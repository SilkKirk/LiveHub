package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Finding one name in a follow list that has grown past a screenful.
 *
 * The list is sorted live-first and has no name filter, so the hundredth follow is only reachable by
 * scrolling and hoping. The box that fixes that is drawn on the same rule as the chip row above it —
 * only when it can help — which is what these assertions are about: absent on a short list, present
 * on a long one, and still present once a query is typed even if the results are short.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class FollowSearchTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun streamer(id: String) = Streamer(
    platform = Platform.DOUYU,
    roomId = id,
    streamerName = "主播$id",
    title = "标题 $id",
    isLive = true,
    viewerCount = 1_000,
  )

  private fun state(count: Int, query: String = "") = UiState(
    screen = Screen.Follow,
    followed = (1..count).map { streamer("$it") },
    followQuery = query,
  )

  private fun render(state: UiState) {
    composeRule.setContent {
      Box(modifier = Modifier.size(320.dp, 640.dp)) {
        FollowScreen(state = state, onAction = {})
      }
    }
    composeRule.waitForIdle()
  }

  @Test
  fun `a short list gets no search box`() {
    render(state(count = 3))

    composeRule.onNodeWithText("搜索关注的主播").assertDoesNotExist()
    composeRule.onNodeWithText("标题 1").assertIsDisplayed()
  }

  @Test
  fun `a long list gets one`() {
    render(state(count = 12))

    composeRule.onNodeWithText("搜索关注的主播").assertIsDisplayed()
  }

  @Test
  fun `the query narrows the grid to matching names`() {
    render(state(count = 12, query = "主播7"))

    composeRule.onNodeWithText("标题 7").assertIsDisplayed()
    composeRule.onNodeWithText("标题 3").assertDoesNotExist()
    composeRule.onNodeWithText("标题 8").assertDoesNotExist()
  }

  /** The box cannot disappear under the finger the moment the results get short. */
  @Test
  fun `an active query keeps the box on a list that would not have one`() {
    render(state(count = 3, query = "主播2"))

    composeRule.onNodeWithText("搜索关注的主播").assertIsDisplayed()
    composeRule.onNodeWithText("标题 2").assertIsDisplayed()
    composeRule.onNodeWithText("标题 1").assertDoesNotExist()
  }

  @Test
  fun `nothing matching says so and offers to clear the filters`() {
    render(state(count = 12, query = "不存在的主播"))

    composeRule.onNodeWithText("没有找到「不存在的主播」").assertIsDisplayed()
    composeRule.onNodeWithText("清空筛选").assertIsDisplayed()
  }
}
