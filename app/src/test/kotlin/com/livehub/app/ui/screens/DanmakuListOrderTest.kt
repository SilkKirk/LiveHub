package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiState
import com.livehub.core.designsystem.LiveHubTheme
import com.livehub.core.model.DanmakuMessage
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The portrait danmaku list has to read top-to-bottom as time moves forward.
 *
 * It was `asReversed()` (newest at the top) combined with `animateScrollToItem(0)` on every
 * new message. Those two cancel out frame by frame: the new row is inserted above
 * everything, the viewport scrolls back up to it, and what the eye sees is the existing
 * chat sliding *upward* — reading as reversed even though the ordering was documented as
 * "newest first".
 *
 * These tests pin the order rather than the animation, because the bug was a visible
 * consequence of the two being wrong *together*.
 *
 * Composed inside [LiveHubTheme]: every row of the list is `bodySmall`, which the app draws at
 * 13sp/18sp where Compose's default is 12sp/16sp. Row height decides how many messages fit the
 * panel and therefore what "visible" means to the assertions below, so the panel is measured in the
 * scale the app uses rather than in Material's.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DanmakuListOrderTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun message(index: Int, text: String) = DanmakuMessage(
    roomKey = "douyu:1",
    userName = "观众$index",
    text = text,
    // Strictly increasing, so any sorting the UI applies is separable from arrival order.
    receivedAt = 1_000L + index,
  )

  private fun state(
    messages: List<DanmakuMessage>,
    blockedKeywords: List<String> = emptyList(),
  ) = UiState(
    room = Streamer(
      platform = Platform.DOUYU,
      roomId = "1",
      streamerName = "主播",
      title = "直播中",
      isLive = true,
    ),
    danmaku = messages,
    danmakuEnabled = true,
    blockedKeywords = blockedKeywords,
  )

  /** Messages the composition is currently showing; mutated to simulate new arrivals. */
  private val current = mutableStateOf<List<DanmakuMessage>>(emptyList())

  private fun composePanel(
    messages: List<DanmakuMessage>,
    blockedKeywords: List<String> = emptyList(),
  ) {
    current.value = messages
    composeRule.setContent {
      LiveHubTheme {
        Box(modifier = Modifier.size(360.dp, 640.dp)) {
          // `current.value` is read inside the composition, so assigning to it recomposes
          // rather than requiring a second `setContent` (which the rule forbids).
          DanmakuPanel(state(current.value, blockedKeywords))
        }
      }
    }
    composeRule.waitForIdle()
  }

  /**
   * With few enough messages to fit, every row is visible and the vertical order is
   * readable straight from the composition.
   */
  @Test
  fun `older danmaku sits above newer ones`() {
    composePanel(listOf(message(0, "第一条"), message(1, "第二条"), message(2, "第三条")))

    val first = composeRule.onNodeWithText("第一条").fetchSemanticsNode().boundsInRoot.top
    val second = composeRule.onNodeWithText("第二条").fetchSemanticsNode().boundsInRoot.top
    val third = composeRule.onNodeWithText("第三条").fetchSemanticsNode().boundsInRoot.top

    assertTrue("第一条 should be above 第二条 (was $first vs $second)", first < second)
    assertTrue("第二条 should be above 第三条 (was $second vs $third)", second < third)
  }

  /**
   * The newest message must be on screen after an update.
   *
   * This is the part the old code got wrong in the opposite direction: it pinned position
   * 0, which under the reversed list was the newest row and under a correctly ordered list
   * is the oldest — so following the tail is what has to happen instead.
   */
  @Test
  fun `the newest message stays visible as more arrive`() {
    val early = (0 until 6).map { message(it, "旧弹幕$it") }
    composePanel(early)
    composeRule.onNodeWithText("旧弹幕5").assertIsDisplayed()

    // Feed a new tail in, the way the collector does.
    current.value = early + listOf(message(6, "最新弹幕"))
    composeRule.waitForIdle()

    composeRule.onNodeWithText("最新弹幕").assertIsDisplayed()
  }

  @Test
  fun `blocked keywords are filtered out of the list`() {
    composePanel(
      messages = listOf(message(0, "正常内容"), message(1, "广告内容")),
      blockedKeywords = listOf("广告"),
    )

    composeRule.onNodeWithText("正常内容").assertIsDisplayed()
    val blocked = composeRule.onAllNodesWithText("广告内容").fetchSemanticsNodes()
    assertTrue("a blocked keyword must not render", blocked.isEmpty())
  }
}
