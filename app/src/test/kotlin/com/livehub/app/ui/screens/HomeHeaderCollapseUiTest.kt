package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
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
 *
 * Both swipes are performed on the **root** rather than on a card. The first version grabbed a card
 * by its title, which works until the fold happens — then that card is scrolled away and the second
 * gesture cannot be aimed. The window centre is inside the grid in both states.
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

  /**
   * The room grid, as opposed to the chip rows above it.
   *
   * Both are scrollable, and a gesture has to start *inside* the one it means to scroll: the first
   * version swiped on the root, which starts at the very top of the window — inside the platform tab
   * row — so the fold worked (any downward scroll does) but the unfold never received the gesture.
   * The cards are what distinguishes the grid from the chips.
   */
  private fun grid() = composeRule.onNode(
    hasScrollAction() and hasAnyDescendant(hasText("直播标题", substring = true)),
  )

  @Test
  fun `swiping the grid up folds the category tree away`() {
    composeHome()
    assertTrue("the tree starts open", groupRowCount() == 1)

    grid().performTouchInput { swipeUp() }
    composeRule.waitForIdle()

    assertTrue(
      "after a downward scroll the group row must be gone, found ${groupRowCount()}",
      groupRowCount() == 0,
    )
  }

  @Test
  fun `swiping back down brings it back`() {
    composeHome()

    grid().performTouchInput { swipeUp() }
    composeRule.waitForIdle()
    assertTrue("folded first", groupRowCount() == 0)

    grid().performTouchInput { swipeDown() }
    composeRule.waitForIdle()

    assertTrue("scrolling back up must bring the tree back", groupRowCount() == 1)
    composeRule.onNodeWithText("网游").assertIsDisplayed()
  }
}