package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
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
 * Dropping a follow from the follow list itself.
 *
 * The star on a follow card is 特别关注, so this screen had no way to unfollow anything: tidying up
 * twenty rooms meant opening twenty rooms. 取消关注 now sits in the long-press panel, and the point
 * of these tests is that *arming* it is not the same as doing it — the tap that writes through to
 * the follow store and the sync log is the second one.
 *
 * The panel is opened with a long press rather than by putting "sheet open" in a state, because no
 * `UiState` field expresses it; `FollowTagsTest` presses for the same reason.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class FollowUnfollowTest {

  @get:Rule
  val composeRule = createComposeRule()

  /** The room whose panel these tests open, and the one that has to leave the list. */
  private val dropped = Streamer(
    platform = Platform.DOUYU,
    roomId = "1",
    streamerName = "团团",
    title = "标题 1",
    isLive = true,
  )

  /**
   * A second follow that stays put.
   *
   * Without it "the card is gone" would also be satisfied by a screen that drew nothing at all, and
   * the last assertion would be reading the empty state instead of a grid.
   */
  private val kept = Streamer(
    platform = Platform.DOUYU,
    roomId = "2",
    streamerName = "宝宝",
    title = "标题 2",
    isLive = false,
  )

  private val actions = mutableListOf<UiAction>()

  private lateinit var state: MutableState<UiState>

  /**
   * Renders the screen and applies the follow toggle the way the view model does.
   *
   * It re-reads what the follow store would report instead of editing a copy in place, which is
   * exactly what makes the card leave the grid: `followed` no longer holds the room, so nothing in
   * the screen has to remove it by hand.
   */
  private fun render() {
    state = mutableStateOf(
      UiState(
        screen = Screen.Follow,
        followed = listOf(dropped, kept),
        followTags = mapOf(dropped.key to listOf("常看")),
      ),
    )
    composeRule.setContent {
      Box(modifier = Modifier.size(320.dp, 640.dp)) {
        FollowScreen(
          state = state.value,
          onAction = { action ->
            actions += action
            state.value = when (action) {
              is UiAction.ToggleFollow -> state.value.copy(
                followed = state.value.followed.filterNot { it.key == action.streamer.key },
                followTags = state.value.followTags - action.streamer.key,
              )
              else -> state.value
            }
          },
        )
      }
    }
    composeRule.waitForIdle()
  }

  private fun openPanel() {
    composeRule.onNodeWithText("标题 1").performTouchInput { longClick() }
    composeRule.waitForIdle()
  }

  private fun unfollows(): List<UiAction.ToggleFollow> =
    actions.filterIsInstance<UiAction.ToggleFollow>()

  @Test
  fun `the long-press panel offers unfollow and says what it costs`() {
    render()
    openPanel()

    composeRule.onNodeWithText("取消关注").assertIsDisplayed()
    composeRule.onNodeWithText("取消关注会把主播从列表里移除，它的标签只在关注页用，也会一起消失。")
      .assertIsDisplayed()
    assertTrue("opening the panel must not touch the follow list (actions=$actions)", actions.isEmpty())
  }

  /**
   * The tags are on the panel's first screen, not behind a fold.
   *
   * A 1.5× render of this panel is what produced the scrolling band in `TagEditorSheet`: the two
   * explanations grew to six lines, the chip list was measured at zero height and the unfollow row
   * was laid out past the sheet's bottom edge. The JVM cannot reproduce that — Robolectric's text
   * measurement is a stub, so the same paragraphs come out a couple of pixels tall here — so this
   * pins the half that is reproducible: at 1.0× the tags are inside the panel and inside the window.
   * If a later change pushes them below the fold at the default text size, this goes red before the
   * large-font render does.
   */
  @Test
  fun `the tags sit on the panel's first screen`() {
    render()
    openPanel()

    composeRule.onNodeWithText("常看").assertIsDisplayed()
  }

  /**
   * The first tap arms the button and stops there.
   *
   * This is the assertion the whole feature hangs on: a version that unfollowed on the first tap
   * would still show a "取消关注" button, still render a plausible panel, and would only reveal
   * itself by taking a record away.
   */
  @Test
  fun `the first tap only asks for confirmation`() {
    render()
    openPanel()

    composeRule.onNodeWithText("取消关注").performClick()
    composeRule.waitForIdle()

    assertTrue(
      "the arming tap must not reach the view model (actions=$actions)",
      unfollows().isEmpty(),
    )
    composeRule.onNodeWithText("确认取消关注").assertIsDisplayed()
  }

  @Test
  fun `the confirming tap unfollows the room the panel was opened for, exactly once`() {
    render()
    openPanel()

    composeRule.onNodeWithText("取消关注").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("确认取消关注").performClick()
    composeRule.waitForIdle()

    val toggles = unfollows()
    assertEquals("exactly one unfollow, got ${toggles}", 1, toggles.size)
    assertEquals(
      "the toggle must name the room whose panel was open",
      dropped.key,
      toggles.first().streamer.key,
    )

    // The panel closed, and the card left the grid because `followed` changed — no removal of its
    // own. The room that was not touched is still there, which is what makes the absence mean
    // something.
    composeRule.onNodeWithText("确认取消关注").assertDoesNotExist()
    composeRule.onNodeWithText("标题 1").assertDoesNotExist()
    composeRule.onNodeWithText("标题 2").assertIsDisplayed()
  }

  /** The confirmation has to be escapable, or the only way to keep a follow is to close the panel. */
  @Test
  fun `backing out of the confirmation keeps the follow`() {
    render()
    openPanel()

    composeRule.onNodeWithText("取消关注").performClick()
    composeRule.waitForIdle()
    composeRule.onNodeWithText("保留关注").performClick()
    composeRule.waitForIdle()

    assertTrue("backing out must not unfollow (actions=$actions)", unfollows().isEmpty())
    composeRule.onNodeWithText("确认取消关注").assertDoesNotExist()
    composeRule.onNodeWithText("取消关注").assertIsDisplayed()
    assertTrue(
      "the room must still be followed",
      state.value.followed.any { it.key == dropped.key },
    )
  }
}
