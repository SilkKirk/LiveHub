package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.model.FollowGroup
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
 * The two things this feature adds to the follow screen.
 *
 * The follow list already had one filter row mixing two axes. This round splits it: a group row
 * (which group am I looking at) above a state/platform row (what am I filtering). What a JVM test
 * can hold is *which* chips exist, what a tap emits, and that the group's own order is what the
 * group shows — storage behaviour is `LibraryStoreTest`'s job.
 *
 * Two things make the selectors here less obvious than they look:
 *
 *  * a group name appears **twice** by design — once as a chip, once on every room filed into it —
 *    so chip assertions match on `Role.Checkbox`, which only the chip carries;
 *  * `isLive` rows sort before offline ones regardless of stored order (a deliberate view rule),
 *    so the ordering test has to use two rooms that are both live.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class FollowGroupScreenTest {

  @get:Rule
  val composeRule = createComposeRule()

  /** Rooms 1 and 3 are live, 2 and 4 are not — the live-first rule needs both kinds. */
  private val rooms = (1..4).map { index ->
    Streamer(
      platform = Platform.DOUYU,
      roomId = "$index",
      streamerName = "主播$index",
      title = "直播标题$index",
      viewerCount = index * 10_000L,
      isLive = index % 2 == 1,
    )
  }

  private val groups = listOf(FollowGroup("g1", "游戏", 0), FollowGroup("g2", "音乐", 1))

  private fun state(
    selected: String? = null,
    roomGroups: Map<String, Set<String>> = mapOf(rooms[0].key to setOf("g1")),
    groupRoomOrder: Map<String, List<String>> = emptyMap(),
  ) = UiState(
    followed = rooms,
    followGroups = groups,
    roomGroups = roomGroups,
    groupRoomOrder = groupRoomOrder,
    selectedFollowGroupId = selected,
  )

  private fun compose(state: UiState, actions: MutableList<UiAction> = mutableListOf()) {
    composeRule.setContent {
      Box(modifier = Modifier.size(320.dp, 640.dp)) {
        FollowScreen(state = state, onAction = { actions += it })
      }
    }
    composeRule.waitForIdle()
  }

  /** The group chip, as opposed to the same name printed on a room row. */
  private fun chip(label: String) = composeRule.onNode(
    SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) and hasText(label),
  )

  @Test
  fun `the group row lists every group plus a way to manage them`() {
    compose(state())

    chip("全部").assertIsDisplayed()
    chip("游戏").assertIsDisplayed()
    chip("音乐").assertIsDisplayed()
    composeRule.onNodeWithText("管理").assertIsDisplayed()
  }

  @Test
  fun `tapping a group scopes the list to it`() {
    val actions = mutableListOf<UiAction>()
    compose(state(), actions)

    chip("游戏").performClick()

    assertEquals(UiAction.SelectFollowGroup("g1"), actions.first { it is UiAction.SelectFollowGroup })
  }

  /**
   * A room can be in several groups, so the row is a multi-select filter rather than a choice:
   * the room filed into both must still appear when either group is selected.
   *
   * One group per test, not a second `setContent`: a Compose test rule may only set content once
   * per test, so "shows under each of them" has to be two cases rather than two renders.
   */
  @Test
  fun `a room in two groups shows when the first group is selected`() {
    compose(state(selected = "g1", roomGroups = mapOf(rooms[0].key to setOf("g1", "g2"))))
    composeRule.onNodeWithText("主播1").assertIsDisplayed()
  }

  @Test
  fun `a room in two groups shows when the second group is selected`() {
    compose(state(selected = "g2", roomGroups = mapOf(rooms[0].key to setOf("g1", "g2"))))
    composeRule.onNodeWithText("主播1").assertIsDisplayed()
  }

  /**
   * The defect this test was written after: membership comes from `roomGroups` and only the
   * *order* from `groupRoomOrder`. Reading the order map as the membership list made a group with
   * no populated order fall back to every followed room — the group filter silently did nothing.
   */
  @Test
  fun `a scoped group hides rooms that are not in it even with no group order`() {
    compose(
      state(
        selected = "g2",
        roomGroups = mapOf(rooms[0].key to setOf("g1"), rooms[1].key to setOf("g2")),
      ),
    )

    // 主播1 is filed into g1 only, so g2 must not show it.
    composeRule.onNodeWithText("主播1").assertDoesNotExist()
    composeRule.onNodeWithText("主播2").assertIsDisplayed()
  }

  /**
   * Both orders are stored separately (`member:` records carry the group position, `room:`
   * records the global one), so a group view that read `followed` would silently show the global
   * arrangement and make "arrange rooms inside 游戏" a no-op.
   *
   * Both rooms are live: the live-first rule outranks stored order, so mixing an offline room in
   * would test that rule instead of this one.
   */
  @Test
  fun `a group shows its rooms in the group's own order`() {
    val live = listOf(rooms[0], rooms[2])
    val roomGroups = live.associate { it.key to setOf("g1") }
    compose(
      state(
        selected = "g1",
        roomGroups = roomGroups,
        // Global order says 主播1 first; the group order says 主播3 first.
        groupRoomOrder = mapOf("g1" to listOf(rooms[2].key, rooms[0].key)),
      ),
    )

    val first = composeRule.onAllNodesWithText("主播3")[0].fetchSemanticsNode().boundsInRoot.top
    val second = composeRule.onAllNodesWithText("主播1")[0].fetchSemanticsNode().boundsInRoot.top
    assertTrue("the group order must win inside the group", first < second)
  }

  @Test
  fun `the room row names the groups it is filed into`() {
    compose(state())

    // 主播1 sits in 游戏, and the row says so without opening anything. The name appears twice —
    // once as the chip, once on the row — which is the point.
    assertEquals(2, composeRule.onAllNodesWithText("游戏").fetchSemanticsNodes().size)
  }

  @Test
  fun `a room in no group shows no group label`() {
    compose(state(roomGroups = emptyMap()))

    // Only the chip carries the name now.
    assertEquals(1, composeRule.onAllNodesWithText("游戏").fetchSemanticsNodes().size)
  }

  @Test
  fun `the manage button opens the sheet`() {
    compose(state())

    composeRule.onNodeWithText("管理").performClick()

    composeRule.onNodeWithText("分组管理").assertIsDisplayed()
    composeRule.onNodeWithText("完成").assertIsDisplayed()
  }

  @Test
  fun `an empty group list still explains how to create one`() {
    compose(state().copy(followGroups = emptyList()))

    composeRule.onNodeWithText("管理").performClick()

    composeRule.onNodeWithText("还没有分组。在下面输入一个名字就能建。").assertIsDisplayed()
  }

  /**
   * The management sheet exposes a drag handle per group.
   *
   * A long press on a card that also holds two icon buttons is not discoverable on its own, so the
   * handle is the affordance that says "this row can be reordered". The gesture itself is not
   * driven here — Robolectric cannot inject a long-press-and-drag reliably — but the handle's
   * presence is what a user sees, and `LibraryStoreTest` covers the write it triggers.
   */
  @Test
  fun `the management sheet offers a drag handle per group`() {
    compose(state())

    composeRule.onNodeWithText("管理").performClick()

    assertEquals(
      2,
      composeRule.onAllNodesWithContentDescription("长按拖动调整分组顺序").fetchSemanticsNodes().size,
    )
  }

  /**
   * The sheet's filing mode lists every group as a switch, and 未分类 is one of them: it is the
   * *absence* of memberships, so picking it clears the room's groups rather than adding one.
   */
  @Test
  fun `filing a room lists the groups and an unfiled entry`() {
    compose(state())

    composeRule.onNodeWithContentDescription("把 主播1 放进分组").performClick()

    composeRule.onNodeWithText("把「主播1」放进分组").assertIsDisplayed()
    composeRule.onNodeWithText("未分类").assertIsDisplayed()
  }

  // ------------------------------------------------------------ bulk selection

  @Test
  fun `the header offers a bulk selection mode`() {
    compose(state())

    composeRule.onNodeWithText("多选").assertIsDisplayed()
  }

  /**
   * Selection mode replaces the header. Leaving the group chips and the filters on screen would
   * invite a scope change mid-selection, which silently drops the rooms that just left the view
   * from the pending move.
   */
  @Test
  fun `entering selection mode swaps the header for a counter`() {
    compose(state())

    composeRule.onNodeWithText("多选").performClick()

    composeRule.onNodeWithText("已选 0 位").assertIsDisplayed()
    composeRule.onNodeWithText("全选").assertIsDisplayed()
    composeRule.onNodeWithText("放入分组").assertIsDisplayed()
    // The group chips are gone, so a tap can only mean "select".
    composeRule.onNodeWithText("管理").assertDoesNotExist()
  }

  @Test
  fun `tapping a row in selection mode selects it instead of opening it`() {
    val actions = mutableListOf<UiAction>()
    compose(state(), actions)

    composeRule.onNodeWithText("多选").performClick()
    composeRule.onNodeWithText("主播1").performClick()

    composeRule.onNodeWithText("已选 1 位").assertIsDisplayed()
    assertTrue("opening a room must not happen while selecting", actions.none { it is UiAction.OpenRoom })
  }

  @Test
  fun `select all picks every visible room`() {
    compose(state())

    composeRule.onNodeWithText("多选").performClick()
    composeRule.onNodeWithText("全选").performClick()

    composeRule.onNodeWithText("已选 4 位").assertIsDisplayed()
  }

  @Test
  fun `the bulk sheet lists the groups and explains that it only adds`() {
    compose(state())

    composeRule.onNodeWithText("多选").performClick()
    composeRule.onNodeWithText("全选").performClick()
    composeRule.onNodeWithText("放入分组").performClick()

    composeRule.onNodeWithText("把 4 位主播放进分组").assertIsDisplayed()
    composeRule.onNodeWithText("只添加，不会把主播从其他分组移出。").assertIsDisplayed()
    composeRule.onNodeWithText("取消").assertIsDisplayed()
  }

  @Test
  fun `the bulk sheet cannot confirm with nothing chosen`() {
    compose(state())

    composeRule.onNodeWithText("多选").performClick()
    composeRule.onNodeWithText("全选").performClick()
    composeRule.onNodeWithText("放入分组").performClick()

    composeRule.onNodeWithText("放入").assertIsNotEnabled()
  }

  @Test
  fun `confirming a bulk file emits one action carrying every selected room`() {
    val actions = mutableListOf<UiAction>()
    compose(state(), actions)

    composeRule.onNodeWithText("多选").performClick()
    composeRule.onNodeWithText("全选").performClick()
    composeRule.onNodeWithText("放入分组").performClick()
    // The bulk sheet lists groups as plain rows, not chips, so select by text here.
    composeRule.onNodeWithText("游戏").performClick()
    composeRule.onNodeWithText("放入").performClick()

    val bulk = actions.filterIsInstance<UiAction.AddRoomsToGroups>().single()
    assertEquals(4, bulk.rooms.size)
    assertEquals(setOf("g1"), bulk.groupIds)
  }

  @Test
  fun `a bulk sheet with no groups says to create one first`() {
    compose(state().copy(followGroups = emptyList()))

    composeRule.onNodeWithText("多选").performClick()
    composeRule.onNodeWithText("全选").performClick()
    composeRule.onNodeWithText("放入分组").performClick()

    composeRule.onNodeWithText("还没有分组。先用「管理」建一个，再回来批量放入。").assertIsDisplayed()
  }
}
