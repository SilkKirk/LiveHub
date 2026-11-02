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
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackSwipeContainerTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val closes = AtomicInteger(0)

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

  /** Drags from [startX] to [endX] in [steps] frames, the way a finger actually moves. */
  private fun drag(startX: Float, endX: Float, steps: Int) {
    composeRule.onNodeWithText("播放器内容").performTouchInput {
      down(Offset(startX, 300f))
      val step = (endX - startX) / steps
      repeat(steps) { index ->
        // Small increments per frame, which is exactly what the old code compared
        // against the whole 180px threshold.
        moveTo(Offset(startX + step * (index + 1), 300f))
      }
      up()
    }
    composeRule.waitForIdle()
  }

  @Test
  fun `a swipe from the left edge closes the player`() {
    composeContainer()

    drag(startX = 5f, endX = 300f, steps = 10)

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

    drag(startX = 5f, endX = 380f, steps = 20)

    assertEquals("one swipe must dispatch one close", 1, closes.get())
  }

  @Test
  fun `a short swipe does not close the player`() {
    composeContainer()

    drag(startX = 5f, endX = 60f, steps = 6)

    assertEquals("a tap-length drag must not close the player", 0, closes.get())
  }

  /**
   * A drag starting in the middle of the picture is a pan, not a back gesture.
   *
   * Otherwise scrubbing across the video would throw the user out of the room.
   */
  @Test
  fun `a swipe that starts away from the edge does not close the player`() {
    composeContainer()

    drag(startX = 200f, endX = 390f, steps = 10)

    assertEquals("a mid-screen drag must not close the player", 0, closes.get())
  }
}
