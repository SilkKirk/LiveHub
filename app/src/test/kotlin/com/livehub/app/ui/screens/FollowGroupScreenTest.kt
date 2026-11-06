package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.model.Category
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
 * (which group am I looking at) above a state/platform row (what am I filtering). The claims a JVM
 * test can hold are about *which* chips exist and what a tap emits — the storage behaviour is
 * `LibraryStoreTest`'s job.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class FollowGroupScreenTest {

  @get:Rule
  val composeRule = createComposeRule()

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

  @Test
  fun `the group row lists every group plus a way to manage them`() {
    compose(state())

    composeRule.onNodeWithText("全部").assertIsDisplayed()
    composeRule.onNodeWithText("游戏").assertIsDisplayed()
    composeRule.onNodeWithText("音乐").assertIsDisplayed()
    composeRule.onNodeWithText("管理").assertIsDisplayed()
  }

  @Test
  fun `tapping a group scopes the list to it`() {
    val actions = mutableListOf<UiAction>()
    compose(state(), actions)

    composeRule.onNodeWithText("游戏").performClick()

    assertEquals(UiAction.SelectFollowGroup("g1"), actions.first { it is UiAction.SelectFollowGroup })
  }

  /**
   * A room can be in several groups, so the row is a multi-select filter rather than a choice:
   * the room filed into both must still appear when either group is selected.
   */
  @Test
  fun `a room in two groups shows under each of them`() {
    val both = mapOf(rooms[0].key to setOf("g1", "g2"))
    compose(state(selected = "g1", roomGroups = both))
    composeRule.onNodeWithText("主播1").assertIsDisplayed()
  }

  /**
   * The group's own order, not the global one.
   *
   * Both orders are stored separately (`member:` records carry the group position, `room:`
   * records the global one), so a group view that read `followed` would silently show the global
   * arrangement and make "arrange rooms inside 游戏" a no-op.
   */
  @Test
  fun `a group shows its rooms in the group's own order`() {
    val roomGroups = mapOf(
      rooms[0].key to setOf("g1"),
      rooms[1].key to setOf("g1"),
    )
    compose(
      state(
        selected = "g1",
        roomGroups = roomGroups,
        // Global order says 主播1 first; the group order says 主播2 first.
        groupRoomOrder = mapOf("g1" to listOf(rooms[1].key, rooms[0].key)),
      ),
    )

    val first = composeRule.onAllNodesWithText("主播2")[0].fetchSemanticsNode().boundsInRoot.top
    val second = composeRule.onAllNodesWithText("主播1")[0].fetchSemanticsNode().boundsInRoot.top
    assertTrue("the group order must win inside the group", first < second)
  }

  @Test
  fun `a scoped group hides rooms that are not in it`() {
    compose(state(selected = "g2"))

    // 主播1 is filed into g1 only, so g2 must not show it.
    composeRule.onNodeWithText("主播1").assertDoesNotExist()
    composeRule.onNodeWithText("主播2").assertIsDisplayed()
  }

  @Test
  fun `the room row names the groups it is filed into`() {
    compose(state())

    // 主播1 sits in 游戏, and the row says so without opening anything. The group name appears
    // twice — once as the chip, once in the row — which is the point.
    assertEquals(2, composeRule.onAllNodesWithText("游戏").fetchSemanticsNodes().size)
  }

  @Test
  fun `the manage button opens the sheet`() {
    compose(state())

    composeRule.onNodeWithText("管理").performClick()

    composeRule.onNodeWithText("分组管理").assertIsDisplayed()
    composeRule.onNodeWithText("完成").assertIsDisplayed()
  }

  @Test
  fun `the sheet refuses to draw an empty group list as a blank panel`() {
    compose(state().copy(followGroups = emptyList()))

    composeRule.onNodeWithText("管理").performClick()

    composeRule.onNodeWithText("还没有分组。在下面输入一个名字就能建。").assertIsDisplayed()
  }
}
