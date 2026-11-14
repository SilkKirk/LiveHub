package com.livehub.app.ui.player

import android.content.pm.ActivityInfo
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.app.ui.screens.PlayerScreen
import com.livehub.core.designsystem.LiveHubTheme
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * When the player asks the window to turn, and when it stops asking.
 *
 * The reported defect was the first row of the table below: pressing 全屏 on a phone held upright
 * hid the chrome and left the window portrait, so the picture filled a portrait rectangle and
 * "full screen" never happened. The rest of the table is the part that decides whether the fix is
 * liveable — a lock that outlives the full-screen state would leave the whole app sideways, and a
 * lock triggered by *physical* rotation would take away the rotation that got there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class PlayerOrientationTest {

  @get:Rule
  val composeRule = createComposeRule()

  // ------------------------------------------------------------------ the decision ---

  @Test
  fun `the fullscreen button turns the window to landscape`() {
    assertEquals(
      PlayerOrientation.Landscape,
      playerOrientation(manualFullscreen = true, compact = false),
    )
  }

  @Test
  fun `the portrait player does not ask for anything`() {
    assertEquals(
      PlayerOrientation.Unspecified,
      playerOrientation(manualFullscreen = false, compact = false),
    )
  }

  /**
   * The thumbnail never owns the device's orientation — not even while the flag is still set.
   *
   * The flag *is* cleared when the player minimizes (see `PlayerScreen`), but the decision must be
   * right on its own: `compact` wins, because a mini window inside another screen that kept the
   * app sideways would be a bug nobody could explain.
   */
  @Test
  fun `the mini window never asks for landscape`() {
    assertEquals(
      PlayerOrientation.Unspecified,
      playerOrientation(manualFullscreen = true, compact = true),
    )
    assertEquals(
      PlayerOrientation.Unspecified,
      playerOrientation(manualFullscreen = false, compact = true),
    )
  }

  /**
   * `SENSOR_LANDSCAPE`, not `LANDSCAPE`: a phone turned the other way must still show the picture
   * upright, and `LANDSCAPE` is one fixed direction.
   */
  @Test
  fun `landscape follows the sensor for both directions`() {
    assertEquals(
      ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE,
      PlayerOrientation.Landscape.toActivityInfo(),
    )
    assertEquals(
      ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
      PlayerOrientation.Unspecified.toActivityInfo(),
    )
  }

  // ------------------------------------------------------------------- the wiring ---

  private val room = Streamer(
    platform = Platform.DOUYU,
    roomId = "1",
    streamerName = "一条小团团",
    title = "今天继续冲分",
    isLive = true,
  )

  /**
   * A room with no resolved variant: the player shows its "not playing" notice instead of building
   * a Media3 decoder, which no JVM test can do. The chrome — and the fullscreen button with it — is
   * drawn either way.
   */
  private fun state() = UiState(screen = Screen.Player, platform = Platform.DOUYU, room = room)

  private fun renderPlayer(requests: MutableList<PlayerOrientation>) {
    composeRule.setContent {
      LiveHubTheme {
        Box(modifier = Modifier.size(320.dp, 640.dp)) {
          PlayerScreen(
            state = state(),
            onAction = {},
            orientationRequest = { requests += it },
          )
        }
      }
    }
    composeRule.waitForIdle()
  }

  @Test
  fun `pressing fullscreen requests landscape and pressing it again releases it`() {
    val requests = mutableListOf<PlayerOrientation>()
    renderPlayer(requests)

    // The first request is the initial "nothing of ours" state; the button is what changes it.
    assertEquals(
      "the player starts by releasing any lock it does not hold",
      listOf(PlayerOrientation.Unspecified),
      requests,
    )

    composeRule.onNodeWithContentDescription("全屏").performClick()
    composeRule.waitForIdle()
    assertEquals(
      "全屏 has to turn the window, not just hide the chrome",
      PlayerOrientation.Landscape,
      requests.last(),
    )

    // Two controls leave full screen once the player is full screen (the bar's arrow and the side
    // column's own toggle), so this walks the collection instead of demanding a unique node.
    composeRule.onAllNodesWithContentDescription("退出全屏")[0].performClick()
    composeRule.waitForIdle()
    assertEquals(
      "leaving full screen has to give the orientation back",
      PlayerOrientation.Unspecified,
      requests.last(),
    )
  }
}
