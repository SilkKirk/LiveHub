package com.livehub.app.ui.screens

import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.core.designsystem.LiveHubTheme
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
 *
 * Composed inside [LiveHubTheme] because the height this file measures *is* a type-scale fact: the
 * dialog's note is `bodySmall` (13sp/18sp in the app, 12sp/16sp under Material's defaults) inside a
 * `Column` that does not scroll, so the two scales disagree about precisely the dimension the
 * assertions below put a cap on.
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
      LiveHubTheme { SyncScreen(state = state(), onAction = {}) }
    }
    composeRule.waitForIdle()
    // Scrolled to first, because the action moved below the 本机 card when the primary button became
    // full width (round 96). In the 640×360 landscape window that card alone fills the viewport: the
    // button is composed but clipped, and a click at its centre lands outside the window, so nothing
    // opened and the failure showed up as "no dialog" rather than as "clicked the wrong place".
    // What this file measures is the dialog's geometry, not where the button sits.
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("关联设备"))
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

  /**
   * The shortest window the app renders in: a 640×360 landscape phone.
   *
   * This is the case that exposed the defect. Bisecting the dialog one component at a time
   * showed the trigger is a `fillMaxWidth()` text field inside the dialog: `Dialog` + text,
   * + button, a weighted scroll column, a minimal `AlertDialog`, and even the same field
   * *outside* a dialog all idle at this size; adding the filling field stops the composition
   * from settling (`AppNotIdleException`, ~198k attempts in 60s). The dialog now bounds the
   * field instead of filling, and this test is the regression guard.
   */
  @Test
  @Config(qualifiers = "w640dp-h360dp-land")
  fun `the dialog also fits a landscape phone`() {
    openDialog()

    val dialog = composeRule.onNode(isDialog()).fetchSemanticsNode().boundsInRoot
    val heightDp = with(composeRule.density) { dialog.height.toDp().value }
    val lastRow = composeRule.onNodeWithText("重新生成本机匹配码").fetchSemanticsNode().boundsInRoot

    assertTrue("the dialog is ${heightDp}dp tall, the screen only has 360dp", heightDp <= 360f)
    assertTrue(
      "the last row ends at ${lastRow.bottom} but the dialog stops at ${dialog.bottom}",
      lastRow.bottom <= dialog.bottom,
    )
  }

  // The finding above is recorded in docs/ITERATIONS.md 第三十七轮: the loop needs a dialog and
  // a wide, short window, and it is a `fillMaxWidth()` text field inside the dialog that causes
  // it. A real device was never observed freezing — the instrumented suite runs portrait only —
  // so the fix is justified by the bounded-vs-filled comparison, not by device evidence.
}
