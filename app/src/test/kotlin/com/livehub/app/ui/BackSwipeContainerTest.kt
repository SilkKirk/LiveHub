package com.livehub.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.atomic.AtomicInteger

/**
 * The left-edge swipe is the only way to leave the player in portrait now.
 *
 * It used to be declared, documented and dead. `detectHorizontalDragGestures` hands the
 * lambda the movement **since the previous frame**, and the code compared that against a
 * 180px threshold — a delta no human finger produces. So the gesture never fired, and once
 * the header's 返回/关闭 buttons were removed there would have been no way back at all
 * except the system gesture.
 *
 * A test that merely composes the screen cannot see this, because there is nothing visibly
 * wrong. These tests drag and count the callbacks.
 *
 * Coordinates are given in dp and converted with the container's own density: the
 * threshold is 60dp, and writing raw pixels here is what made the first version of this
 * test assert distances that happened to fall on the wrong side of it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackSwipeContainerTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val closes = AtomicInteger(0)

  /** 400x600dp: comfortably wider than the 8% edge zone and the 60dp threshold. */
  private fun composeContainer() {
    composeRule.setContent {
      Box(modifier = Modifier.size(400.dp, 600.dp)) {
        BackSwipeContainer(onClose = { closes.incrementAndGet() }) {
          Text("播放器内容")
        }
      }
    }
    composeRule.waitForIdle()
  }

  /** Converts dp to the pixels this composition actually uses. */
  private fun px(dp: Int): Float = with(composeRule.density) { dp.dp.toPx() }

  /** Drags from [startDp] to [endDp] in [steps] frames, the way a finger actually moves. */
  private fun drag(startDp: Int, endDp: Int, steps: Int) {
    val startX = px(startDp)
    val endX = px(endDp)
    val y = px(300)
    composeRule.onNodeWithText("播放器内容").performTouchInput {
      down(Offset(startX, y))
      val step = (endX - startX) / steps
      repeat(steps) { index ->
        // Small increments per frame, which is exactly what the old code compared
        // against the whole threshold.
        moveTo(Offset(startX + step * (index + 1), y))
      }
      up()
    }
    composeRule.waitForIdle()
  }

  @Test
  fun `a swipe from the left edge closes the player`() {
    composeContainer()

    drag(startDp = 2, endDp = 300, steps = 10)

    assertEquals("a left-edge swipe must close the player", 1, closes.get())
  }

  /**
   * The gesture must fire exactly once.
   *
   * The old implementation called `ClosePlayer` on every frame past the threshold, so a
   * single swipe dispatched a burst of them.
   */
  @Test
  fun `a swipe closes exactly once, not once per frame`() {
    composeContainer()

    drag(startDp = 2, endDp = 390, steps = 20)

    assertEquals("one swipe must dispatch one close", 1, closes.get())
  }

  @Test
  fun `a short swipe does not close the player`() {
    composeContainer()

    // 30dp of travel, well under the 60dp threshold.
    drag(startDp = 2, endDp = 32, steps = 6)

    assertEquals("a short drag must not close the player", 0, closes.get())
  }

  /**
   * A drag starting in the middle of the picture is a pan, not a back gesture.
   *
   * Otherwise scrubbing across the video would throw the user out of the room.
   */
  @Test
  fun `a swipe that starts away from the edge does not close the player`() {
    composeContainer()

    drag(startDp = 200, endDp = 390, steps = 10)

    assertEquals("a mid-screen drag must not close the player", 0, closes.get())
  }
}
