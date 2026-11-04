package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * TEMPORARY bisection: which piece of the 关联设备 dialog stops Compose idling at 640x360?
 *
 * Round 36 established the shape of the problem: the loop needs a dialog *and* a wide, short
 * window (600x800 + dialog, 320x360 + dialog and 640x360 without a dialog all idle), and
 * neither replacing `AlertDialog` with a hand-written `Dialog` nor capping the dialog's height
 * fixed it. This test opens trivial dialogs of increasing complexity at the failing size, one
 * component at a time, so the culprit is measured instead of guessed.
 *
 * A probe that hangs fails with `AppNotIdleException` after 60s, which is itself the answer
 * for that row of the matrix.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land")
class SyncDialogBisectTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun probe1_plainDialogWithText() {
    composeRule.setContent {
      MaterialTheme {
        Dialog(onDismissRequest = {}) { Text("hello") }
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("hello").assertExists()
  }

  @Test
  fun probe2_plainDialogWithTextAndButton() {
    composeRule.setContent {
      MaterialTheme {
        Dialog(onDismissRequest = {}) {
          Column {
            Text("hello")
            TextButton(onClick = {}) { Text("取消") }
          }
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("取消").assertExists()
  }

  @Test
  fun probe3_plainDialogWithTextFieldFillWidth() {
    composeRule.setContent {
      MaterialTheme {
        Dialog(onDismissRequest = {}) {
          Column {
            Text("hello")
            OutlinedTextField(value = "", onValueChange = {}, modifier = Modifier.fillMaxWidth())
          }
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("hello").assertExists()
  }

  @Test
  fun probe4_plainDialogWithTextFieldNoFill() {
    composeRule.setContent {
      MaterialTheme {
        Dialog(onDismissRequest = {}) {
          Column {
            Text("hello")
            OutlinedTextField(value = "", onValueChange = {})
          }
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("hello").assertExists()
  }

  @Test
  fun probe5_plainDialogWithWeightedScrollColumn() {
    composeRule.setContent {
      MaterialTheme {
        Dialog(onDismissRequest = {}) {
          // A fixed outer height: `weight` needs a bounded parent, and a dialog's own height is
          // wrap-content.
          Column(modifier = Modifier.height(200.dp)) {
            Text("hello")
            Column(
              modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState()),
            ) {
              Text("body")
              Column(modifier = Modifier.height(400.dp)) {}
            }
          }
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("body").assertExists()
  }

  @Test
  fun probe6_alertDialogWithText() {
    composeRule.setContent {
      MaterialTheme {
        AlertDialog(
          onDismissRequest = {},
          title = { Text("hello") },
          text = { Text("body") },
          confirmButton = { TextButton(onClick = {}) { Text("关联") } },
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("body").assertExists()
  }
}
