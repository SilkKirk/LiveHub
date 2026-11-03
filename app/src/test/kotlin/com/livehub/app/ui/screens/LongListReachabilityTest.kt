package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.app.ui.components.BiliLoginUi
import com.livehub.core.sync.SyncLogEntry
import com.livehub.core.sync.TrustedPeer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The bottom of the settings and sync pages has to stay reachable on a small phone.
 *
 * Both screens are `LazyColumn`s, so the content *is* scrollable today — but nothing said so,
 * and neither screen had a single test. `13-sync.png` is what made the gap visible: at
 * 320x640 the exchange log sits entirely below the fold, the 我的设备 title is cut in half by
 * the bottom bar, and the only thing proving the log existed at all was the source. Swapping
 * `LazyColumn` for a `Column` (or putting the log inside one tall `item`) would have been a
 * silent regression: every test stayed green and the log became unreachable.
 *
 * `performScrollToNode` is the assertion that closes that hole. It fails when the target is
 * not in the tree *and* when the tree has nothing to scroll, so it pins both "the section is
 * composed" and "the user can get to it". `assertIsDisplayed` then rejects the case the
 * scroll leaves the node parked outside the viewport.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class LongListReachabilityTest {

  @get:Rule
  val composeRule = createComposeRule()

  /**
   * The last row of the exchange log, which is the deepest thing on the page.
   *
   * The scope is unique among the entries so the matcher cannot accidentally hit an earlier
   * row and pass without the list ever scrolling.
   */
  private val lastLogRow = "搜索历史 · 小米平板 6"

  private fun syncState() = UiState(
    screen = Screen.Sync,
    deviceName = "Pixel 7",
    localAddress = "192.168.1.23:47653",
    // A code with no time left on it: the countdown `LaunchedEffect` would otherwise keep a
    // coroutine suspended for four minutes of virtual time while `waitForIdle` settles.
    pairingCode = "482913",
    pairingCodeSecondsLeft = 0,
    peers = listOf(
      TrustedPeer(2, "小米平板 6", "secret", 1_700_000_000_000, lastSeenAt = 1_700_000_100_000),
    ),
    syncLog = listOf(
      SyncLogEntry(1_700_000_080_000, SyncLogEntry.Direction.PUSH, "小米平板 6", "关注列表", 3, true),
      SyncLogEntry(1_700_000_085_000, SyncLogEntry.Direction.PULL, "小米平板 6", "分区订阅", 2, true),
      SyncLogEntry(1_700_000_090_000, SyncLogEntry.Direction.PUSH, "小米平板 6", "播放设置", 1, true),
      SyncLogEntry(1_700_000_095_000, SyncLogEntry.Direction.PULL, "小米平板 6", "关注列表", 4, true),
      SyncLogEntry(1_700_000_100_000, SyncLogEntry.Direction.PUSH, "小米平板 6", "分区订阅", 1, false),
      SyncLogEntry(1_700_000_105_000, SyncLogEntry.Direction.PULL, "小米平板 6", "播放设置", 2, true),
      SyncLogEntry(1_700_000_110_000, SyncLogEntry.Direction.PUSH, "小米平板 6", "关注列表", 1, true),
      SyncLogEntry(1_700_000_115_000, SyncLogEntry.Direction.PUSH, "小米平板 6", "搜索历史", 2, true),
    ),
  )

  private fun settingsState() = UiState(
    screen = Screen.Settings,
    blockedKeywords = listOf("广告", "刷屏", "666"),
    bilibiliLogin = BiliLoginUi(loggedIn = false),
  )

  /** 320x640: the smallest phone the renders cover, and where the log falls off the page. */
  private fun composeInSmallWindow(content: @Composable () -> Unit) {
    composeRule.setContent {
      Box(modifier = Modifier.size(320.dp, 640.dp)) { content() }
    }
    composeRule.waitForIdle()
  }

  @Test
  fun `the last sync log row is reachable on a 320x640 screen`() {
    composeInSmallWindow { SyncScreen(state = syncState(), onAction = {}) }

    composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(lastLogRow))

    composeRule.onNodeWithText(lastLogRow).assertIsDisplayed()
  }

  /**
   * The log header is reached as well, not just the row that was scrolled to.
   *
   * A log that is technically scrollable but whose heading never arrives is still a page a
   * user cannot tell apart from one with no log at all.
   */
  @Test
  fun `the sync log heading is reachable on a 320x640 screen`() {
    composeInSmallWindow { SyncScreen(state = syncState(), onAction = {}) }

    composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("同步记录"))

    composeRule.onNodeWithText("同步记录").assertIsDisplayed()
  }

  @Test
  fun `the about section is reachable on a 320x640 screen`() {
    composeInSmallWindow { SettingsScreen(state = settingsState(), onAction = {}) }

    composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("LiveHub"))

    composeRule.onNodeWithText("LiveHub").assertIsDisplayed()
  }

  /**
   * The blocked-keyword list grows without bound, so it is what pushes the account and about
   * sections off the bottom on a real phone. Ten entries stand in for a user who has been
   * adding them for a while.
   */
  @Test
  fun `the about section is still reachable behind a long keyword list`() {
    val keywords = (1..10).map { "屏蔽词$it" }
    composeInSmallWindow {
      SettingsScreen(state = settingsState().copy(blockedKeywords = keywords), onAction = {})
    }

    composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("LiveHub"))

    composeRule.onNodeWithText("LiveHub").assertIsDisplayed()
  }
}
