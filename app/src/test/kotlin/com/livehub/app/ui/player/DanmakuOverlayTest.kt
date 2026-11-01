package com.livehub.app.ui.player

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import com.livehub.core.model.DanmakuMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Overlay behaviour that a plain unit test cannot see.
 *
 * The bug these cover was invisible in both the screenshot harness (the animation clock is
 * frozen, so nothing has moved yet) and the JVM tests (no composition at all).
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
   * Each bubble runs an 8 s `Animatable`, and `onFinished` removes it from the overlay. With
   * `autoAdvance` left on, `waitForIdle()` runs the animation to completion, every message
   * is dropped, and the assertions see an empty overlay rather than the real state.
   */
  private fun freezeClock() {
    composeRule.mainClock.autoAdvance = false
  }

  /**
   * A new message must not re-add the messages already on screen.
   *
   * `messages` is the ViewModel's whole rolling buffer, so appending one entry changed the
   * list identity and re-ran the effect over *every* entry. With three messages that meant
   * "a" was animated three times, "b" twice and "c" once — the overlay filled with
   * duplicates within seconds of opening a busy room.
   *
   * Compose does not compose two nodes with identical text unless they are genuinely two
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
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.waitForIdle()

    // One more arrives.
    messages = first + message("c")
    composeRule.waitForIdle()
    // The frozen clock means the new bubble has been added to the list but not yet laid
    // out or drawn; one frame is what turns it into a node.
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.waitForIdle()

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
    composeRule.waitForIdle()

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
    composeRule.waitForIdle()
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.waitForIdle()

    composeRule.onAllNodesWithText("买广告").assertCountEquals(0)
    composeRule.onNodeWithText("正常弹幕").assertExists()
  }
}
