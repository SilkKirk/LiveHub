package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
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
 * The 全部直播/热门 chip that leads the category row.
 *
 * The entry is a [Category] with `isAggregate = true`, so it goes through the same row and the
 * same `SelectCategory` action as a real one — which means three things have to be true of the
 * layout, and each was wrong by construction before it existed:
 *
 *  * it is the row's **first** chip, so "先随便看看" is reachable without scrolling;
 *  * it stays on screen after a real category from another group is picked — the row filters
 *    to the active group, and an aggregate belongs to none, so a naive filter would hide the
 *    only way back;
 *  * it is **not** in the group picker, where it would sit at the top of every group's list
 *    as a duplicate control that also has no group to be filed under.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class AggregateCategoryChipTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun category(id: String, name: String, group: String, order: Int) =
    Category(Platform.DOUYU, id, name, id, group, sortOrder = order)

  private val aggregate = Category.aggregate(Platform.DOUYU, "全部直播")

  private val categories = listOf(
    aggregate,
    category("86", "英雄联盟", "网游", 0),
    category("2", "绝地求生", "网游", 1),
    category("1", "王者荣耀", "手游", 2),
  )

  private val rooms = (1..4).map { index ->
    Streamer(
      platform = Platform.DOUYU,
      roomId = "$index",
      streamerName = "主播$index",
      title = "直播标题$index",
      viewerCount = index * 10_000L,
      isLive = true,
    )
  }

  private fun homeState(selected: Category) = UiState(
    platform = Platform.DOUYU,
    categories = categories,
    selectedCategory = selected,
    rooms = rooms,
  )

  private fun composeHome(
    state: UiState = homeState(categories[1]),
    onAction: (UiAction) -> Unit = {},
  ) {
    composeRule.setContent {
      Box(modifier = Modifier.size(320.dp, 640.dp)) {
        HomeScreen(state = state, onAction = onAction)
      }
    }
    composeRule.waitForIdle()
  }

  private fun leftDp(text: String): Float = with(composeRule.density) {
    composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.left.toDp().value
  }

  @Test
  fun `the aggregate chip leads the row`() {
    composeHome()

    // 英雄联盟 is the selected category of the active group and is the row's first *real*
    // chip, so anything to its left can only be the aggregate.
    assertTrue(
      "全部直播 starts at ${leftDp("全部直播")}dp, 英雄联盟 at ${leftDp("英雄联盟")}dp",
      leftDp("全部直播") < leftDp("英雄联盟"),
    )
  }

  /**
   * The regression this exists for: the row filters to the active group, and the aggregate
   * belongs to none. Picking 王者荣耀 (group 手游) must not take 全部直播 off the screen.
   */
  @Test
  fun `the aggregate chip survives a category from another group`() {
    composeHome(state = homeState(categories[3]))

    composeRule.onNodeWithText("全部直播").assertIsDisplayed()
    // 手游 is the active group now, so 英雄联盟 (网游) is correctly gone — the aggregate is
    // the one exception to that filter.
    composeRule.onNodeWithText("英雄联盟").assertDoesNotExist()
  }

  @Test
  fun `the aggregate chip is marked selected when it is the active category`() {
    composeHome(state = homeState(aggregate))

    composeRule.onNodeWithText("全部直播").assertIsSelected()
  }

  @Test
  fun `selecting the aggregate chip asks for it by id`() {
    val actions = mutableListOf<UiAction>()
    composeHome(onAction = { actions += it })

    composeRule.onNodeWithText("全部直播").performClick()

    val picked = actions.filterIsInstance<UiAction.SelectCategory>().single()
    assertEquals(Category.ALL_ID, picked.category.id)
    assertTrue(picked.category.isAggregate)
  }

  @Test
  fun `the group picker does not list the aggregate`() {
    composeHome()

    composeRule.onNodeWithText("网游").performClick()

    // The picker is open over 网游's categories; 全部直播 is already the row's first chip and has
    // no group to be filed under, so it must appear exactly once on screen — in the row.
    composeRule.onNodeWithText("选择分类").assertIsDisplayed()
    composeRule.onAllNodesWithText("全部直播").assertCountEquals(1)
  }
}
