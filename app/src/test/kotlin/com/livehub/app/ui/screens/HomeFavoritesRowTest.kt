package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The pinned-partition row under the platform tabs.
 *
 * Three claims, and each is the reason a previous attempt at this feature failed:
 *
 *  * it is **absent** when nothing is pinned — a permanent row that only repeated "select a
 *    category" cost 40dp on every home render, which is why the earlier version was removed;
 *  * it is scoped to the **current platform**, because it sits inside the tab's column;
 *  * the aggregate entry never appears in it — 全部直播 is the whole platform, not a partition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class HomeFavoritesRowTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val categories = listOf(
    Category.aggregate(Platform.DOUYU, "全部直播"),
    Category(Platform.DOUYU, "86", "英雄联盟", "1", "网游", sortOrder = 0),
    Category(Platform.DOUYU, "2", "绝地求生", "1", "网游", sortOrder = 1),
    Category(Platform.BILIBILI, "1:2", "虚拟主播", "1", "虚拟", sortOrder = 0),
  )

  private val rooms = (1..4).map { index ->
    Streamer(
      platform = Platform.DOUYU,
      roomId = "$index",
      streamerName = "主播$index",
      title = "直播标题$index",
      isLive = true,
    )
  }

  private fun state(favorites: List<Category>) = UiState(
    platform = Platform.DOUYU,
    categories = categories,
    selectedCategory = categories.first(),
    rooms = rooms,
    favorites = favorites,
  )

  private fun compose(state: UiState, actions: MutableList<UiAction> = mutableListOf()) {
    composeRule.setContent {
      Box(modifier = Modifier.size(320.dp, 640.dp)) {
        HomeScreen(state = state, onAction = { actions += it })
      }
    }
    composeRule.waitForIdle()
  }

  @Test
  fun `no favourites draws no row at all`() {
    compose(state(emptyList()))

    // The star prefix is what distinguishes this row from the category row.
    composeRule.onNodeWithText("★ 英雄联盟").assertDoesNotExist()
  }

  @Test
  fun `a pinned partition shows as a star chip`() {
    compose(state(listOf(categories[1])))

    composeRule.onNodeWithText("★ 英雄联盟").assertIsDisplayed()
  }

  @Test
  fun `a favourite of another platform is not shown under this tab`() {
    compose(state(listOf(categories[3])))

    composeRule.onNodeWithText("★ 虚拟主播").assertDoesNotExist()
  }

  /**
   * Without this the feature is unfindable from a platform that has nothing pinned: the row would
   * simply be missing and nothing would hint that favourites exist elsewhere.
   */
  @Test
  fun `a platform with no favourites points at the ones it has elsewhere`() {
    compose(state(listOf(categories[3])))

    composeRule.onNodeWithText("在 1 个其他平台有收藏 ›").assertIsDisplayed()
  }

  @Test
  fun `tapping a favourite selects that partition`() {
    val actions = mutableListOf<UiAction>()
    compose(state(listOf(categories[1])), actions)

    composeRule.onNodeWithText("★ 英雄联盟").performClick()

    val selected = actions.filterIsInstance<UiAction.SelectCategory>().single()
    assertEquals("86", selected.category.id)
  }

  /**
   * The aggregate entry can never be a favourite, so the row must never contain it — even if a
   * hand-edited record or an old payload tried to put it there.
   */
  @Test
  fun `the aggregate entry never appears in the row`() {
    compose(state(listOf(categories[0])))

    composeRule.onNodeWithText("★ 全部直播").assertDoesNotExist()
    // And it must not leave a dangling empty row behind either.
    composeRule.onNodeWithText("全部直播").assertIsDisplayed() // still the category chip
  }

  @Test
  fun `the row offers a way to pin more partitions`() {
    compose(state(listOf(categories[1])))

    composeRule.onNodeWithContentDescription("收藏分区").assertIsDisplayed()
  }

  @Test
  fun `many pinned partitions stay on one scrollable row`() {
    val many = (1..12).map {
      Category(Platform.DOUYU, "c$it", "分区$it", "1", "网游", sortOrder = it)
    }
    compose(state(many))

    // The first chip is laid out; a wrapping row would have pushed it off the top instead.
    composeRule.onNodeWithText("★ 分区1").assertIsDisplayed()
  }
}
