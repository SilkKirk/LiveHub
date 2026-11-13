package com.livehub.app.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Assert.assertEquals
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
