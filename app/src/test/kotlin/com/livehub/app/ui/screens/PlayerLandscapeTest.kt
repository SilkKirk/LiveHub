package com.livehub.app.ui.screens

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.livehub.app.ui.LiveHubRoot
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.designsystem.LiveHubTheme
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Who answers the back gesture while the player's settings panel is open in a landscape window.
 *
 * The panel is drawn inside the activity's window rather than as a `ModalBottomSheet` — a sheet is
 * capped at a fraction of an already short landscape window, which is why the drawer exists at all —
 * and nothing in the framework dismisses a hand-drawn surface. So the gesture fell through to
 * `LiveHubRoot`, which turned it into 缩小: back closed the *room* and left the panel open, and since
 * this composable is not disposed when the picture shrinks into the corner, the panel came back
 * unclicked the next time it was expanded.
 *
 * The window is a real landscape configuration here (`-land`), because that is the only thing that
 * selects the drawer over the sheet: the two branches differ by `isLandscape`, which is read from the
 * window and not from any parameter a test could pass. `PlayerChromeTest` can pass a landscape
 * *layout* to `PlayerContent`, but the back gesture belongs to `PlayerScreen`, one level up.
 *
 * The clock is frozen for the same reason as in `PlayerChromeTest`: the chrome hides itself three
 * seconds after the picture appears, and a click on a button that has already gone is a test that
 * says nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w891dp-h411dp-land")
class PlayerLandscapeTest {

  @get:Rule
  val composeRule = createAndroidComposeRule<ComponentActivity>()

  /**
   * The room, with no resolved variant on purpose: a room that has one hands the picture to a real
   * Media3 decoder, which no JVM test can build. The chrome — and the settings button with it — is
   * drawn either way.
   */
  private val room = Streamer(
    platform = Platform.DOUYU,
    roomId = "1",
    streamerName = "一条小团团",
    title = "今天继续冲分",
    isLive = true,
  )

  private fun playerState() = UiState(
    screen = Screen.Player,
    platform = Platform.DOUYU,
    room = room,
  )

  private fun renderPlayer(): MutableList<UiAction> {
    val actions = mutableListOf<UiAction>()
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      LiveHubTheme {
        LiveHubRoot(state = playerState(), onAction = { actions += it })
      }
    }
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.waitForIdle()
    return actions
  }

  /** A row that only the settings panel draws — the player itself has no such text. */
  private fun settingsPanelNodes(): Int =
    composeRule.onAllNodesWithText("显示弹幕").fetchSemanticsNodes().size

  private fun openSettings() {
    // The strip's 清晰度 chip is the way into the settings panel now; the gear is gone (round 144).
    composeRule.onNodeWithText("清晰度").performClick()
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.waitForIdle()
    assertEquals("the drawer did not open", 1, settingsPanelNodes())
  }

  private fun pressBack() {
    composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.waitForIdle()
  }

  @Test
  fun `back closes the settings drawer instead of leaving the room`() {
    val actions = renderPlayer()
    openSettings()

    pressBack()

    assertEquals("the panel has to consume the gesture", 0, settingsPanelNodes())
    assertFalse(
      "back must not leave the room while the panel is open, got $actions",
      actions.any { it is UiAction.Back || it is UiAction.MinimizePlayer },
    )
  }

  /**
   * The same gesture, once the panel is closed, still does what it did before: 缩小.
   *
   * Without this the drawer's handler could swallow every back press — a panel that is not on screen
   * would silently disable the way out of the player.
   */
  @Test
  fun `back without the panel still minimises the player`() {
    val actions = renderPlayer()

    pressBack()

    assertTrue(
      "back is 缩小 when nothing is open, got $actions",
      actions.any { it is UiAction.Back || it is UiAction.MinimizePlayer },
    )
  }

  /**
   * A trip through the corner window closes the panel for good.
   *
   * `showSettings` is a `remember` in `PlayerScreen`, and that composable survives the shrink — the
   * same call site renders all three sizes so the decoder is never rebuilt. The flag therefore
   * outlived the panel's owner and re-opened it, unclicked, on expand.
   */
  @Test
  fun `the drawer does not come back after a trip through the mini window`() {
    var state by mutableStateOf(playerState())
    val actions = mutableListOf<UiAction>()
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      LiveHubTheme {
        LiveHubRoot(state = state, onAction = { actions += it })
      }
    }
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.waitForIdle()

    openSettings()

    // 缩小: the room stays open, the screen goes back to browsing, the picture moves to the corner.
    composeRule.runOnUiThread { state = playerState().copy(screen = Screen.Home) }
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.waitForIdle()
    assertEquals("the panel is gone with the player", 0, settingsPanelNodes())

    // And back into the full player, which is where it used to re-appear.
    composeRule.runOnUiThread { state = playerState() }
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.waitForIdle()
    assertEquals("expanding must not re-open a panel nobody opened", 0, settingsPanelNodes())
  }
}
