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
    // Start from a clean follow list so tests do not depend on ordering.
    container.followStore.replaceAll(emptyList())
    viewModel = MainViewModel(container)
    idle()
  }

  /** Drains the main looper so `viewModelScope` work scheduled on it completes. */
  private fun idle() {
    shadowOf(Looper.getMainLooper()).idle()
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

  @Test
  fun `theme toggle flips the dark flag`() {
    val before = state.darkTheme
    viewModel.onAction(UiAction.ToggleTheme)
    idle()
    assertEquals(!before, state.darkTheme)
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
    idle()

    assertFalse(state.syncing)
    assertNotNull(state.message)
  }

  @Test
  fun `unpairing an unknown device is harmless`() {
    viewModel.onAction(UiAction.UnpairPeer(9999L))
    idle()

    assertTrue(state.peers.isEmpty())
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
