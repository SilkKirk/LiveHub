package com.livehub.app.ui.screens

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.core.sync.TrustedPeer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TEMPORARY probe: which window dimension makes the 关联设备 dialog stop idling?
 *
 * Round 21 recorded "the dialog body is cut off and does not scroll" and a later attempt to add
 * `verticalScroll` hung for 60s. Round 36 measured it: 320x470 settles, 640x360 does not
 * (`AppNotIdleException: Compose did not get idle after 125826 attempts`). Wide-and-tall versus
 * narrow-and-short separates "width triggers it" from "height triggers it"; the third case
 * opens no dialog at all and says whether the screen itself is innocent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h470dp")
class SyncDialogProbeTest {

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

  private fun showScreen() {
    composeRule.setContent {
      MaterialTheme { SyncScreen(state = state(), onAction = {}) }
    }
    composeRule.waitForIdle()
  }

  private fun openDialog() {
    showScreen()
    composeRule.onNodeWithText("关联设备").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("六位匹配码").assertExists()
  }

  /** Wide and tall: 600x800, the size a tablet in portrait uses. */
  @Test
  @Config(qualifiers = "w600dp-h800dp")
  fun `probe wide and tall`() {
    openDialog()
  }

  /** Narrow and short: same height as the failing landscape case, same width as the passing one. */
  @Test
  @Config(qualifiers = "w320dp-h360dp")
  fun `probe narrow and short`() {
    openDialog()
  }

  /** The screen alone at the failing size: if this idles, the dialog is the culprit. */
  @Test
  @Config(qualifiers = "w640dp-h360dp")
  fun `probe screen only in landscape`() {
    showScreen()
    composeRule.onNodeWithText("立即同步").assertExists()
  }
}
