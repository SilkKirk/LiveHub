package com.livehub.app.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.livehub.core.model.DanmakuMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Overlay behaviour that neither the screenshot harness nor a plain unit test can see.
 *
 * The screenshots freeze the clock, so nothing has moved; a plain JVM test never composes
 * at all. Both would have missed the duplication bug below, which survived nine rounds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DanmakuOverlayTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun message(text: String) = DanmakuMessage(roomKey = "douyu:1", userName = "观众", text = text)

  /**
   * Freezes the animation clock before composing.
   *
   * Each bubble runs an 8 s `Animatable` and `onFinished` removes it. With `autoAdvance`
   * left at its default, `waitForIdle()` runs that animation to completion, so every
   * message has already flown off and been dropped by the time the assertions run.
   */
  private fun freezeClock() {
    composeRule.mainClock.autoAdvance = false
  }

  /**
   * Advances a little virtual time and lets the tree settle.
   *
   * One frame is not enough after a state change: the recomposition that restarts
   * `LaunchedEffect(messages)` must run, the effect body must be dispatched, and the
   * resulting list mutation must be composed in turn. A single `advanceTimeByFrame()`
   * left a newly appended message un-composed. 500 ms is many frames and still well
   * inside the 8 s flight, so every bubble exists and is still mid-screen.
   */
  private fun settle() {
    composeRule.mainClock.advanceTimeBy(500)
    composeRule.waitForIdle()
  }

  /**
   * A new message must not re-add the messages already on screen.
   *
   * `messages` is the ViewModel's whole rolling buffer, so appending one entry changed the
   * list identity and re-ran the effect over *every* entry. With three messages that meant
   * "a" was animated three times, "b" twice and "c" once — the overlay filled with
   * duplicates within seconds of opening a busy room.
   *
   * Compose does not produce two nodes with identical text unless they really are two
   * separate bubbles, so the assertion is on node counts.
   */
  @Test
  fun `appending a message does not duplicate the ones already shown`() {
    freezeClock()
    val first = listOf(message("a"), message("b"))
    var messages by mutableStateOf(first)

    composeRule.setContent {
      DanmakuOverlay(
        messages = messages,
        enabled = true,
        blockedKeywords = emptyList(),
        areaFraction = 1f,
      )
    }
    settle()

    // One more arrives, exactly as the ViewModel's `danmaku + new` update would.
    messages = first + message("c")
    settle()

    composeRule.onAllNodesWithText("a").assertCountEquals(1)
    composeRule.onAllNodesWithText("b").assertCountEquals(1)
    composeRule.onNodeWithText("c").assertExists()
  }

  /** A disabled overlay composes nothing, and therefore costs nothing. */
  @Test
  fun `a disabled overlay renders no messages`() {
    freezeClock()
    composeRule.setContent {
      DanmakuOverlay(
        messages = listOf(message("hidden")),
        enabled = false,
        blockedKeywords = emptyList(),
      )
    }
    settle()

    composeRule.onAllNodesWithText("hidden").assertCountEquals(0)
  }

  /** Blocked keywords never reach the overlay, and are matched case-insensitively. */
  @Test
  fun `blocked keywords are filtered before animating`() {
    freezeClock()
    composeRule.setContent {
      DanmakuOverlay(
        messages = listOf(message("买广告"), message("正常弹幕")),
        enabled = true,
        blockedKeywords = listOf("广告"),
      )
    }
    settle()

    composeRule.onAllNodesWithText("买广告").assertCountEquals(0)
    composeRule.onNodeWithText("正常弹幕").assertExists()
  }
}

/**
 * Lane scheduling, which is pure arithmetic and needs no composition.
 *
 * Plain round-robin assignment — what the overlay did before — hands out lane N+1 while a
 * long danmaku is still crossing lane N, so a short one entering behind it catches up and
 * overlaps. These pin the rule that replaced it.
 */
class DanmakuLaneSchedulingTest {

  @Test
  fun `a free lane is taken immediately`() {
    val lanes = LongArray(3) { 0L }
    assertEquals(0, chooseLane(lanes, now = 1_000))
  }

  @Test
  fun `the lane that frees soonest wins when every lane is busy`() {
    // Lane 2 frees at 1_050, inside the tolerance window; lanes 0 and 1 much later.
    val lanes = longArrayOf(5_000, 4_000, 1_050)
    assertEquals(2, chooseLane(lanes, now = 1_000))
  }

  @Test
  fun `no lane is returned when the soonest is beyond the tolerance`() {
    // Dropping a danmaku beats stacking it on top of another: every live client does this.
    val lanes = longArrayOf(9_000, 9_000, 9_000)
    assertNull(chooseLane(lanes, now = 1_000))
  }

  @Test
  fun `a lane exactly at the tolerance boundary is still usable`() {
    val lanes = longArrayOf(1_120, 9_000)
    assertEquals(0, chooseLane(lanes, now = 1_000))
  }

  @Test
  fun `a wider label keeps its lane busy longer`() {
    val shortLabel = laneFreeAfterMs(textWidthPx = 50f, screenWidthPx = 1_000f, flightMillis = 8_000)
    val longLabel = laneFreeAfterMs(textWidthPx = 500f, screenWidthPx = 1_000f, flightMillis = 8_000)

    assertTrue(
      "a longer label must hold the lane longer ($longLabel vs $shortLabel)",
      longLabel > shortLabel,
    )
  }

  @Test
  fun `a label never frees its lane before it has cleared the edge`() {
    // The label travels screen + text width; it clears the entry point after its own width.
    // 200px of a 1200px travel at 8000ms is ~1333ms.
    val free = laneFreeAfterMs(textWidthPx = 200f, screenWidthPx = 1_000f, flightMillis = 8_000)

    assertTrue("expected roughly 1333ms, got $free", free in 1_400..1_450)
  }

  @Test
  fun `a zero width screen falls back to the whole flight`() {
    assertEquals(8_000L, laneFreeAfterMs(textWidthPx = 100f, screenWidthPx = 0f, flightMillis = 8_000))
    assertEquals(8_000L, laneFreeAfterMs(textWidthPx = 0f, screenWidthPx = 1_000f, flightMillis = 8_000))
  }
}
