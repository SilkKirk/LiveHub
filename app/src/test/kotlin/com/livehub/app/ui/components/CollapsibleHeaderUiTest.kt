package com.livehub.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
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
 * `CollapsibleHeaderTest` pins the arithmetic; this pins that it is wired to a real list and a real
 * header, in both directions, on each page. Two tests per screen — plus the round-131 case that a
 * short drag only folds part of the header — because the per-screen part is exactly the wiring: the
 * component is shared, the lambda that says "the list has moved" is not, and a screen that
 * remembered its grid state in the wrong place would pass the pure test and fail here.
 *
 * The header is identified by text that only exists while it is composed — the group chips on 首页,
 * the filter row on 关注 — so "the node is gone" means "the header folded".
 *
 * **Gestures are full height on purpose.** The fold is proportional to the drag now (round 131), so
 * a gesture aimed at a single card would fold exactly the card's height and stop — it would be
 * testing the card's size, not the wiring. `swipeUp()` on the root drags the whole 640dp window,
 * which clears any header on either page.
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

  private fun count(text: String, substring: Boolean = false): Int =
    composeRule.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().size

  /**
   * The follow header's own subtitle, "4/5 正在直播".
   *
   * The filter row was the obvious anchor and it was the wrong one: which chips a follow list shows
   * depends on what is in the list (the platform row disappears when everything is on one platform),
   * so an assertion on 「全部」 was really an assertion about the fixture.
   */
  private fun headerCount(): Int = count("正在直播", substring = true)

  /**
   * A drag from the window centre to the top: long enough to out-drag either page's header.
   *
   * Starting at the centre rather than at the bottom edge is what makes the gesture land: the bottom
   * edge is the exclusive bound of the root's bounds, and a touch there hits nothing to scroll.
   */
  private fun swipeUpThroughTheGrid() {
    composeRule.onRoot().performTouchInput { swipeUp(startY = centerY, endY = top) }
  }

  /**
   * Drags down from the middle of the window.
   *
   * Aimed at the root rather than at a card, because the card at the bottom of the list is usually
   * *partly* below the window: its centre is then outside the device, the injected touch lands
   * nowhere, and the failure reads as "the header did not come back" instead of "the gesture missed".
   * The window centre is always inside the grid.
   */
  private fun swipeDownInGrid() {
    composeRule.onRoot().performTouchInput { swipeDown(startY = centerY, endY = bottom) }
  }

  @Test
  fun `home - swiping the grid up folds the category tree away`() {
    composeHome()
    assertTrue("the tree starts open", count("网游") == 1)

    swipeUpThroughTheGrid()
    composeRule.waitForIdle()

    assertTrue(
      "after a downward scroll the group row must be gone, found ${count("网游")}",
      count("网游") == 0,
    )
  }

  @Test
  fun `home - swiping back down brings it back`() {
    composeHome()

    swipeUpThroughTheGrid()
    composeRule.waitForIdle()
    assertTrue("folded first", count("网游") == 0)

    swipeDownInGrid()
    composeRule.waitForIdle()

    assertTrue("scrolling back up must bring the tree back", count("网游") == 1)
    composeRule.onNodeWithText("网游").assertIsDisplayed()
  }

  @Test
  fun `follow - swiping the grid up folds the whole header away`() {
    composeFollow()
    assertTrue("the header starts visible, found ${headerCount()}", headerCount() == 1)

    swipeUpThroughTheGrid()
    composeRule.waitForIdle()

    assertTrue(
      "after a downward scroll the header must be gone, found ${headerCount()}",
      headerCount() == 0,
    )
  }

  @Test
  fun `follow - swiping back down brings it back`() {
    composeFollow()

    swipeUpThroughTheGrid()
    composeRule.waitForIdle()
    assertTrue("folded first, found ${headerCount()}", headerCount() == 0)

    swipeDownInGrid()
    composeRule.waitForIdle()

    assertTrue("scrolling back up must bring the header back, found ${headerCount()}", headerCount() == 1)
  }

  /**
   * A drag shorter than the header folds part of it, not all of it.
   *
   * The reported defect (round 131): any drag past 6px flipped the header away and a 160ms animation
   * then pulled the content up by the header's whole height — "下滑时突然上面的收起来，感觉滑动很快".
   * The old rule fails this assertion outright (the header is gone); the fold now moves by the
   * distance actually dragged, so a 60dp gesture leaves the rest of the header on screen.
   *
   * The gesture is stepped rather than one jump: the first step is spent on touch slop and on moving
   * the list at all (folding is gated on the list having moved), and the rest is what the fold uses.
   */
  @Test
  fun `follow - a short drag folds the header part-way instead of away`() {
    composeFollow()
    val before = composeRule.onNodeWithText("正在直播", substring = true)
      .fetchSemanticsNode().boundsInRoot

    composeRule.onRoot().performTouchInput {
      val start = center
      val step = 15.dp.toPx()
      down(start)
      repeat(4) { index ->
        moveTo(Offset(start.x, start.y - step * (index + 1)))
      }
      up()
    }
    composeRule.waitForIdle()

    assertTrue(
      "a 60dp drag must not remove the header, found ${headerCount()}",
      headerCount() == 1,
    )
    val after = composeRule.onNodeWithText("正在直播", substring = true)
      .fetchSemanticsNode().boundsInRoot
    assertTrue(
      "the header must have moved up by the drag, not stayed put or vanished: $before -> $after",
      after.top < before.top,
    )
  }
}
