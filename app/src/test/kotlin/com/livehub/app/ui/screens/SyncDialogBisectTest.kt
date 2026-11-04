package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
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
 * TEMPORARY follow-up: is the `OutlinedTextField` loop about the dialog at all?
 *
 * The first bisection (same window, dialogs of increasing complexity) narrowed it to exactly
 * one component: `Dialog` + `Text`, `+ TextButton`, the weighted scroll column and a minimal
 * `AlertDialog` all idle; adding an `OutlinedTextField` — with or without `fillMaxWidth` —
 * fails with `AppNotIdleException` after ~198k attempts.
 *
 * This file asks the two questions that decide what to do about it:
 *
 *  1. does the same field loop *outside* a dialog at this size? If yes, the dialog is
 *     irrelevant and the trigger is the width/height/paint combination, i.e. a Robolectric
 *     text-measurement artifact rather than a layout bug a device would hit;
 *  2. does pinning the field to a fixed width stop it? If yes, the fix is a one-line constraint.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land")
class SyncDialogBisectTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun probeA_textFieldOutsideAnyDialog() {
    composeRule.setContent {
      MaterialTheme {
        Column(modifier = Modifier.fillMaxWidth()) {
          Text("hello")
          OutlinedTextField(value = "", onValueChange = {})
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("hello").assertExists()
  }

  @Test
  fun probeB_textFieldInDialogWithFixedWidth() {
    composeRule.setContent {
      MaterialTheme {
        Dialog(onDismissRequest = {}) {
          Column {
            Text("hello")
            OutlinedTextField(value = "", onValueChange = {}, modifier = Modifier.width(280.dp))
          }
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("hello").assertExists()
  }

  @Test
  fun probeC_textFieldInDialogAgain() {
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
}
