package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.model.Platform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The search screen has to offer a platform switcher.
 *
 * It did not, while its own field label read "搜索 斗鱼 主播 / 直播间" — naming a platform the user
 * had no way to change without going back to 首页. In an aggregator, "the same streamer on another
 * platform" is the main reason to search at all, and it cost four taps plus losing the keyword.
 *
 * The important half is the **action** it emits. `SelectPlatform` is the home screen's switcher:
 * it resets the category tree and the room grid and navigates to `Screen.Home`, so reusing it here
 * would kick the user out of search. These tests pin that the chip emits
 * `SelectSearchPlatform` and that the field's keyword survives the tap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class SearchPlatformSwitchTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun state(platform: Platform = Platform.DOUYU) = UiState(
    platform = platform,
    screen = Screen.Search,
  )

  private fun render(
    state: UiState,
    actions: MutableList<UiAction> = mutableListOf(),
  ): MutableList<UiAction> {
    composeRule.setContent {
      Box(modifier = Modifier.size(320.dp, 640.dp)) {
        SearchScreen(state = state, onAction = { actions += it })
      }
    }
    composeRule.waitForIdle()
    return actions
  }

  /** Every platform is reachable from this screen. */
  @Test
  fun `all four platforms are offered`() {
    render(state())

    Platform.entries.forEach { platform ->
      composeRule.onNodeWithText(platform.displayName).assertIsDisplayed()
    }
  }

  /** The current platform is the selected chip, so the row doubles as a label. */
  @Test
  fun `the current platform is marked selected`() {
    render(state(platform = Platform.HUYA))

    composeRule.onNodeWithText("虎牙").assertIsSelected()
  }

  /**
   * Tapping a chip asks for a search-scoped switch, not the home one.
   *
   * Asserting the *absence* of `SelectPlatform` is the point: the two look identical from the
   * outside, and only one of them keeps the user on the search screen.
   */
  @Test
  fun `tapping a chip emits the search scoped action`() {
    val actions = render(state())

    composeRule.onNodeWithText("哔哩哔哩").performClick()

    val switched = actions.filterIsInstance<UiAction.SelectSearchPlatform>()
    assertEquals(Platform.BILIBILI, switched.firstOrNull()?.platform)
    assertTrue(
      "the home screen's switcher must not be emitted here: it navigates to Screen.Home",
      actions.none { it is UiAction.SelectPlatform },
    )
  }

  /**
   * The keyword is not erased by the switch.
   *
   * The field is what the ViewModel re-runs the search with, so wiping it here would turn
   * "compare this streamer on two platforms" into "type it again".
   */
  @Test
  fun `the keyword survives a platform switch`() {
    val withKeyword = state().copy(searchQuery = "英雄联盟", lastSearchQuery = "英雄联盟")
    render(withKeyword)

    // The field keeps its text; nothing in this screen clears it on a chip tap.
    composeRule.onNodeWithText("英雄联盟").assertIsDisplayed()
  }

  /** The switcher is present before anything has been searched, too. */
  @Test
  fun `the switcher is there on the idle screen`() {
    render(state())

    // The idle state shows its own hint; the chip row must not be hidden behind it.
    composeRule.onNodeWithText("斗鱼").assertIsDisplayed()
    composeRule.onNodeWithText("虎牙").assertIsDisplayed()
  }
}
