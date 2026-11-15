package com.livehub.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.LiveHubRoot
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.core.designsystem.LiveHubTheme
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Controls that repeat their own text must not say it twice to a screen reader.
 *
 * Both defects are the same shape, and both are the shape this project already avoids elsewhere:
 * the settings and sync rows give their leading icons no description precisely because `ListItem`
 * merges the row into one node, and a described icon beside a `Text` with the same words is read
 * twice. Two much more common controls had not been given the same treatment:
 *
 *  * the dock's five tabs — `NavigationBarItem` merges icon + label, so every tab announced itself
 *    as "首页，首页，已选择";
 *  * every room card — the cover carried `contentDescription = title` while the title `Text` sits
 *    directly below it inside the same merged `combinedClickable` node, so the title was read twice
 *    on the home grid, the follow grid and every search result.
 *
 * The assertions are "no node anywhere carries this string as a description", because the defect is
 * a *duplicate name*, and counting descriptions is the only way to see it: both versions still have
 * a correctly labelled node.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AccessibilityNamingTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val room = Streamer(
    platform = Platform.DOUYU,
    roomId = "1",
    streamerName = "一条小团团",
    title = "今天继续冲分",
    coverUrl = "https://example.com/cover.jpg",
    isLive = true,
  )

  @Test
  fun `a dock tab is named once, by its label`() {
    composeRule.setContent {
      LiveHubTheme {
        LiveHubRoot(state = UiState(screen = Screen.Home), onAction = {})
      }
    }
    composeRule.waitForIdle()

    // The label is what names the tab, and it is still there.
    composeRule.onNodeWithText("首页").assertExists()
    // Nothing repeats it as a description.
    composeRule.onAllNodesWithContentDescription("首页", useUnmergedTree = true).assertCountEquals(0)
  }

  @Test
  fun `a room card does not repeat its title through the cover`() {
    composeRule.setContent {
      LiveHubTheme {
        Box(modifier = Modifier.size(320.dp, 640.dp)) {
          StreamerCard(
            streamer = room,
            followed = false,
            onClick = {},
            onToggleFollow = {},
          )
        }
      }
    }
    composeRule.waitForIdle()

    // The title is on screen, as text.
    composeRule.onNodeWithText("今天继续冲分").assertExists()
    // And the cover no longer announces it a second time.
    composeRule.onAllNodesWithContentDescription("今天继续冲分", useUnmergedTree = true)
      .assertCountEquals(0)
  }

  /** The follow star must keep its description: it is the only thing that names that control. */
  @Test
  fun `the follow star still names itself`() {
    composeRule.setContent {
      LiveHubTheme {
        Box(modifier = Modifier.size(320.dp, 640.dp)) {
          StreamerCard(
            streamer = room,
            followed = false,
            onClick = {},
            onToggleFollow = {},
          )
        }
      }
    }
    composeRule.waitForIdle()

    composeRule.onNodeWithContentDescription("关注", useUnmergedTree = true).assertExists()
  }
}
