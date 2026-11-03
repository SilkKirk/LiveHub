package com.livehub.app.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
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
   * Each bubble runs an `Animatable` for the label's own flight time and `onFinished` removes
   * it. With `autoAdvance` left at its default, `waitForIdle()` runs that animation to
   * completion, so every message has already flown off and been dropped by the time the
   * assertions run. The flight is the baseline ~5.5 s here: the JVM text measurer has no font
   * backend, so every label measures zero and is clamped to one pixel.
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
   * inside the baseline flight, so every bubble exists and is still mid-screen.
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

  /**
   * A line flies for its own duration, not for one shared constant.
   *
   * Every label used to run an 8 s animation whatever its width, which is the bug this
   * replaces. Driving [DanmakuLine] directly is the only way to see it: the overlay derives
   * the duration from the measured label width, and on the JVM the measurer has no font
   * backend, so two messages of different lengths both come out one pixel wide and an
   * overlay-level test could not tell a 3 s flight from an 8 s one.
   */
  @Test
  fun `a line flies for its own duration`() {
    freezeClock()
    var finished = 0
    composeRule.setContent {
      DanmakuLine(
        item = FlyingDanmaku(
          id = 1L,
          text = "按自己的时长飞",
          lane = 0,
          color = Color.White,
          textWidthPx = 400f,
          flightMillis = 3_000L,
        ),
        screenWidthPx = 1_000f,
        laneHeightPx = 40f,
        opacity = 1f,
        style = TextStyle(),
        onFinished = { finished++ },
      )
    }
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.waitForIdle()

    composeRule.mainClock.advanceTimeBy(2_000)
    composeRule.waitForIdle()
    assertEquals("a 3 s flight is still on screen at 2 s", 0, finished)

    composeRule.mainClock.advanceTimeBy(2_500)
    composeRule.waitForIdle()
    assertEquals("a 3 s flight must be over by 4.5 s", 1, finished)
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

/**
 * How long each label stays on screen.
 *
 * The overlay used to give every label the same 8 s, which made the scroll speed a function
 * of how long the message happened to be: a three-character danmaku moved at 0.14 px/ms and
 * a thirty-character one at 0.30 px/ms, so short labels crawled and long ones raced. These
 * pin the constant-speed rule that replaced it.
 */
class DanmakuFlightSpeedTest {

  private val screen = 1_000f

  /** Distance covered per millisecond, which must not depend on the label. */
  private fun speed(textWidthPx: Float): Float {
    val travel = screen + textWidthPx
    return travel / flightDurationMs(textWidthPx, screen)
  }

  @Test
  fun `every label crosses at the same speed`() {
    val short = speed(100f).toDouble()
    val typical = speed(450f).toDouble()
    val wide = speed(1_200f).toDouble()

    // 1% is generous: the durations are whole milliseconds, so a few pixels of rounding is
    // expected. A fixed duration would put these a factor of three apart, not a percent.
    assertEquals("short and typical disagree", short, typical, short * 0.01)
    assertEquals("typical and wide disagree", typical, wide, wide * 0.01)
  }

  @Test
  fun `a label narrower than the screen no longer crawls`() {
    // 100 px of a 1000 px screen used to take the full 8 s; at constant speed it takes 6 s.
    val duration = flightDurationMs(textWidthPx = 100f, screenWidthPx = screen)

    assertTrue("expected about 6050 ms, got $duration", duration in 5_900L..6_200L)
    assertTrue("a short label must not still take the old 8 s", duration < 8_000)
  }

  @Test
  fun `a label wider than the screen is no longer raced across`() {
    // 1200 px of label used to cross in the same 8 s as everything else.
    val duration = flightDurationMs(textWidthPx = 1_200f, screenWidthPx = screen)

    assertTrue("expected about 12100 ms, got $duration", duration in 11_900L..12_300L)
    assertTrue("a long label must get more time than the old 8 s", duration > 8_000)
  }

  @Test
  fun `a typical label keeps the time the old fixed duration gave it`() {
    // Calibration guard: ten characters at the default size are about 0.45 screen widths,
    // and the whole point of the constants is that this case does not visibly change.
    val duration = flightDurationMs(textWidthPx = 450f, screenWidthPx = screen)

    assertTrue("expected about 7975 ms, got $duration", duration in 7_800L..8_150L)
  }

  @Test
  fun `the speed does not depend on the device width`() {
    // A label that is 0.4 screen widths wide must take the same time on any screen: the
    // speed is expressed relative to the width for exactly that reason.
    val phone = flightDurationMs(textWidthPx = 400f, screenWidthPx = 1_000f)
    val tablet = flightDurationMs(textWidthPx = 1_600f, screenWidthPx = 4_000f)

    assertTrue("phone $phone vs tablet $tablet", kotlin.math.abs(phone - tablet) <= 1)
  }

  @Test
  fun `an essay is capped so it cannot hold a lane forever`() {
    val duration = flightDurationMs(textWidthPx = 100_000f, screenWidthPx = screen)

    assertEquals(15_000L, duration)
  }

  @Test
  fun `a zero measured width still produces a usable duration`() {
    // The measurer can return nothing on the very first frame; that must not divide by zero
    // or produce a zero-length flight that flickers.
    val duration = flightDurationMs(textWidthPx = 0f, screenWidthPx = 0f)

    assertTrue("expected a sane fallback, got $duration", duration in 5_000L..15_000L)
  }

  @Test
  fun `a lane is freed when its own label clears the edge, not when the flight ends`() {
    // Constant speed means the hand-off is simply the label's own width worth of time plus
    // the gap: at 1000 px per 5500 ms that is 5.5 ms per pixel, whatever the label's length.
    listOf(100f, 450f, 900f).forEach { width ->
      val free = laneFreeAfterMs(width, screen, flightDurationMs(width, screen))
      val expected = (width * 5.5f).toLong() + 80L

      assertTrue("width $width: expected $expected, got $free", free in (expected - 2)..(expected + 2))
    }
  }

  @Test
  fun `the hand-off uses the label's own flight time`() {
    // Passing one shared duration — the old 8 s — frees a wide label's lane roughly 1.6 s
    // early, so the next danmaku enters while the previous one is still on top of it.
    val width = 900f
    val ownFlight = laneFreeAfterMs(width, screen, flightDurationMs(width, screen))
    val fixedFlight = laneFreeAfterMs(width, screen, 8_000L)

    assertTrue(
      "a shared 8 s duration must free the lane earlier than the label really clears it " +
        "($fixedFlight vs $ownFlight)",
      fixedFlight < ownFlight - 500,
    )
  }
}
