package com.livehub.app.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livehub.app.di.AppContainer
import com.livehub.core.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Interaction tests for [MainViewModel] on the JVM.
 *
 * Every navigation, search and follow action was previously only eyeballed in
 * screenshots. These tests drive the same [UiAction] stream the UI emits and assert on
 * the resulting [UiState], which is where state-machine bugs hide: a screen that never
 * becomes reachable, a query that is silently dropped, a follow that does not survive a
 * round trip through the store.
 *
 * Robolectric provides the Android framework; network calls are avoided by asserting
 * only on the actions that do not require a live platform.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MainViewModelTest {

  private lateinit var container: AppContainer
  private lateinit var viewModel: MainViewModel

  @Before
  fun setUp() {
    container = AppContainer(ApplicationProvider.getApplicationContext())
    // DataStore is a process-wide file, so any value a test persists leaks into whichever
    // test runs next. That is not hypothetical: the theme test wrote "light" and the next
    // test's "a fresh install starts at System" assertion saw it. Reset the whole settings
    // record so every test starts from the same state regardless of order.
    container.preferences.update { com.livehub.app.di.UserSettings() }
    // Start from a clean follow list so tests do not depend on ordering.
    container.followStore.replaceAll(emptyList())
    viewModel = MainViewModel(container)
    idle()
  }

  /** Drains the main looper so `viewModelScope` work scheduled on it completes. */
  private fun idle() {
    shadowOf(Looper.getMainLooper()).idle()
  }

  /**
   * Idles until [condition] holds, or fails after [timeoutMillis].
   *
   * `idle()` alone only runs what is already queued on the main looper. Work that hops to
   * `Dispatchers.IO` — which the sync path now does, so its sockets stay off the UI thread —
   * resumes on the main looper only once the background thread is done, so a single idle
   * observes the state from *before* the call completed.
   */
  private fun awaitCondition(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
      idle()
      if (condition()) return
      Thread.sleep(10)
    }
    idle()
    assertTrue("condition not met within ${timeoutMillis}ms", condition())
  }

  private val state: UiState get() = viewModel.state.value

  private fun streamer(roomId: String, name: String, platform: Platform = Platform.DOUYU) =
      com.livehub.core.model.Streamer(
        platform = platform,
        roomId = roomId,
        streamerName = name,
        title = "$name 的直播",
        isLive = true,
      )

  // ---------------------------------------------------------------- navigation ---

  @Test
  fun `the app starts on the home screen`() {
    assertEquals(Screen.Home, state.screen)
  }

  @Test
  fun `navigating to every dock screen updates the current screen`() {
    listOf(Screen.Follow, Screen.Search, Screen.Sync, Screen.Settings).forEach { target ->
      viewModel.onAction(UiAction.Navigate(target))
      idle()
      assertEquals("navigating to $target", target, state.screen)
    }
  }

  @Test
  fun `back from a non-home screen returns home`() {
    viewModel.onAction(UiAction.Navigate(Screen.Sync))
    idle()
    viewModel.onAction(UiAction.Back)
    idle()
    assertEquals(Screen.Home, state.screen)
  }

  @Test
  fun `back from home is a no-op`() {
    viewModel.onAction(UiAction.Back)
    idle()
    assertEquals(Screen.Home, state.screen)
  }

  @Test
  fun `opening a room switches to the player and closing returns home`() {
    val room = streamer("1", "主播")
    viewModel.onAction(UiAction.OpenRoom(room))
    idle()

    assertEquals(Screen.Player, state.screen)
    assertEquals(room.key, state.room?.key)

    viewModel.onAction(UiAction.ClosePlayer)
    idle()

    assertEquals(Screen.Home, state.screen)
    assertNull(state.room)
    assertTrue("the danmaku buffer must be cleared", state.danmaku.isEmpty())
  }

  /**
   * Closing a room returns to where it was opened, not always Home.
   *
   * The player and search both hardcoded Home, so opening a room from the follow list and
   * closing it dropped the user on the browse tab with their place lost.
   */
  @Test
  fun `closing a room opened from the follow list returns to the follow list`() {
    viewModel.onAction(UiAction.Navigate(Screen.Follow))
    idle()
    val room = streamer("7", "主播")
    viewModel.onAction(UiAction.OpenRoom(room))
    idle()
    assertEquals(Screen.Player, state.screen)

    viewModel.onAction(UiAction.ClosePlayer)
    idle()
    assertEquals(Screen.Follow, state.screen)
  }

  @Test
  fun `leaving search returns to the screen it was opened from`() {
    viewModel.onAction(UiAction.Navigate(Screen.Follow))
    idle()
    viewModel.onAction(UiAction.Navigate(Screen.Search))
    idle()
    assertEquals(Screen.Search, state.screen)

    viewModel.onAction(UiAction.Back)
    idle()
    assertEquals(Screen.Follow, state.screen)
  }

  // -------------------------------------------------------------------- search ---

  @Test
  fun `typing updates the query without submitting`() {
    viewModel.onAction(UiAction.UpdateQuery("英雄联盟"))
    idle()

    assertEquals("英雄联盟", state.searchQuery)
    assertTrue(state.searchResults.isEmpty())
  }

  @Test
  fun `submitting an empty query reports why nothing happened`() {
    viewModel.onAction(UiAction.UpdateQuery("   "))
    viewModel.onAction(UiAction.SubmitSearch)
    idle()

    assertNotNull(state.message)
    assertTrue(state.message!!.contains("请输入"))
    assertTrue(state.searchResults.isEmpty())
  }

  @Test
  fun `clearing the query resets the results view`() {
    viewModel.onAction(UiAction.UpdateQuery("abc"))
    viewModel.onAction(UiAction.UpdateQuery(""))
    idle()

    assertEquals("", state.searchQuery)
  }

  // ------------------------------------------------------------------- follows ---

  @Test
  fun `following a room adds it to the list and unfollowing removes it`() {
    val room = streamer("42", "测试主播")

    viewModel.onAction(UiAction.ToggleFollow(room))
    idle()

    assertEquals(1, state.followed.size)
    assertEquals(room.key, state.followed.first().key)
    assertTrue(container.followStore.isFollowed(room.platform, room.roomId))

    viewModel.onAction(UiAction.ToggleFollow(room))
    idle()

    assertTrue("unfollow must empty the list", state.followed.isEmpty())
    assertFalse(container.followStore.isFollowed(room.platform, room.roomId))
  }

  @Test
  fun `following the same room twice does not duplicate it`() {
    val room = streamer("42", "测试主播")

    viewModel.onAction(UiAction.ToggleFollow(room))
    idle()
    viewModel.onAction(UiAction.ToggleFollow(room))
    idle()
    viewModel.onAction(UiAction.ToggleFollow(room))
    idle()

    assertEquals(1, state.followed.size)
  }

  @Test
  fun `the follow list survives a new view model instance`() {
    val room = streamer("7", "持久化主播")
    viewModel.onAction(UiAction.ToggleFollow(room))
    idle()

    val recreated = MainViewModel(container)
    idle()

    assertEquals(1, recreated.state.value.followed.size)
    assertEquals(room.key, recreated.state.value.followed.first().key)
  }

  @Test
  fun `reordering persists the new order`() {
    val first = streamer("1", "第一")
    val second = streamer("2", "第二")
    val third = streamer("3", "第三")
    listOf(first, second, third).forEach {
      viewModel.onAction(UiAction.ToggleFollow(it))
      idle()
    }

    // Until the user reorders, the list is ordered by recency, so the newest follow
    // comes first. Derive the starting order instead of assuming insertion order.
    val before = state.followed.map { it.key }
    assertEquals(3, before.size)

    val moving = state.followed.last()
    viewModel.onAction(UiAction.MoveFollow(moving, 0))
    idle()

    val expected = listOf(moving.key) + before.filterNot { it == moving.key }
    assertEquals(expected, state.followed.map { it.key })

    // And the store agrees after a reload.
    assertEquals(expected, container.followStore.followed().map { it.key })
  }

  // ---------------------------------------------------------------- preferences ---

  @Test
  fun `toggling danmaku persists across a restart`() {
    viewModel.onAction(UiAction.ToggleDanmaku(false))
    idle()

    assertFalse(state.danmakuEnabled)
    assertFalse(container.preferences.settings.danmakuEnabled)

    val recreated = MainViewModel(container)
    idle()
    assertFalse(recreated.state.value.danmakuEnabled)
  }

  @Test
  fun `blocked keywords are trimmed, de-duplicated and capped`() {
    viewModel.onAction(
      UiAction.SetBlockedKeywords(listOf("  广告 ", "广告", "666", "", "刷屏", "广告")),
    )
    idle()

    assertEquals(listOf("广告", "666", "刷屏"), state.blockedKeywords)
    assertEquals(state.blockedKeywords, container.preferences.settings.blockedKeywords)
  }

  @Test
  fun `selecting a platform is remembered`() {
    viewModel.onAction(UiAction.SelectPlatform(Platform.HUYA))
    idle()

    assertEquals(Platform.HUYA, state.platform)
    assertEquals(Platform.HUYA.id, container.preferences.settings.lastPlatform)
  }

  /**
   * The toggle cycles system → dark → light → system.
   *
   * A two-state boolean could not express "follow the system", and it also made 浅色
   * unreachable and lost the choice on restart.
   */
  @Test
  fun `theme toggle cycles through all three modes and persists`() {
    assertEquals(com.livehub.core.designsystem.ThemeMode.System, state.themeMode)

    viewModel.onAction(UiAction.ToggleTheme)
    idle()
    assertEquals(com.livehub.core.designsystem.ThemeMode.Dark, state.themeMode)
    assertEquals("dark", container.preferences.settings.themeMode)
    assertTrue(state.darkTheme)

    viewModel.onAction(UiAction.ToggleTheme)
    idle()
    assertEquals(com.livehub.core.designsystem.ThemeMode.Light, state.themeMode)
    assertEquals("light", container.preferences.settings.themeMode)
    assertTrue("light mode must not report dark", !state.darkTheme)

    viewModel.onAction(UiAction.ToggleTheme)
    idle()
    assertEquals(com.livehub.core.designsystem.ThemeMode.System, state.themeMode)
    assertEquals("system", container.preferences.settings.themeMode)
  }

  @Test
  fun `an explicit theme choice is persisted`() {
    viewModel.onAction(UiAction.SetThemeMode(com.livehub.core.designsystem.ThemeMode.Light))
    idle()
    assertEquals("light", container.preferences.settings.themeMode)
    assertEquals(com.livehub.core.designsystem.ThemeMode.Light, state.themeMode)
  }

  /**
   * Picking a quality must be remembered per platform.
   *
   * `UserPreferences.preferredQuality` and its setter existed from the start, but nothing
   * ever wrote or read them: every room reopened at the platform default, so the selector
   * felt broken even after it started actually re-resolving.
   */
  @Test
  fun `selecting a quality is remembered for the next room on that platform`() {
    // There is no live room in this test, so drive the preference through the same path the
    // UI uses: a selected variant carries the quality code.
    viewModel.onAction(UiAction.OpenRoom(streamer("1", "主播")))
    idle()
    viewModel.onAction(
      UiAction.SelectVariant(
        com.livehub.core.model.StreamVariant(
          qualityLabel = "高清",
          url = "https://example.com/a.flv",
          lineName = "ws-h5",
          qualityCode = 2,
        ),
      ),
    )
    idle()

    assertEquals(2, container.preferences.settings.preferredQuality[Platform.DOUYU.id])
  }

  // ----------------------------------------------------------------------- sync ---

  @Test
  fun `the sync scope selection is persisted`() {
    viewModel.onAction(UiAction.SetSyncScopes(setOf(com.livehub.core.sync.SyncScope.FOLLOWS)))
    idle()

    assertEquals(setOf(com.livehub.core.sync.SyncScope.FOLLOWS), state.syncScopes)
    assertEquals(
      listOf(com.livehub.core.sync.SyncScope.FOLLOWS.wireName),
      container.preferences.settings.syncScopes,
    )
  }

  @Test
  fun `syncing with no paired device explains why nothing happened`() {
    viewModel.onAction(UiAction.SyncNow)
    // The exchange runs on Dispatchers.IO, so the snackbar message arrives after the
    // background hop completes rather than on the next main-loop tick.
    awaitCondition { !state.syncing && state.message != null }

    assertFalse(state.syncing)
    assertNotNull(state.message)
    assertTrue("a message must explain the outcome", state.message!!.isNotBlank())
  }

  @Test
  fun `unpairing an unknown device is harmless`() {
    viewModel.onAction(UiAction.UnpairPeer(9999L))
    idle()

    assertTrue(state.peers.isEmpty())
  }

  /**
   * Arriving at the sync screen has to show a usable matching code.
   *
   * The whole pairing flow starts by reading this code off the other device, so an empty
   * or already-expired one makes 关联设备 impossible to complete.
   */
  @Test
  fun `opening the sync screen shows a six digit matching code`() {
    viewModel.onAction(UiAction.Navigate(Screen.Sync))
    idle()

    assertEquals(6, state.pairingCode.length)
    assertTrue("code must be digits: ${state.pairingCode}", state.pairingCode.all { it.isDigit() })
    assertTrue("code must have time left", state.pairingCodeSecondsLeft > 0)
  }

  @Test
  fun `refreshing the matching code issues a new one with a full window`() {
    viewModel.onAction(UiAction.Navigate(Screen.Sync))
    idle()
    val first = state.pairingCode

    viewModel.onAction(UiAction.RefreshPairingCode)
    idle()

    assertEquals(6, state.pairingCode.length)
    // Not asserting "differs": a random draw can legitimately repeat. The window is what
    // must reset, and that is what the countdown is derived from.
    assertTrue(state.pairingCodeSecondsLeft > 0)
    assertTrue("a refreshed code must still be six digits ($first)", state.pairingCode.all { it.isDigit() })
  }

  /**
   * A short code is refused before any socket opens.
   *
   * Without this the sweep would run on a partial code and report "没有找到设备", which
   * reads as a network problem rather than as "you have not finished typing".
   */
  @Test
  fun `a code that is not six digits is refused without searching`() {
    viewModel.onAction(UiAction.Navigate(Screen.Sync))
    idle()

    viewModel.onAction(UiAction.PairWithCode("123"))
    idle()

    assertNotNull(state.message)
    assertTrue(state.message!!.contains("六位"), state.message!!)
    assertFalse(state.pairing)
  }

  // ------------------------------------------------------------------ messages ---

  @Test
  fun `dismissing a message clears both message and error`() {
    viewModel.onAction(UiAction.UpdateQuery(""))
    viewModel.onAction(UiAction.SubmitSearch)
    idle()
    assertNotNull(state.message)

    viewModel.onAction(UiAction.DismissMessage)
    idle()

    assertNull(state.message)
    assertNull(state.error)
  }
}
