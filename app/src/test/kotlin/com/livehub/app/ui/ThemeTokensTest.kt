package com.livehub.app.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.livehub.core.designsystem.LiveHubTheme
import com.livehub.core.designsystem.ThemeMode
import com.livehub.core.designsystem.supportsDynamicColor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What `MaterialTheme` actually supplies, rather than what the theme file appears to set.
 *
 * Three systems live in the theme — colour, type and shape. Colour and type had tests; shape and
 * Material You had none, because both were unreachable: `LiveHubShapes` did not exist and
 * `dynamicColor` was a setting no theme read. These assertions are the reachability half of the
 * fix: a shape scale that is actually the theme's, and a wallpaper scheme that is actually
 * different from the brand one when it is asked for.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class ThemeTokensTest {

  @get:Rule
  val composeRule = createComposeRule()

  /**
   * The shape scale is read off the theme, not off `LiveHubShapes`.
   *
   * `medium` has to stay 12dp — that is what every card in the app already draws (`Card` reads
   * `CardDefaults.shape` → `shapes.medium`), so pulling it from the theme must not move a single
   * card. `large` is the assertion that proves the theme is ours rather than Material's: the M3
   * default for it is 16dp and the app's panels are 20dp, so a `LiveHubTheme` that silently fell
   * back to the default scale fails here.
   */
  @Test
  fun `the theme supplies the brand shape scale instead of Material's default`() {
    var medium: Shape? = null
    var large: Shape? = null
    var extraLarge: Shape? = null
    composeRule.setContent {
      LiveHubTheme {
        medium = MaterialTheme.shapes.medium
        large = MaterialTheme.shapes.large
        extraLarge = MaterialTheme.shapes.extraLarge
      }
    }
    composeRule.waitForIdle()

    assertEquals("cards keep their 12dp corner", RoundedCornerShape(12.dp), medium)
    assertEquals("panels are 20dp, not Material's 16dp default", RoundedCornerShape(20.dp), large)
    assertEquals("the dialog corner stays 28dp", RoundedCornerShape(28.dp), extraLarge)
  }

  /** Android 12 introduced the system accent resources the dynamic schemes read. */
  @Test
  fun `material you is only offered from android 12`() {
    assertFalse("API 26 has no dynamic colour resources", supportsDynamicColor(26))
    assertFalse("API 30 has no dynamic colour resources", supportsDynamicColor(30))
    assertTrue("API 31 is where they appear", supportsDynamicColor(31))
    assertTrue("a current phone supports it", supportsDynamicColor(34))
  }

  /**
   * Turning the switch on has to change the scheme the components read.
   *
   * The comparison is between two sibling themes composed from the same state, so a wiring bug
   * that ignores the parameter (the old field's failure mode) shows up as "both palettes are the
   * brand pink" rather than as a passing render.
   */
  @Test
  fun `the wallpaper palette replaces the brand palette when it is asked for`() {
    var brandPrimary: Color? = null
    var dynamicPrimary: Color? = null
    composeRule.setContent {
      LiveHubTheme(mode = ThemeMode.Light, dynamicColor = false) {
        brandPrimary = MaterialTheme.colorScheme.primary
      }
      LiveHubTheme(mode = ThemeMode.Light, dynamicColor = true) {
        dynamicPrimary = MaterialTheme.colorScheme.primary
      }
    }
    composeRule.waitForIdle()

    assertNotNull("the brand palette has to compose", brandPrimary)
    assertNotNull("the dynamic palette has to compose", dynamicPrimary)
    assertNotEquals(
      "the wallpaper palette is not the brand pink",
      brandPrimary,
      dynamicPrimary,
    )
  }
}
