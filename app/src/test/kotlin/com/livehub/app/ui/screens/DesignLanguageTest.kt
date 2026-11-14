package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.core.designsystem.LiveHubTheme
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import com.livehub.core.sync.SyncLogEntry
import com.livehub.core.sync.TrustedPeer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The page-level layout rule the round-104 redesign introduced, pinned so it cannot drift.
 *
 * Two things are asserted, and neither is about a pixel value:
 *
 *  * **Every page that has no other masthead opens with one.** 关注/同步/设置 used to start with a
 *    section label or a sentence welded to its counter; they now start with the same `PageHeader`,
 *    and the title has to be above the first thing it introduces. 首页 and 搜索 are the exceptions
 *    by design — the platform tab row and the search field *are* their mastheads (a masthead on the
 *    home grid would cost 52dp of the smallest phone, which rounds 22/78 spent a lot of effort
 *    getting back).
 *  * **A tile's facts are under the picture, not on it.** Round 104 moved the audience figure from
 *    a black pill on the cover into the text block. Asserting "the number is lower than the title"
 *    is what tells a future edit where the number belongs; asserting only that it exists would pass
 *    with it back on the cover.
 *
 * Everything is composed in [LiveHubTheme] at the 320×640 window the app targets, because both
 * assertions are about where a wrapped line ends up and the type scale decides that.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class DesignLanguageTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun room(name: String, viewers: Long = 129_000) = Streamer(
    platform = Platform.DOUYU,
    roomId = name,
    streamerName = name,
    title = "直播标题 $name",
    viewerCount = viewers,
    isLive = true,
  )

  private fun composeInWindow(content: @androidx.compose.runtime.Composable () -> Unit) {
    composeRule.setContent {
      LiveHubTheme {
        Box(modifier = Modifier.size(320.dp, 640.dp)) { content() }
      }
    }
    composeRule.waitForIdle()
  }

  /**
   * The top edge of one *text*, in dp.
   *
   * `useUnmergedTree` is load-bearing: a tile's whole column is clickable, so the merged tree
   * answers to every line it contains with the tile's own box. Comparing "the count is under the
   * title" against the merged node would compare the tile with itself and pass whatever the layout
   * did. `SearchScreenTest` documents the same trap for its result rows.
   */
  private fun topOf(text: String): Float =
    composeRule.onNodeWithText(text, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top

  @Test
  fun `settings opens with its masthead above the first card`() {
    composeInWindow { SettingsScreen(state = UiState(screen = Screen.Settings), onAction = {}) }

    composeRule.onNodeWithText("设置").assertIsDisplayed()
    assertTrue(
      "设置 has to be above 外观, not somewhere below it",
      topOf("设置") < topOf("外观"),
    )
  }

  @Test
  fun `sync opens with its masthead above the first card`() {
    val state = UiState(
      screen = Screen.Sync,
      deviceName = "Pixel 7",
      // A code with no time left: the countdown `LaunchedEffect` would otherwise keep a coroutine
      // suspended for four minutes of virtual time and `waitForIdle` would never settle.
      pairingCode = "482913",
      pairingCodeSecondsLeft = 0,
      peers = listOf(TrustedPeer(2, "小米平板 6", "secret", 1_700_000_000_000)),
      syncLog = listOf(
        SyncLogEntry(1_700_000_080_000, SyncLogEntry.Direction.PUSH, "小米平板 6", "关注列表", 3, true),
      ),
    )
    composeInWindow { SyncScreen(state = state, onAction = {}) }

    composeRule.onNodeWithText("同步").assertIsDisplayed()
    // 刷新匹配码, not 本机: 本机 is both this card's title and the supporting line of the 我的设备
    // row further down, so a text query on it is ambiguous as soon as the page is tall enough to
    // compose both.
    assertTrue(
      "同步 has to be above the first card's own action, not somewhere below it",
      topOf("同步") < topOf("刷新匹配码"),
    )
  }

  /** The counter moved out of the title line and under it, so the title is now a single word. */
  @Test
  fun `follow opens with its masthead and keeps its live counter underneath`() {
    val state = UiState(
      screen = Screen.Follow,
      followed = listOf(room("一条小团团"), room("旭旭宝宝", viewers = 320_000)),
    )
    composeInWindow { FollowScreen(state = state, onAction = {}) }

    composeRule.onNodeWithText("关注").assertIsDisplayed()
    composeRule.onNodeWithText("2/2 正在直播").assertIsDisplayed()
    assertTrue(
      "the counter has to be under the title, not welded into it",
      topOf("关注") < topOf("2/2 正在直播"),
    )
  }

  /**
   * The audience figure is a fact in the text block, not an overlay on the picture.
   *
   * `CardInformationDensityTest` already asserts that a followed room *shows* its audience; this
   * one asserts *where*, which is the half the move in round 104 changed.
   */
  @Test
  fun `a tile draws its audience figure under its title`() {
    val state = UiState(
      screen = Screen.Home,
      platform = Platform.DOUYU,
      rooms = listOf(room("一条小团团", viewers = 129_000)),
    )
    composeInWindow { HomeScreen(state = state, onAction = {}) }

    composeRule.onAllNodesWithText("12.9万").fetchSemanticsNodes().let {
      assertTrue("the audience figure has to be drawn at all", it.isNotEmpty())
    }
    assertTrue(
      "the audience figure belongs in the text block under the title, not on the cover",
      topOf("12.9万") > topOf("直播标题 一条小团团"),
    )
  }
}
