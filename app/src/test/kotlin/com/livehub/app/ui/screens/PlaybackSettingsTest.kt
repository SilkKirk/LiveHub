package com.livehub.app.ui.screens

import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
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
 * 播放 settings: 小窗播放 and 画中画.
 *
 * The page is a `LazyColumn`, so the card only composes after a scroll — the two switches are
 * identified by tag rather than by their label, because the label is its own semantics node and a
 * test that clicks the text would be clicking the wrong thing (the same reason
 * `BackgroundRefreshTest` uses tags).
 *
 * What each switch *does* is pinned elsewhere: the mini window in `MainViewModelTest` (leaving the
 * player stops the stream) and picture-in-picture in `PictureInPictureTest` (the policy, including
 * the switch). This file only proves the page turns taps into the two actions and renders the
 * stored values.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class PlaybackSettingsTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val actions = mutableListOf<UiAction>()

  private fun render(miniPlayer: Boolean = true, pictureInPicture: Boolean = true) {
    composeRule.setContent {
      LiveHubTheme {
        SettingsScreen(
          state = UiState(
            screen = Screen.Settings,
            miniPlayerEnabled = miniPlayer,
            pictureInPictureEnabled = pictureInPicture,
          ),
          onAction = { actions += it },
        )
      }
    }
    composeRule.waitForIdle()
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("小窗播放"))
    composeRule.waitForIdle()
  }

  @Test
  fun `both playback switches are on the settings page, on by default`() {
    render()

    composeRule.onNodeWithTag(MINI_PLAYER_SWITCH_TAG).assertIsOn()
    composeRule.onNodeWithTag(PICTURE_IN_PICTURE_SWITCH_TAG).assertIsOn()
  }

  @Test
  fun `a stored off value is what the switches show`() {
    render(miniPlayer = false, pictureInPicture = false)

    composeRule.onNodeWithTag(MINI_PLAYER_SWITCH_TAG).assertIsOff()
    composeRule.onNodeWithTag(PICTURE_IN_PICTURE_SWITCH_TAG).assertIsOff()
  }

  @Test
  fun `turning the mini window off emits its action`() {
    render()

    composeRule.onNodeWithTag(MINI_PLAYER_SWITCH_TAG).performClick()

    assertEquals(listOf<UiAction>(UiAction.SetMiniPlayer(false)), actions)
  }

  @Test
  fun `turning picture-in-picture off emits its action`() {
    render()

    composeRule.onNodeWithTag(PICTURE_IN_PICTURE_SWITCH_TAG).performClick()

    assertEquals(listOf<UiAction>(UiAction.SetPictureInPicture(false)), actions)
  }
}
