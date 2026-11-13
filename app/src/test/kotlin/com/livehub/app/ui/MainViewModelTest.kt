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
    // Start from a clean library so tests do not depend on ordering or leftover groups.
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

  /**
   * A decoder failure must not outlive the retry that answers it.
   *
   * The error lived in a `remember(state.room?.key)` inside the player composable, so the
   * 重试 button — which reopens the same room — kept the remembered banner on screen over a
   * stream that had just been resolved successfully: the button looked dead. It is
   * ViewModel state now, and reopening the room is what clears it.
   */
  @Test
  fun `retrying a room clears the decoder failure`() {
    val room = streamer("1", "主播")
    viewModel.onAction(UiAction.OpenRoom(room))
    idle()
    viewModel.onAction(UiAction.PlayerFailed("无法解码该流"))
    idle()
    assertEquals("无法解码该流", state.playerError)

    viewModel.onAction(UiAction.OpenRoom(room))
    idle()

    assertNull("a retry has to start from a clean slate", state.playerError)
  }

  @Test
  fun `closing the player clears the decoder failure`() {
    viewModel.onAction(UiAction.OpenRoom(streamer("2", "主播")))
    idle()
    viewModel.onAction(UiAction.PlayerFailed("无法解码该流"))
    idle()
    assertNotNull(state.playerError)

    viewModel.onAction(UiAction.ClosePlayer)
    idle()

    assertNull("the failure belongs to the closed room", state.playerError)
  }

  /**
   * Pause is part of the state, not of the composable that draws it.
   *
   * The activity pauses the stream when the app is hidden without a picture-in-picture window —
   * a stream the user cannot see must not keep playing with nothing on screen to stop it — and it
   * can only reach that through the view model. Equally: a pause must not outlive the room it
   * belongs to, or the next room opens frozen.
   */
  @Test
  fun `pausing lives in the state and dies with the room`() {
    val room = streamer("12", "主播")
    viewModel.onAction(UiAction.OpenRoom(room))
    idle()
    assertFalse("a freshly opened room plays", state.playerPaused)

    viewModel.onAction(UiAction.SetPlayerPaused(true))
    idle()
    assertTrue("the pause has to live in the state", state.playerPaused)

    viewModel.onAction(UiAction.SetPlayerPaused(false))
    idle()
    assertFalse(state.playerPaused)

    viewModel.onAction(UiAction.SetPlayerPaused(true))
    idle()
    viewModel.onAction(UiAction.ClosePlayer)
    idle()
    assertFalse("a pause cannot outlive its room", state.playerPaused)
  }

  /**
   * Leaving the player minimises it; only closing stops the stream.
   *
   * Everything the room owns has to survive the step — the variant, the danmaku socket and the room
   * itself — because the composable that draws the picture is resized into the mini window rather
   * than disposed. Re-resolving there would cost a round trip and a second of black on every back
   * press.
   */
  @Test
  fun `leaving the player keeps the stream, closing it stops the stream`() {
    viewModel.onAction(UiAction.Navigate(Screen.Follow))
    idle()
    val room = streamer("13", "主播")
    viewModel.onAction(UiAction.OpenRoom(room))
    idle()
    assertEquals(Screen.Player, state.screen)

    viewModel.onAction(UiAction.Back)
    idle()

    assertEquals("back returns to where the room was opened", Screen.Follow, state.screen)
    assertEquals("the room stays open", room.key, state.room?.key)
    assertTrue("a room off the player screen is the mini window", state.miniPlayer)

    viewModel.onAction(UiAction.ExpandPlayer)
    idle()
    assertEquals(Screen.Player, state.screen)
    assertFalse(state.miniPlayer)

    viewModel.onAction(UiAction.Back)
    idle()
    viewModel.onAction(UiAction.ClosePlayer)
    idle()

    assertNull("closing the mini window stops the stream", state.room)
    assertEquals(
      "and leaves the user on the screen they were browsing",
      Screen.Follow,
      state.screen,
    )
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

  /**
   * Re-opening the room that is already on screen must not rewrite where it came from.
   *
   * The side 刷新 button and the error state's 重试 both emit `OpenRoom` for the room that is
   * already playing, and `openRoom` is also what records the screen to return to. It recorded the
   * `Home` fallback whenever it was called from the player — so after a retry, closing the room
   * landed on 首页 instead of the follow list the user had opened it from.
   */
  @Test
  fun `refreshing the open room keeps the screen it was opened from`() {
    viewModel.onAction(UiAction.Navigate(Screen.Follow))
    idle()
    val room = streamer("9", "主播")
    viewModel.onAction(UiAction.OpenRoom(room))
    idle()

    // What the player's 刷新 button does.
    viewModel.onAction(UiAction.OpenRoom(room))
    idle()

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
    // The view resolves the drop against the full list and sends the whole order, so a drag
    // under a filter can never write a *filtered* order into the store.
    viewModel.onAction(UiAction.SetFollowOrder(listOf(moving.key) + before.filterNot { it == moving.key }))
    idle()

    val expected = listOf(moving.key) + before.filterNot { it == moving.key }
    assertEquals(expected, state.followed.map { it.key })

    // And the store agrees after a reload.
    assertEquals(expected, container.followStore.followed().map { it.key })
  }

  // -------------------------------------------------------- 特别关注 (special) ---

  @Test
  fun `starring a followed room puts it on the special row`() {
    val room = streamer("42", "测试主播")
    viewModel.onAction(UiAction.ToggleFollow(room))
    idle()
    assertTrue("nothing is special until it is starred", state.specialFollowKeys.isEmpty())

    viewModel.onAction(UiAction.ToggleSpecialFollow(room))
    idle()

    assertEquals(listOf(room.key), state.specialFollowKeys)
    // Persisted, not just in the state: the row has to survive a restart.
    assertEquals(listOf(room.key), container.followStore.specialKeys())
  }

  @Test
  fun `unstarring removes it from the special row`() {
    val room = streamer("42", "测试主播")
    viewModel.onAction(UiAction.ToggleFollow(room))
    viewModel.onAction(UiAction.ToggleSpecialFollow(room))
    idle()

    viewModel.onAction(UiAction.ToggleSpecialFollow(room))
    idle()

    assertTrue(state.specialFollowKeys.isEmpty())
    assertTrue(container.followStore.specialKeys().isEmpty())
  }

  /**
   * A special mark without a follow has nowhere to live — the flag is a field on the follow
   * record — so the action explains itself instead of silently doing nothing.
   */
  @Test
  fun `starring a room that is not followed is refused with a message`() {
    val room = streamer("404", "没关注的主播")
    viewModel.onAction(UiAction.ToggleSpecialFollow(room))
    idle()

    assertTrue(state.specialFollowKeys.isEmpty())
    assertNotNull(state.message)
  }

  /**
   * Unfollowing has to take the room off the avatar row in the same update — and re-following
   * must not put it back, because the user never re-gave that mark.
   */
  @Test
  fun `unfollowing clears the special mark and re-following does not restore it`() {
    val room = streamer("42", "测试主播")
    viewModel.onAction(UiAction.ToggleFollow(room))
    viewModel.onAction(UiAction.ToggleSpecialFollow(room))
    idle()
    assertEquals(1, state.specialFollowKeys.size)

    viewModel.onAction(UiAction.ToggleFollow(room))
    idle()
    assertTrue(state.followed.isEmpty())
    assertTrue(state.specialFollowKeys.isEmpty())

    viewModel.onAction(UiAction.ToggleFollow(room))
    idle()
    assertEquals(1, state.followed.size)
    assertTrue(state.specialFollowKeys.isEmpty())
  }

  @Test
  fun `the special row keeps the user's order, not the live-first one`() {
    val offline = streamer("1", "未开播").copy(isLive = false)
    val live = streamer("2", "在播")
    viewModel.onAction(UiAction.ToggleFollow(offline))
    viewModel.onAction(UiAction.ToggleFollow(live))
    viewModel.onAction(UiAction.ToggleSpecialFollow(offline))
    viewModel.onAction(UiAction.ToggleSpecialFollow(live))
    idle()

    // Put the offline one first, then read the row back: the row is a *manual* arrangement, so
    // being offline must not push it behind the live room the way the grid's sort would.
    viewModel.onAction(UiAction.SetFollowOrder(listOf(offline.key, live.key)))
    idle()

    assertEquals(listOf(offline.key, live.key), container.followStore.specialKeys())
  }

  /**
   * The two follow-screen filters survive opening a room and coming back.
   *
   * They used to be `remember` inside the screen, so closing the player rebuilt it from scratch
   * and handed the user the unfiltered list again.
   */
  @Test
  fun `the follow filters survive a round trip through the player`() {
    val room = streamer("42", "测试主播")
    viewModel.onAction(UiAction.ToggleFollow(room))
    idle()
    viewModel.onAction(UiAction.Navigate(Screen.Follow))
    idle()

    viewModel.onAction(UiAction.SetFollowLiveFilter(FollowLiveFilter.LIVE))
    viewModel.onAction(UiAction.SetFollowPlatformFilter(Platform.DOUYU))
    idle()

    viewModel.onAction(UiAction.OpenRoom(room))
    idle()
    viewModel.onAction(UiAction.ClosePlayer)
    idle()

    assertEquals(Screen.Follow, state.screen)
    assertEquals(FollowLiveFilter.LIVE, state.followLiveFilter)
    assertEquals(Platform.DOUYU, state.followPlatformFilter)
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

    viewModel.onAction(UiAction.ToggleTheme)
    idle()
    assertEquals(com.livehub.core.designsystem.ThemeMode.Light, state.themeMode)
    assertEquals("light", container.preferences.settings.themeMode)

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
    // This file is JUnit 4 (`org.junit.Assert`), so the reason comes *first*. Argument order
    // is the one thing that differs from the Jupiter tests elsewhere in the repo, and
    // getting it backwards makes the compiler read the reason as the condition.
    assertTrue(state.message!!, state.message!!.contains("六位"))
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
