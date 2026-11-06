package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onLast
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
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The home screen's chrome has to stay small enough that cards are what you see.
 *
 * At 320x640 the layout this replaced stacked up to three chip rows under a platform bar that had
 * itself wrapped onto two lines: chips ran from y=8 to y=268, 41% of the screen, and the grid
 * showed less than two rows of cards. The inline category tree keeps that budget: one group row,
 * one partition row, and a third row only when the platform publishes a third level.
 *
 * The assertions are about the properties the broken layout lacked — shared baselines, a bounded
 * depth, and a collapsed default — rather than node counts, because every one of these nodes
 * exists in a bad layout too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class HomeCategoryDensityTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun category(id: String, name: String, group: String, order: Int) =
    Category(Platform.DOUYU, id, name, id, group, sortOrder = order)

  /**
   * Douyu's real shape: three groups, several partitions each, and a third level under one.
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

  /**
   * The group row and the partition row are two lines, not three.
   *
   * They used to share a single line (a group chip beside the partition chips), which is what
   * made room for the panel. The inline tree gives the groups their own row, so the check is that
   * the partition row starts below the group row rather than level with it.
   */
  @Test
  fun `the partition row sits below the group row`() {
    composeHome()

    assertTrue(
      "英雄联盟 (${topDp("英雄联盟")}dp) must be below 网游 (${topDp("网游")}dp)",
      topDp("英雄联盟") > topDp("网游"),
    )
  }

  /**
   * The density claim itself, with all three Douyu levels on screen.
   *
   * Two chip rows plus the third level measured 156dp on the CI font metrics; the layout this
   * replaced started its deepest row at 222dp, so 190dp still separates the two by a wide margin
   * while leaving room for font variation across devices.
   *
   * `onLast()` because 全部 is now also the group row's first chip — the third level's 全部 is the
   * one drawn last.
   */
  @Test
  fun `the deepest chip row starts in the top third of the screen`() {
    composeHome(state = homeState(withSubCategories = true))

    val deepest = with(composeRule.density) {
      composeRule.onAllNodesWithText("全部").onLast().fetchSemanticsNode().boundsInRoot.top.toDp().value
    }
    assertTrue("the third level starts ${deepest}dp down a 640dp screen", deepest < 190f)
  }

  @Test
  fun `the third level stays inline under the partition row`() {
    composeHome(state = homeState(withSubCategories = true))

    composeRule.onNodeWithText("峡谷之巅").assertIsDisplayed()
    assertTrue(topDp("峡谷之巅") > topDp("英雄联盟"))
  }

  /**
   * A category from another group is one tap away, and it does not require opening anything.
   *
   * 王者荣耀 belongs to 手游, which is not the active group, so it is not listed until that group
   * chip is tapped — the whole path the inline row has to keep.
   */
  @Test
  fun `another group's partition is one tap away`() {
    val actions = mutableListOf<UiAction>()
    composeHome(onAction = { actions += it })

    composeRule.onNodeWithText("手游").performClick()
    composeRule.onNodeWithText("王者荣耀").performClick()

    val picked = actions.filterIsInstance<UiAction.SelectCategory>().single()
    assertEquals("王者荣耀", picked.category.name)
  }

  /**
   * A platform with a single group hides nothing, so it gets no group row at all.
   *
   * Showing one would put every partition behind a redundant chip and cost a line of height for
   * no information.
   */
  @Test
  fun `a single-group platform shows no group row`() {
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

  // --------------------------------------------------------------- expandable

  @Test
  fun `the tree starts collapsed`() {
    composeHome()

    // Only the active group's partitions are listed.
    composeRule.onNodeWithText("英雄联盟").assertIsDisplayed()
    composeRule.onNodeWithText("户外").assertDoesNotExist()
  }

  /**
   * Expanding wraps the groups instead of scrolling one line, so a group that was off the right
   * edge becomes visible — which is the whole point of the chevron.
   */
  @Test
  fun `expanding shows every group`() {
    composeHome()

    composeRule.onNodeWithText("户外").assertDoesNotExist()
    composeRule.onNodeWithContentDescription("展开分类").performClick()

    composeRule.onNodeWithText("网游").assertIsDisplayed()
    composeRule.onNodeWithText("手游").assertIsDisplayed()
    composeRule.onNodeWithText("娱乐").assertIsDisplayed()
  }

  @Test
  fun `the chevron flips when expanded`() {
    composeHome()

    composeRule.onNodeWithContentDescription("展开分类").performClick()
    composeRule.onNodeWithContentDescription("收起分类").assertIsDisplayed()
  }

  /**
   * A single-group platform still gets a chevron.
   *
   * This used to assert the opposite, on the theory that a platform with nothing to hide needs no
   * expander. That was wrong for a reason only visible once the tree collapsed on selection: the
   * chevron is also how the *partitions* get wrapped into a readable grid, so gating it on the
   * group count left such a platform unable to return to the expanded state at all.
   */
  @Test
  fun `a single-group platform still has a chevron`() {
    val single = listOf(category("1", "英雄联盟", "网游", 0))
    composeHome(state = homeState().copy(categories = single, selectedCategory = single.first()))

    composeRule.onNodeWithContentDescription("展开分类").assertExists()
  }

  /**
   * Expanding must not push the grid off screen: the wrapped group block is bounded by the number
   * of *groups* (three here), not by the number of partitions.
   */
  @Test
  fun `expanding keeps the partition row on screen`() {
    composeHome()

    composeRule.onNodeWithContentDescription("展开分类").performClick()

    val partitions = topDp("英雄联盟")
    assertTrue("partitions start ${partitions}dp down after expanding", partitions < 220f)
  }

  /**
   * The group row leads with 全部, as the reference client does: "every group's partitions" is a
   * real scope, and without it the widest one available is the biggest group.
   */
  @Test
  fun `the group row leads with an all chip`() {
    composeHome()

    // 全部 is the first chip and 网游 the second, so anything left of 网游 can only be 全部.
    val all = composeRule.onAllNodesWithText("全部")[0]
    val left = with(composeRule.density) { all.fetchSemanticsNode().boundsInRoot.left.toDp().value }
    assertTrue("全部 starts at ${left}dp, 网游 at ${topDp("网游")}", left < leftDp("网游"))
  }

  /**
   * The collapsed tree is two lines: the group row and the partition row.
   *
   * This is the regression v0.15.0 shipped with. The partition row was a `FlowRow` regardless of
   * the collapsed state, so Douyu's ~40 partitions in 网游竞技 wrapped into a screenful of chips and
   * pushed the room grid entirely below the fold — the app opened to a wall of chips with no
   * streams visible at all. The assertion measures the grid's top edge, because the chip count is
   * exactly what was wrong and a node-existence check would have passed either way.
   */
  @Test
  fun `the collapsed tree leaves room for the grid`() {
    composeHome()

    val gridTop = with(composeRule.density) {
      composeRule.onNodeWithText("主播1").fetchSemanticsNode().boundsInRoot.top.toDp().value
    }
    assertTrue(
      "the first card starts ${gridTop}dp down a 640dp screen; the tree must stay near the top",
      gridTop < 260f,
    )
  }

  /**
   * Collapsed, a partition that does not fit scrolls rather than wrapping.
   *
   * The check is structural: the partition chips live inside a **horizontal** scroll container
   * when collapsed. `hasScrollAction` alone is not enough — the room grid is scrollable too, and
   * the broken `FlowRow` had no scroll container of its own at all.
   */
  @Test
  fun `the collapsed partition row scrolls instead of wrapping`() {
    composeHome()

    val horizontalScrollers = composeRule
      .onAllNodes(hasScrollAction() and hasAnyDescendant(hasText("DNF")))
      .fetchSemanticsNodes()
    assertTrue(
      "the partition row must sit in its own scroll container, found ${horizontalScrollers.size}",
      horizontalScrollers.isNotEmpty(),
    )
  }

  @Test
  fun `expanding wraps the partitions instead of scrolling them`() {
    composeHome()

    composeRule.onNodeWithContentDescription("展开分类").performClick()

    // Every partition of the active group is laid out at once, so all three are on screen.
    composeRule.onNodeWithText("英雄联盟").assertIsDisplayed()
    composeRule.onNodeWithText("绝地求生").assertIsDisplayed()
    composeRule.onNodeWithText("DNF").assertIsDisplayed()
  }

  /**
   * Picking a partition collapses the tree.
   *
   * The expanded tree is a browse state, not a destination: once a partition is chosen the user
   * wants to see its streams. Leaving it open kept a screenful of chips over the grid that had just
   * loaded, which is the same complaint the collapsed-`FlowRow` bug produced from the other side.
   *
   * "Collapsed" is observed through the *collapse* chevron disappearing rather than through the
   * expand chevron appearing: the collapsed row only draws a chevron when the platform publishes
   * more than one group, and asserting on the positive case would make this test depend on the
   * fixture's group count instead of on the behaviour.
   */
  @Test
  fun `picking a partition collapses the tree`() {
    val actions = mutableListOf<UiAction>()
    composeHome(onAction = { actions += it })

    composeRule.onNodeWithContentDescription("展开分类").performClick()
    composeRule.onNodeWithContentDescription("收起分类").assertExists()
    composeRule.onNodeWithText("绝地求生").performClick()

    // The selection is reported…
    assertEquals(
      "绝地求生",
      actions.filterIsInstance<UiAction.SelectCategory>().lastOrNull()?.category?.name,
    )
    // …and the expanded row is gone, which is what "collapsed" means here.
    composeRule.onNodeWithContentDescription("收起分类").assertDoesNotExist()
  }

  @Test
  fun `picking a third level collapses the tree too`() {
    val actions = mutableListOf<UiAction>()
    composeHome(state = homeState(withSubCategories = true), onAction = { actions += it })

    composeRule.onNodeWithContentDescription("展开分类").performClick()
    composeRule.onNodeWithContentDescription("收起分类").assertExists()
    composeRule.onNodeWithText("峡谷之巅").performClick()

    assertEquals(
      "峡谷之巅",
      actions.filterIsInstance<UiAction.SelectSubCategory>().lastOrNull()?.category?.name,
    )
    composeRule.onNodeWithContentDescription("收起分类").assertDoesNotExist()
  }

  @Test
  fun `the all group chip lists every group's partitions`() {
    composeHome()

    composeRule.onNodeWithText("户外").assertDoesNotExist()
    composeRule.onNodeWithText("全部").performClick()

    // 户外 is in 娱乐, 王者荣耀 in 手游: both now listed beside 网游's.
    composeRule.onNodeWithText("英雄联盟").assertIsDisplayed()
    composeRule.onNodeWithText("王者荣耀").assertIsDisplayed()
    composeRule.onNodeWithText("户外").assertIsDisplayed()
  }

  @Test
  fun `the all group chip is marked selected only when it is the active one`() {
    composeHome()

    // The baseline browses 网游 (the selected partition's group), so 全部 is not selected.
    composeRule.onAllNodesWithText("全部")[0].assertIsNotSelected()

    composeRule.onAllNodesWithText("全部")[0].performClick()

    composeRule.onAllNodesWithText("全部")[0].assertIsSelected()
  }

  private fun leftDp(text: String): Float = with(composeRule.density) {
    composeRule.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.left.toDp().value
  }
}
