package com.livehub.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.designsystem.LiveHubTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 动态取色 row on the 外观 card.
 *
 * The setting existed before, as a field the theme never received: a switch that described itself
 * and changed nothing. The assertions here are about the contract that fixed it — the row is on
 * the first card, the switch edge is the same constant the screen uses, a tap is reported as the
 * opposite of the stored value, and the row says what it does (the wallpaper palette).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class SettingsDynamicColorTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun render(state: UiState, actions: MutableList<UiAction> = mutableListOf()) {
    composeRule.setContent {
      LiveHubTheme {
        SettingsScreen(state = state, onAction = { actions += it })
      }
    }
    composeRule.waitForIdle()
  }

  @Test
  fun `the appearance card offers material you and reports a tap`() {
    val actions = mutableListOf<UiAction>()
    render(UiState(screen = Screen.Settings), actions)

    composeRule.onNodeWithText("动态取色").assertIsDisplayed()
    composeRule.onNodeWithTag(DYNAMIC_COLOR_SWITCH_TAG)
      .assertIsDisplayed()
      .assertIsEnabled()
      .assertIsOff()
      .performClick()
    composeRule.waitForIdle()

    assertEquals(
      "tapping the switch asks for the wallpaper palette",
      listOf<UiAction>(UiAction.SetDynamicColor(true)),
      actions,
    )
  }

  @Test
  fun `the switch shows the stored choice`() {
    render(UiState(screen = Screen.Settings, dynamicColor = true))

    composeRule.onNodeWithTag(DYNAMIC_COLOR_SWITCH_TAG).assertIsOn()
  }

  /** The explanation is not optional: without it the row is a switch with no stated consequence. */
  @Test
  fun `the row says the palette follows the wallpaper`() {
    render(UiState(screen = Screen.Settings))

    composeRule.onNodeWithText("跟随系统壁纸取色（Material You）。关掉则使用品牌配色。")
      .assertIsDisplayed()
  }
}
