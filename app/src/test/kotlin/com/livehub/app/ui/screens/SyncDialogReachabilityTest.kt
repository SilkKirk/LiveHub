package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNode
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.core.sync.TrustedPeer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 关联设备 dialog has to stay usable when the window is short.
 *
 * `AlertDialog` caps its text slot at whatever height is left after the title and the
 * buttons, and that slot does not scroll: content past the cap is measured, clipped, and
 * then impossible to reach. The dialog has five rows — the explanation, the six digit field,
 * this device's own code, 重新生成本机匹配码 and the searching indicator — which is more than
 * a 640x360 landscape window leaves once the dialog margins, the title and the buttons are
 * taken out. A large system font scale does the same thing in portrait.
 *
 * The failure is silent in every test that only checks the rows exist: a clipped row is
 * still in the semantics tree. `performScrollTo` is the assertion that separates the two —
 * it throws when the node has no scrollable ancestor at all, which is exactly the state the
 * dialog was in.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land")
class SyncDialogReachabilityTest {

  @get:Rule
  val composeRule = createComposeRule()

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
      // 360dp of height: what a 640x360 landscape phone leaves the dialog window.
      Box(modifier = Modifier.size(640.dp, 360.dp)) {
        SyncScreen(state = state(), onAction = {})
      }
    }
    composeRule.waitForIdle()

    // The button sits below the 本机 card, so scroll to it first: `performClick` injects the
    // touch at the node's centre and refuses a node that is outside the viewport.
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("关联设备"))
    composeRule.onNodeWithText("关联设备").performClick()
    composeRule.waitForIdle()
  }

  @Test
  fun `the regenerate code button is reachable in the landscape dialog`() {
    openDialog()

    composeRule.onNodeWithText("重新生成本机匹配码").performScrollTo().assertIsDisplayed()
  }

  /**
   * The field the whole dialog exists for, checked with the same scroll.
   *
   * The top rows survive the cap on their own, so this one is a control: if a later change
   * makes the dialog unscrollable again, the assertion above fails while this one keeps
   * passing, and that difference says the problem is at the bottom of the dialog rather than
   * in the dialog not opening at all.
   */
  @Test
  fun `the six digit field is reachable in the landscape dialog`() {
    openDialog()

    composeRule.onNodeWithText("六位匹配码").performScrollTo().assertIsDisplayed()
  }
}
