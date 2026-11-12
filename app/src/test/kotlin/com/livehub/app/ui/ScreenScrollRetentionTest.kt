package com.livehub.app.ui

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performSemanticsAction
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A screen keeps its scroll position while another screen is shown.
 *
 * The grids and the result list are `rememberLazyGridState` / `rememberLazyListState`, which are
 * `rememberSaveable` — but a screen leaves composition whenever the shell shows something else, and
 * a `rememberSaveable` value is only persisted into a `SaveableStateHolder`. Without one, the room
 * grid was rebuilt from scratch on every return: watching a stream from halfway down the page and
 * pressing back put the user at the top of the list again.
 *
 * The round trip here goes through 关注 rather than the player: the player replaces the whole
 * scaffold (so it exercises the same disposal) but its empty resolve state spins forever, and a
 * compose test cannot reach idle on an infinite animation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class ScreenScrollRetentionTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val rooms = (1..40).map { index ->
    Streamer(
      platform = Platform.DOUYU,
      roomId = "$index",
      streamerName = "主播$index",
      title = "标题$index",
      isLive = true,
    )
  }

  /**
   * Renders the real shell, driven by a state the test can change.
   *
   * Only the three actions this test emits are reduced; everything else is dropped, which is what
   * makes the shell — and not the view model — the thing under test. The state is created *outside*
   * composition on purpose: `LiveHubRoot` is then a plain function of it, exactly as it is in
   * `MainActivity`, where the state comes from the view model rather than from a `remember`.
   */
  private fun render(): MutableState<UiState> {
    val state = mutableStateOf(UiState(rooms = rooms))
    composeRule.setContent {
      LiveHubRoot(
        state = state.value,
        onAction = { action -> state.value = reduce(state.value, action) },
      )
    }
    composeRule.waitForIdle()
    return state
  }

  private fun reduce(state: UiState, action: UiAction): UiState = when (action) {
    is UiAction.Navigate -> state.copy(screen = action.screen)
    is UiAction.OpenRoom -> state.copy(screen = Screen.Player, room = action.streamer)
    UiAction.ClosePlayer -> state.copy(screen = Screen.Home, room = null)
    else -> state
  }

  @Test
  fun `the home grid keeps its scroll position after a round trip through another screen`() {
    val state = render()

    // Selected by *its own* scroll range, not by a child: `performSemanticsAction` re-fetches the
    // node before it acts, and a selector that names a card stops matching the moment that card
    // scrolls out of composition ("the node is no longer in the tree").
    //
    // The grid is the only vertical scroller here: the platform switcher is a horizontal pager, and
    // the state has no categories, so no partition row is drawn.
    val grid = composeRule.onNode(
      SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange),
    )
    grid.performSemanticsAction(SemanticsActions.ScrollToIndex) { it(20) }
    composeRule.waitForIdle()

    // Two screens' worth down: the first card is gone and room 21 is on screen.
    composeRule.onNodeWithText("主播1").assertDoesNotExist()
    composeRule.onNodeWithText("主播21").assertIsDisplayed()

    state.value = reduce(state.value, UiAction.Navigate(Screen.Follow))
    composeRule.waitForIdle()
    composeRule.onNodeWithText("主播21").assertDoesNotExist()

    state.value = reduce(state.value, UiAction.Navigate(Screen.Home))
    composeRule.waitForIdle()

    // Back on the grid, at the position it was left at — not at the top.
    composeRule.onNodeWithText("主播21").assertIsDisplayed()
    composeRule.onNodeWithText("主播1").assertDoesNotExist()
  }
}
