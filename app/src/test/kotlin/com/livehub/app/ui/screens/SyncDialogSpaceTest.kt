package com.livehub.app.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.core.sync.TrustedPeer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Measures the 关联设备 dialog instead of guessing at it.
 *
 * The pending question (round 21) is whether the dialog's body overflows the window on a short
 * screen: it is a plain `Column` in the `text` slot, and the note that has been carried since
 * then says "被窗口高度截断且自己不滚". Attempting to fix it by adding `verticalScroll` made
 * Robolectric spin for 60 seconds without idling, so the change was reverted and the question
 * left open.
 *
 * This test answers the question with numbers, on the smallest screen the app targets
 * (320×470dp, the qualifier below) and without touching production code:
 *
 *  * the dialog window is never taller than the screen;
 *  * the last row of its body sits inside the dialog window.
 *
 * If either fails, the truncation is real and has a measured shape to fix. If both pass, the
 * report above was a guess and the item can be closed as "not a defect".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h470dp")
class SyncDialogSpaceTest {

  @get:Rule
  val composeRule = createComposeRule()

  /**
   * `pairingCodeSecondsLeft = 0` is load-bearing.
   *
   * `SyncScreen` derives the countdown in a `LaunchedEffect` that loops `delay(1_000)` while
   * there is time left, so a non-zero value keeps the composition busy forever and
   * `waitForIdle` never returns — which is the most likely explanation for the round-21 hang.
   */
  private fun state() = UiState(
    screen = Screen.Sync,
    deviceName = "Pixel 7",
    localAddress = "192.168.1.23:47653",
    pairingCode = "482913",
    pairingCodeSecondsLeft = 0,
    peers = listOf(
      TrustedPeer(2, "小米平板 6", "secret", 1_700_000_000_000, lastSeenAt = 1_700_000_100_000),
    ),
  )

  private fun openDialog() {
    composeRule.setContent {
      MaterialTheme { SyncScreen(state = state(), onAction = {}) }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("关联设备").performClick()
    composeRule.waitForIdle()
  }

  @Test
  fun `the dialog never grows taller than the screen`() {
    openDialog()

    val dialog = composeRule.onNode(isDialog()).fetchSemanticsNode()
    val heightDp = with(composeRule.density) { dialog.boundsInRoot.height.toDp().value }

    // The qualifier is what defines "the screen" here; a dialog is allowed to use all of it,
    // not more (a window taller than the display is the shape a "cut off" report takes).
    assertTrue(
      "the dialog is ${heightDp}dp tall, the screen only has 470dp",
      heightDp <= 470f,
    )
  }

  @Test
  fun `the last row of the dialog body is inside the dialog`() {
    openDialog()

    val dialog = composeRule.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
    val lastRow = composeRule.onNodeWithText("重新生成本机匹配码").fetchSemanticsNode().boundsInRoot

    assertTrue(
      "the last row ends at ${lastRow.bottom} but the dialog stops at ${dialog.bottom}",
      lastRow.bottom <= dialog.bottom,
    )
    assertTrue(
      "the last row starts at ${lastRow.top}, above the dialog top ${dialog.top}",
      lastRow.top >= dialog.top,
    )
  }

  // There is no landscape case here on purpose. At 640x360 the dialog never settles: Robolectric
  // reports `AppNotIdleException: Compose did not get idle after 107106 attempts in 60 SECONDS`.
  // Narrowing the variables showed it needs a dialog *and* a wide, short window (600x800 with
  // the dialog, 320x360 with the dialog, and 640x360 without it all idle), and rebuilding the
  // dialog as a hand-written `Dialog` with an explicitly capped height did not remove the loop,
  // so that rewrite was reverted rather than kept on a disproven theory. Whether a real device
  // freezes at this size is **not** known: the emulator that runs the instrumented suite is
  // portrait only. Recorded in docs/ITERATIONS.md 第三十六轮 and in the runbook.
}
