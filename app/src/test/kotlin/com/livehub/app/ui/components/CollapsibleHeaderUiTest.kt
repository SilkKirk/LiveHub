package com.livehub.app.ui.components

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
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.app.ui.screens.FollowScreen
import com.livehub.app.ui.screens.HomeScreen
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
 * 下滑收起、上滑显示 — with a gesture, on both screens that use the fold.
 *
 * `CollapsibleHeaderTest` pins the decision table; this pins that the decision is wired to a real
 * list and to a real header, in both directions, on each page. Two tests per screen, because the
 * per-screen part is exactly the wiring: the component is shared, the lambda that says "the list has
 * moved" is not, and a screen that remembered its grid state in the wrong place would pass the pure
 * test and fail here.
 *
 * The header is identified by text that only exists while it is composed — the group chips on 首页,
 * the filter row on 关注 — so "the node is gone" means "the header folded".
 *
 * Gestures are aimed at a card rather than at the list: matching the list is ambiguous (the platform
 * pager is a scrollable too, and it contains the same cards), and the root starts inside the tabs,
 * where nothing scrolls.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class CollapsibleHeaderUiTest {

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

  private fun composeFollow() {
    composeRule.setContent {
      LiveHubTheme {
        Box(modifier = Modifier.size(320.dp, 640.dp)) {
          FollowScreen(
            state = UiState(screen = Screen.Follow, followed = rooms),
            onAction = {},
          )
        }
      }
    }
    composeRule.waitForIdle()
  }

  private fun count(text: String): Int =
    composeRule.onAllNodesWithText(text).fetchSemanticsNodes().size

  private fun cards() = composeRule.onAllNodesWithText("直播标题", substring = true)

  private fun topCard() = cards().let { it[0] }

  private fun bottomCard() = cards().let { it[it.fetchSemanticsNodes().size - 1] }

  @Test
  fun `home - swiping the grid up folds the category tree away`() {
    composeHome()
    assertTrue("the tree starts open", count("网游") == 1)

    topCard().performTouchInput { swipeUp() }
    composeRule.waitForIdle()

    assertTrue(
      "after a downward scroll the group row must be gone, found ${count("网游")}",
      count("网游") == 0,
    )
  }

  @Test
  fun `home - swiping back down brings it back`() {
    composeHome()

    topCard().performTouchInput { swipeUp() }
    composeRule.waitForIdle()
    assertTrue("folded first", count("网游") == 0)

    bottomCard().performTouchInput { swipeDown() }
    composeRule.waitForIdle()

    assertTrue("scrolling back up must bring the tree back", count("网游") == 1)
    composeRule.onNodeWithText("网游").assertIsDisplayed()
  }

  @Test
  fun `follow - swiping the grid up folds the whole header away`() {
    composeFollow()
    assertTrue("the filter row starts visible", count("全部") == 1)

    topCard().performTouchInput { swipeUp() }
    composeRule.waitForIdle()

    assertTrue(
      "after a downward scroll the filter row must be gone, found ${count("全部")}",
      count("全部") == 0,
    )
  }

  @Test
  fun `follow - swiping back down brings it back`() {
    composeFollow()

    topCard().performTouchInput { swipeUp() }
    composeRule.waitForIdle()
    assertTrue("folded first", count("全部") == 0)

    bottomCard().performTouchInput { swipeDown() }
    composeRule.waitForIdle()

    assertTrue("scrolling back up must bring the header back", count("全部") == 1)
  }
}
