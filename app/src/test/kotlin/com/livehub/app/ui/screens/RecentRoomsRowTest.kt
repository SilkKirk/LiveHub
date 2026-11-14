package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.designsystem.LiveHubTheme
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 最近看过 strip on the search screen's idle state.
 *
 * The row is the only surface the play history has: `PlayerHistoryTest` proves the list is
 * recorded and persisted, this proves it is *drawn* and that tapping a card reopens that room.
 * Without it the feature could ship with a state nobody renders — the shape round 75 found in a
 * screenshot that had nothing to photograph.
 *
 * The empty case matters as much as the populated one: the row is written to disappear when there
 * is no history, and a header over an empty strip is worse than no strip at all.
 *
 * Rendered inside [LiveHubTheme], as every screen test in this package has been since round 85: the
 * assertions have to describe the page the app lays out rather than Material's default type scale.
 * Presence is what this file pins today — the strip is a `LazyRow` of fixed-width cards — and the
 * point of the theme is that the first position assertion added here already measures the real
 * scale.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class RecentRoomsRowTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val rooms = listOf(
    Streamer(Platform.DOUYU, "1", "一条小团团", "今天继续冲分", isLive = true),
    Streamer(Platform.DOUYU, "2", "旭旭宝宝", "DNF 深渊日常", isLive = false),
  )

  private fun render(
    state: UiState = UiState(screen = Screen.Search, recentRooms = rooms),
    actions: MutableList<UiAction> = mutableListOf(),
  ): MutableList<UiAction> {
    composeRule.setContent {
      LiveHubTheme {
        Box(modifier = Modifier.size(320.dp, 640.dp)) {
          SearchScreen(state = state, onAction = { actions += it })
        }
      }
    }
    composeRule.waitForIdle()
    return actions
  }

  @Test
  fun `the idle screen shows the rooms that were just watched`() {
    render()

    composeRule.onNodeWithText("最近看过").assertIsDisplayed()
    // Both names, not just the first: the strip is a LazyRow, and a row that only ever renders its
    // first card is the same class of bug as a tag row that starts off screen.
    composeRule.onNodeWithText("一条小团团").assertIsDisplayed()
    composeRule.onNodeWithText("旭旭宝宝").assertIsDisplayed()
  }

  /**
   * Tapping a card reopens *that* room.
   *
   * The assertion is on the emitted action's room id rather than on the tap: a card that opened
   * the wrong room — or the first one in the list every time — would still look right on screen.
   */
  @Test
  fun `tapping a card reopens that room`() {
    val actions = render()

    composeRule.onNodeWithText("旭旭宝宝").performClick()
    composeRule.waitForIdle()

    val opened = actions.filterIsInstance<UiAction.OpenRoom>()
    assertEquals("expected exactly one open, got $actions", 1, opened.size)
    assertEquals("douyu:2", opened.single().streamer.key)
  }

  /**
   * No history, no strip — including the heading.
   *
   * `baseState()` in the screenshot harness carries no history either, which is why this case is
   * worth denying explicitly: it is the state every existing test and artifact is in.
   */
  @Test
  fun `the strip is absent when nothing has been watched`() {
    render(UiState(screen = Screen.Search))

    assertTrue(
      "the row must not exist without history",
      composeRule.onAllNodesWithText("最近看过").fetchSemanticsNodes().isEmpty(),
    )
  }

  /** While results are showing the idle strip is gone: it belongs to the "nothing searched" state. */
  @Test
  fun `the strip is not drawn over search results`() {
    render(
      UiState(
        screen = Screen.Search,
        searchQuery = "团团",
        lastSearchQuery = "团团",
        recentRooms = rooms,
        searchResults = listOf(rooms.first()),
      ),
    )

    assertTrue(
      "the idle strip must not sit above a result list",
      composeRule.onAllNodesWithText("最近看过").fetchSemanticsNodes().isEmpty(),
    )
  }
}
