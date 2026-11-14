package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiState
import com.livehub.core.designsystem.LiveHubTheme
import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 下滑收起分区、上滑显示 — with a gesture, not with the pure function.
 *
 * `HomeHeaderCollapseTest` pins the decision table; this pins that the decision is actually wired to
 * the grid's scroll and to the header's visibility, in both directions. A version that computed the
 * right answer and never applied it would pass that file and fail this one.
 *
 * The tree is identified by its group chips (网游 / 手游 / 娱乐): they exist only while the tree is
 * composed, so "the node is gone" is the same statement as "the tree folded".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class HomeHeaderCollapseUiTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun category(id: String, name: String, group: String, order: Int) =
    Category(Platform.DOUYU, id, name, id, group, sortOrder = order)

  private val categories = listOf(
    category("86", "英雄联盟", "网游", 0),
    category("2", "绝地求生", "网游", 1),
    category("1", "王者荣耀", "手游", 2),
    category("4", "户外", "娱乐", 3),
  )

  /** Enough rooms that a swipe lands on a second row rather than bouncing off the end. */
  private val rooms = (1..12).map { index ->
    Streamer(
      platform = Platform.DOUYU,
      roomId = "$index",
      streamerName = "主播$index",
      title = "直播标题$index",
      viewerCount = index * 10_000L,
      isLive = true,
    )
  }

  private fun composeHome() {
    composeRule.setContent {
      LiveHubTheme {
        Box(modifier = Modifier.size(320.dp, 640.dp)) {
          HomeScreen(
            state = UiState(
              platform = Platform.DOUYU,
              categories = categories,
              selectedCategory = categories.first(),
              rooms = rooms,
            ),
            onAction = {},
          )
        }
      }
    }
    composeRule.waitForIdle()
  }

  private fun groupRowCount(): Int =
    composeRule.onAllNodesWithText("网游").fetchSemanticsNodes().size

  @Test
  fun `swiping the grid up folds the category tree away`() {
    composeHome()
    assertTrue("the tree starts open", groupRowCount() == 1)

    composeRule.onNodeWithText("直播标题1").performTouchInput { swipeUp() }
    composeRule.waitForIdle()

    assertTrue(
      "after a downward scroll the group row must be gone, found $groupRowCount()",
      groupRowCount() == 0,
    )
  }

  @Test
  fun `swiping back down brings it back`() {
    composeHome()

    composeRule.onNodeWithText("直播标题1").performTouchInput { swipeUp() }
    composeRule.waitForIdle()
    assertTrue("folded first", groupRowCount() == 0)

    // Any visible card will do — after the fold the grid starts right under the platform tabs, so
    // the first row is still on screen.
    composeRule.onNodeWithText("直播标题1").performTouchInput { swipeDown() }
    composeRule.waitForIdle()

    assertTrue("scrolling back up must bring the tree back", groupRowCount() == 1)
    composeRule.onNodeWithText("网游").assertIsDisplayed()
  }
}
