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
import com.livehub.core.model.FollowGroup
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Assert.assertEquals
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
  ) = UiState(
    followed = rooms,
    followGroups = groups,
    roomGroups = roomGroups,
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

    // 主播1 sits in 游戏, and the row says so without opening anything.
    composeRule.onNodeWithText("游戏").assertExists()
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
