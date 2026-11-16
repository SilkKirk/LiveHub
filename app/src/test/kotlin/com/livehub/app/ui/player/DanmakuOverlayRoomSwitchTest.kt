package com.livehub.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.livehub.core.designsystem.LiveHubTheme
import com.livehub.core.model.DanmakuMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Switching rooms must stop the previous room's labels, not just stop animating them.
 *
 * The report was "切换直播间但弹幕没有切换，上一个直播间的弹幕显示在当前直播间了". `UiState.danmaku` was
 * already cleared when a room opened, and the overlay already refused to *re-animate* the old
 * entries — but the labels it had put on screen are owned by the overlay, and nothing removed them.
 * Three consequences, all from that one omission:
 *
 *  * the old room's comments kept crossing the new room for their whole flight time (up to ~8s);
 *  * the lane schedule stayed booked by them, so the new room's first messages could not be placed;
 *  * and because the batch is marked as animated *after* the loop, a message that was never placed
 *    was still recorded as shown — lost outright.
 *
 * The fix keys an effect on the stream (`DanmakuMessage.roomKey`, which the collector already
 * stamps) and clears both the flying list and the lane timetable. `null` counts as a change: the
 * buffer is emptied before the next room's first message arrives, so that gap is exactly where the
 * clearing has to happen.
 *
 * The clock is frozen and advanced by hand: a flying label is an infinite-ish animation, and an
 * auto-advancing clock would run it to completion between the assertions.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DanmakuOverlayRoomSwitchTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun message(roomKey: String, text: String, seq: Long) = DanmakuMessage(
    roomKey = roomKey,
    userName = "观众",
    text = text,
    seq = seq,
  )

  private fun frame() = composeRule.mainClock.advanceTimeByFrame()

  @Test
  fun `switching rooms stops the previous room's labels`() {
    val feed = mutableStateOf(listOf(message("douyu:1", "上一条", 1)))
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      LiveHubTheme {
        Box(modifier = Modifier.size(320.dp, 640.dp)) {
          DanmakuOverlay(
            messages = feed.value,
            enabled = true,
            blockedKeywords = emptyList(),
          )
        }
      }
    }
    // A label starts just outside the right edge and is clipped until it has moved in, so existence
    // is the assertion here, not visibility: the question is whether the node is still in the
    // overlay, and a freshly placed one would fail `assertIsDisplayed` for an unrelated reason.
    repeat(4) { frame() }
    composeRule.onNodeWithText("上一条").assertExists()

    // The room changes: the buffer is emptied (as `openRoom` does) and the new stream starts.
    feed.value = emptyList()
    frame()
    feed.value = listOf(message("douyu:2", "新房间", 1))
    repeat(4) { frame() }

    composeRule.onNodeWithText("上一条").assertDoesNotExist()
    composeRule.onNodeWithText("新房间").assertExists()
  }
}
