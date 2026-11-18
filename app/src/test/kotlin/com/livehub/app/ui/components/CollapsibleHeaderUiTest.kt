package com.livehub.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 下滑收起、上滑显示 — with a gesture, on both screens that use the fold, and once at the component.
 *
 * `CollapsibleHeaderTest` pins the arithmetic; this pins that it is wired to a real list and a real
 * header, in both directions, on each page. Two tests per screen, because the per-screen part is
 * exactly the wiring: the component is shared, the lambda that says "the list has moved" is not, and
 * a screen that remembered its grid state in the wrong place would pass the pure test and fail here.
 *
 * The header is identified by text that only exists while it is composed — the group chips on 首页,
 * the filter row on 关注 — so "the node is gone" means "the header folded".
 *
 * **Gestures are full height on purpose.** The fold is proportional to the drag now (round 131), so
 * a gesture aimed at a single card would fold exactly the card's height and stop — it would be
 * testing the card's size, not the wiring. `swipeUp()` on the root drags the whole window, which
 * clears any header on either page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class CollapsibleHeaderUiTest {

  @get:Rule
  val composeRule = createComposeRule()

  /** The state of the component-only test; JUnit builds a fresh instance for each test method. */
  private lateinit var foldState: CollapsibleHeaderState

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
   * A drag shorter than the header folds part of it — and the list does not move at all.
   *
   * The reported defect (round 131): any drag past 6px flipped a boolean, and a 160ms animation then
   * removed the header's whole height at once — "下滑时突然上面的收起来，感觉滑动很快". Two things
   * are pinned here, and neither is reachable through a gesture under Robolectric:
   *
   *  * the fold takes exactly the drag — the header shrinks by 60dp and no more;
   *  * the connection consumes that delta, so the list underneath does not also move by it. That is
   *    the other half of "太快": the old version consumed nothing and moved the content on a timer.
   *
   * Driven through `onPreScroll` directly rather than by injecting a touch, because a Robolectric
   * gesture ends in a fling: the fling legitimately folds the rest of the header, and a gesture-based
   * version of this assertion measured the fling instead of the drag (it failed on CI that way). The
   * four tests above are the gesture path.
   */
  @Test
  fun `a partial drag folds the header by the drag and keeps it on screen`() {
    composeRule.setContent {
      LiveHubTheme {
        foldState = rememberCollapsibleHeaderState()
        Column(modifier = Modifier.fillMaxSize().collapsibleHeaderScroll(foldState)) {
          CollapsibleHeader(state = foldState, modifier = Modifier.testTag("header-box")) {
            Box(modifier = Modifier.fillMaxWidth().height(200.dp).testTag("header-content"))
          }
          LazyColumn(modifier = Modifier.weight(1f)) {
            items(50) { index -> Text("行$index") }
          }
        }
      }
    }
    composeRule.waitForIdle()

    val before = composeRule.onNodeWithTag("header-box").fetchSemanticsNode().boundsInRoot.height
    val drag = with(composeRule.density) { 60.dp.toPx() }

    val consumed = composeRule.runOnIdle {
      foldState.connection.onPreScroll(Offset(0f, -drag), NestedScrollSource.UserInput)
    }
    composeRule.waitForIdle()

    assertEquals(
      "the fold must take the drag, not pass it on — otherwise the list moves by it as well",
      -drag,
      consumed.y,
      1f,
    )
    val after = composeRule.onNodeWithTag("header-box").fetchSemanticsNode().boundsInRoot.height
    assertEquals("the header must shrink by exactly the drag", before - drag, after, 1f)
    composeRule.onNodeWithTag("header-content").assertExists()
  }
}
