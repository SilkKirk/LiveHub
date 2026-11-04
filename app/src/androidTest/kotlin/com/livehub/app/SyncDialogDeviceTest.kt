package com.livehub.app

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.app.ui.screens.SyncScreen
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The 关联设备 dialog on a real device.
 *
 * It had no test of any kind until round 36 — which is how the landscape layout loop (see
 * `SyncDialogSpaceTest` and the round-36 notes) survived: opening it was only ever done by
 * hand. Robolectric can measure it at portrait sizes but not on a device, and the emulator
 * only runs portrait, so this test covers what the JVM cannot: that the dialog really opens,
 * takes six digits and only then enables 「关联」.
 */
@RunWith(AndroidJUnit4::class)
class SyncDialogDeviceTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun thePairingDialogOpensAndGatesTheConfirmButton() {
    composeRule.setContent {
      MaterialTheme {
        SyncScreen(
          state = UiState(
            screen = Screen.Sync,
            deviceName = "Pixel 7",
            pairingCode = "482913",
            // A live countdown keeps a `delay` loop in the composition; zero is the settled
            // state and all this test needs.
            pairingCodeSecondsLeft = 0,
          ),
          onAction = {},
        )
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithText("关联设备").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithText("六位匹配码").assertIsDisplayed()
    composeRule.onNodeWithText("关联").assertIsNotEnabled()

    composeRule.onNode(hasSetTextAction()).performTextInput("482913")
    composeRule.waitForIdle()

    composeRule.onNodeWithText("关联").assertIsEnabled()
  }
}
