package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
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
 * The home screen's chrome has to stay small enough that cards are what you see.
 *
 * At 320x640 the old layout stacked up to three chip rows under a platform bar that had itself
 * wrapped onto two lines: chips ran from y=8 to y=268, 41% of the screen, and the grid showed
 * less than two rows of cards (`03b-home-subcategory.png` in r21). Every assertion here is a
 * property that layout did not have:
 *
 *  * the four platform tabs share one baseline — the old `FlowRow` put 哔哩哔哩 on its own line;
 *  * the group chip and the categories share one row — they used to be two rows;
 *  * with all three Douyu levels present, the deepest chip row still starts in the top fifth
 *    of a 640dp screen — it used to start at 222dp;
 *  * the group that is no longer a row of its own is still reachable, through the picker.
 *
 * Measuring positions rather than counting nodes is deliberate: every one of these nodes exists
 * in the broken layout too, so `assertExists` would have been green throughout.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class HomeCategoryDensityTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun category(id: String, name: String, group: String, order: Int) =
    Category(Platform.DOUYU, id, name, id, group, sortOrder = order)

  /**
   * Douyu's real shape: three groups, several categories each, and a third level under one.
   *
   * The group row only exists when a platform publishes more than one group, so a single-group
   * fixture would test the easy branch and miss the layout that was actually too tall.
   */
  private val categories = listOf(
    category("86", "英雄联盟", "网游", 0),
    category("2", "绝地求生", "网游", 1),
    category("3", "DNF", "网游", 2),
    category("1", "王者荣耀", "手游", 3),
    category("4", "户外", "娱乐", 4),
  )

  private val subCategories = listOf(
    category("c3:3683", "峡谷之巅", "网游", 5),
    category("c3:3684", "云顶之弈", "网游", 6),
  )

  private val rooms = (1..6).map { index ->
    Streamer(
      platform = Platform.DOUYU,
      roomId = "$index",
      streamerName = "主播$index",
      title = "直播标题$index",
      viewerCount = index * 10_000L,
      isLive = true,
    )
  }

  private fun homeState(withSubCategories: Boolean = false) = UiState(
    platform = Platform.DOUYU,
    categories = categories,
    selectedCategory = categories.first(),
    subCategories = if (withSubCategories) subCategories else emptyList(),
    selectedSubCategory = if (withSubCategories) subCategories.first() else null,
    rooms = rooms,
  )

  /** 320x640 is the smallest window the renders cover, and where the chips took 41% of it. */
  private fun composeHome(
    state: UiState = homeState(),
    onAction: (UiAction) -> Unit = {},
  ) {
    composeRule.setContent {
      Box(modifier = Modifier.size(320.dp, 640.dp)) {
        HomeScreen(state = state, onAction = onAction)
      }
    }
    composeRule.waitForIdle()
  }

  /** The top edge of a node, in dp, measured against the composed window. */
  private fun topDp(text: String): Float = with(composeRule.density) {
    composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.top.toDp().value
  }

  @Test
  fun `the four platform tabs stay on one line`() {
    composeHome()

    // 虎牙 and 哔哩哔哩 are only rendered by the switcher (every card in the fixture is a 斗鱼
    // room), so this compares two tabs and nothing else. Wrapping put them 58dp apart.
    val huya = topDp("虎牙")
    val bilibili = topDp("哔哩哔哩")
    assertEquals("哔哩哔哩 is on its own line under 虎牙: $bilibili vs $huya", huya, bilibili, 1f)
  }

  @Test
  fun `the group and its categories share one row`() {
    composeHome()

    assertEquals(
      "网游 is on a row of its own above the categories",
      topDp("英雄联盟"),
      topDp("网游"),
      1f,
    )
  }

  /**
   * The density claim itself, with all three Douyu levels on screen.
   *
   * 130dp is far above the one-row layout (~80dp) and far below the three-row one (222dp), so
   * neither font metrics nor chip padding can move the result across the line.
   */
  @Test
  fun `the deepest chip row starts in the top fifth of the screen`() {
    composeHome(state = homeState(withSubCategories = true))

    val deepest = topDp("全部")
    assertTrue("the third level starts ${deepest}dp down a 640dp screen", deepest < 130f)
  }

  @Test
  fun `the third level stays inline under the category row`() {
    composeHome(state = homeState(withSubCategories = true))

    composeRule.onNodeWithText("峡谷之巅").assertIsDisplayed()
    assertTrue(topDp("峡谷之巅") > topDp("英雄联盟"))
  }

  /**
   * The group that lost its own row is still one tap away.
   *
   * 王者荣耀 belongs to 手游, which is not the active group, so it is not on screen until the
   * picker is opened and the group switched — the whole path the collapsed row has to keep.
   */
  @Test
  fun `the picker still reaches a category from another group`() {
    val actions = mutableListOf<UiAction>()
    composeHome(onAction = { actions += it })

    composeRule.onNodeWithText("网游").performClick()
    composeRule.onNodeWithText("选择分类").assertIsDisplayed()

    composeRule.onNodeWithText("手游").performClick()
    composeRule.onNodeWithText("王者荣耀").performClick()

    val picked = actions.filterIsInstance<UiAction.SelectCategory>().single()
    assertEquals("王者荣耀", picked.category.name)
    composeRule.onNodeWithText("选择分类").assertDoesNotExist()
  }

  /**
   * A platform with a single group hides nothing, so it gets no picker chip.
   *
   * The chip is the escape hatch for categories that are *not* inline; showing it where every
   * category already is would be a second control for the same job.
   */
  @Test
  fun `a single-group platform shows no picker chip`() {
    val single = listOf(
      category("1", "英雄联盟", "网游", 0),
      category("2", "王者荣耀", "网游", 1),
    )
    composeHome(
      state = homeState().copy(categories = single, selectedCategory = single.first()),
    )

    composeRule.onNodeWithText("英雄联盟").assertIsDisplayed()
    composeRule.onNodeWithText("王者荣耀").assertIsDisplayed()
    composeRule.onNodeWithText("网游").assertDoesNotExist()
  }
}
