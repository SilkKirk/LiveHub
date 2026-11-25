package com.livehub.app.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
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
 * The mini window: what is left on screen after stepping off the player.
 *
 * Leaving the player used to close the room — the resolve, the danmaku socket and the picture all
 * went away, and coming back meant starting over. Now the room stays open and the same player
 * composable is resized into a corner of the browse screens (see `LiveHubRoot`); this is the shell
 * around it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class MiniPlayerTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val room = Streamer(
    platform = Platform.DOUYU,
    roomId = "7",
    streamerName = "主播",
    title = "标题",
    isLive = true,
  )

  /**
   * A room that is open, on whichever screen the user went back to.
   *
   * `selectedVariant` is deliberately left null: with a variant the compact player hands the picture
   * to a real `ExoPlayer`, and the thing under test here is the window around the picture.
   */
  private fun miniState(screen: Screen = Screen.Home) = UiState(
    screen = screen,
    platform = Platform.DOUYU,
    room = room,
  )

  private fun render(state: UiState, onAction: (UiAction) -> Unit = {}) {
    // The compact player's resolve state is a spinner, and a spinner never lets the composition
    // reach idle on a running clock.
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent { LiveHubRoot(state = state, onAction = onAction) }
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.waitForIdle()
  }

  /** Enough rooms to fill several screens, so "the last row" is a row the user had to scroll to. */
  private val rooms = (1..40).map { index ->
    Streamer(
      platform = Platform.DOUYU,
      roomId = "$index",
      streamerName = "主播$index",
      title = "标题$index",
      isLive = true,
    )
  }

  /** Scrolls the page's only vertical list to its last item and lets the layout settle. */
  private fun scrollToLastCard() {
    composeRule.onNode(SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange))
      .performSemanticsAction(SemanticsActions.ScrollToIndex) { it(rooms.lastIndex) }
    composeRule.mainClock.advanceTimeBy(500L)
    composeRule.waitForIdle()
  }

  /**
   * The floating window does not sit on top of the grid's last row.
   *
   * The window is 168×94.5dp in the bottom-right corner, and both grids used a flat 10dp of bottom
   * padding — so the last row's title and streamer name were behind it, and `LazyVerticalGrid` stops at
   * the end of its content, which meant no amount of scrolling could bring them out. The reserve is
   * `MINI_PLAYER_CLEARANCE`.
   */
  @Test
  fun `the home grid's last row stops above the floating window`() {
    render(miniState().copy(rooms = rooms))
    scrollToLastCard()

    val window = composeRule.onNodeWithContentDescription("展开播放页")
      .fetchSemanticsNode().boundsInRoot
    val lastTitle = composeRule.onNodeWithText("标题40").fetchSemanticsNode().boundsInRoot

    assertTrue(
      "the last card ends at ${lastTitle.bottom}, the floating window starts at ${window.top}",
      lastTitle.bottom <= window.top,
    )
  }

  /** The same reserve, wired through the follow page's own grid. */
  @Test
  fun `the follow grid's last row stops above the floating window`() {
    render(miniState(screen = Screen.Follow).copy(followed = rooms))
    scrollToLastCard()

    val window = composeRule.onNodeWithContentDescription("展开播放页")
      .fetchSemanticsNode().boundsInRoot
    val lastTitle = composeRule.onNodeWithText("标题40").fetchSemanticsNode().boundsInRoot

    assertTrue(
      "the last followed card ends at ${lastTitle.bottom}, the window starts at ${window.top}",
      lastTitle.bottom <= window.top,
    )
  }

  @Test
  fun `a room left behind keeps a small window over the browse screen`() {
    render(miniState())

    composeRule.onNodeWithContentDescription("展开播放页").assertIsDisplayed()
    composeRule.onNodeWithContentDescription("关闭小窗").assertIsDisplayed()
  }

  /** The whole point of the single call site: on the player screen there is no window, just the player. */
  @Test
  fun `there is no small window while the player is on screen`() {
    render(miniState(screen = Screen.Player))

    composeRule.onNodeWithContentDescription("关闭小窗").assertDoesNotExist()
    composeRule.onNodeWithContentDescription("展开播放页").assertDoesNotExist()
  }

  @Test
  fun `tapping the window expands it and the cross closes the room`() {
    val actions = mutableListOf<UiAction>()
    render(miniState(), onAction = { actions += it })

    composeRule.onNodeWithContentDescription("展开播放页").performClick()
    composeRule.onNodeWithContentDescription("关闭小窗").performClick()
    composeRule.waitForIdle()

    assertEquals(listOf<UiAction>(UiAction.ExpandPlayer, UiAction.ClosePlayer), actions)
  }
}
