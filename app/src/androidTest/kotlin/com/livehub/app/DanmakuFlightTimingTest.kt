package com.livehub.app

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livehub.app.ui.player.DanmakuOverlay
import com.livehub.core.model.DanmakuMessage
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * How long a label stays on screen, with the width measured for real.
 *
 * The JVM cannot run this: Robolectric's legacy graphics mode has no font backend, so a
 * label there has no measurable width and a three-character danmaku is indistinguishable
 * from a thirty-character one — the JVM tests can only pin the arithmetic once a width is
 * given. On a device the measurer works, which makes this the only test that exercises the
 * whole path: measured width, chosen duration, animation, removal.
 *
 * It fails on the code this replaces. Every label used to run the same 8 s, so by 6.5 s the
 * short one was still on screen and by 9 s the long one was already gone.
 */
@RunWith(AndroidJUnit4::class)
class DanmakuFlightTimingTest {

  @get:Rule
  val composeRule = createComposeRule()

  /**
   * Long enough that the label is wider than the screen on any phone profile, so its flight
   * is comfortably past the old 8 s, and past the 15 s cap only on a narrow one — where the
   * cap is the very thing being bounded, not the thing being measured.
   */
  private val longText = "这是一条很长很长的弹幕用来验证长弹幕不会被八秒钟草草拖过去，应该按速度算而不是按时长算，短弹幕也要跑得利索一点"

  private fun message(text: String) = DanmakuMessage(
    roomKey = "douyu:1",
    userName = "观众",
    text = text,
  )

  @Test
  fun `a long danmaku outlives the old fixed eight seconds`() {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      DanmakuOverlay(
        messages = listOf(message("短"), message(longText)),
        enabled = true,
        blockedKeywords = emptyList(),
      )
    }

    // 6.5 s: the one-character label is long gone, the long one is still crossing.
    composeRule.mainClock.advanceTimeBy(6_500)
    composeRule.waitForIdle()
    composeRule.onAllNodesWithText("短").assertCountEquals(0)
    composeRule.onNodeWithText(longText).assertExists()

    // 9 s: past the fixed duration the old overlay gave everything, and the long label is
    // still there, because it is wider than the screen and therefore slower than the baseline.
    composeRule.mainClock.advanceTimeBy(2_500)
    composeRule.waitForIdle()
    composeRule.onNodeWithText(longText).assertExists()
  }
}
