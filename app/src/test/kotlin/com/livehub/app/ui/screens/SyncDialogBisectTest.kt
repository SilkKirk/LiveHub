package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
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
 * TEMPORARY: which width constraint actually stops the loop?
 *
 * Established so far, all at 640x360dp: the same `OutlinedTextField` idles outside a dialog,
 * loops inside one, and idles inside one when pinned to `width(280.dp)`. An exact width is not
 * a product answer (the dialog is 280dp wide on a small phone and 420dp on a tablet), so this
 * probe compares the three constraints a real layout would use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land")
class SyncDialogBisectTest {

  @get:Rule
  val composeRule = createComposeRule()

  /** D: bound the field itself with `widthIn`, no fixed width. */
  @Test
  fun probeD_fieldWithWidthInMax() {
    composeRule.setContent {
      MaterialTheme {
        Dialog(onDismissRequest = {}) {
          Column {
            Text("hello")
            OutlinedTextField(
              value = "",
              onValueChange = {},
              modifier = Modifier.widthIn(max = 280.dp),
            )
          }
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("hello").assertExists()
  }

  /** E: fixed-width column, field fills it — the shape a real dialog has. */
  @Test
  fun probeE_fixedWidthColumnWithFillingField() {
    composeRule.setContent {
      MaterialTheme {
        Dialog(onDismissRequest = {}) {
          Column(modifier = Modifier.width(360.dp)) {
            Text("hello")
            OutlinedTextField(value = "", onValueChange = {}, modifier = Modifier.fillMaxWidth())
          }
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("hello").assertExists()
  }

  /** F: `widthIn`-bounded column, field fills it — the constraint the app would actually use. */
  @Test
  fun probeF_widthInColumnWithFillingField() {
    composeRule.setContent {
      MaterialTheme {
        Dialog(onDismissRequest = {}) {
          Column(modifier = Modifier.widthIn(max = 420.dp)) {
            Text("hello")
            OutlinedTextField(value = "", onValueChange = {}, modifier = Modifier.fillMaxWidth())
          }
        }
      }
    }
    composeRule.waitForIdle()
    composeRule.onNodeWithText("hello").assertExists()
  }
}
