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
import com.livehub.app.ui.components.InlineErrorNotice
import com.livehub.core.designsystem.LiveHubTheme
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
 * A failed refresh has to say so even when the previous list is still on screen.
 *
 * `ErrorState` is the full-screen version and only fits an empty list. With rooms already loaded
 * there was no rendering path at all for `UiState.error`: the pull indicator retracted, the grid
 * stayed exactly as it was, and a dead network looked identical to "nobody is streaming". The
 * inline notice is that missing path — a row above the content, with the reason and a retry, that
 * does not take the page away.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeRefreshFailureTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val rooms = (0 until 4).map { index ->
    Streamer(
      platform = Platform.DOUYU,
      roomId = "$index",
      streamerName = "主播$index",
      title = "直播$index",
      isLive = true,
    )
  }

  private fun state(error: String?) = UiState(
    platform = Platform.DOUYU,
    categories = listOf(Category.aggregate(Platform.DOUYU, "全部直播")),
    selectedCategory = Category.aggregate(Platform.DOUYU, "全部直播"),
    rooms = rooms,
    error = error,
  )

  private fun render(state: UiState, onAction: (UiAction) -> Unit = {}) {
    composeRule.setContent {
      LiveHubTheme {
        Box(modifier = Modifier.size(320.dp, 640.dp)) {
          HomeScreen(state = state, onAction = onAction)
        }
      }
    }
    composeRule.waitForIdle()
  }

  /**
   * The regression: rooms on screen plus an error.
   *
   * Before the notice existed this composition rendered the grid alone and the reason was nowhere
   * — the assertion that fails on the old code is the text lookup below, not the grid's presence.
   */
  @Test
  fun `a failed refresh over an existing list shows the reason`() {
    render(state(error = "无法连接到服务器，请检查网络后重试"))

    composeRule.onNodeWithText("无法连接到服务器，请检查网络后重试").assertIsDisplayed()
    // The old content is still there: this is a note above the page, not a replacement.
    // `useUnmergedTree` because the card merges its own semantics; the grid tests read it the
    // same way.
    composeRule.onNodeWithText("直播0", useUnmergedTree = true).assertExists()
  }

  @Test
  fun `a successful load shows no failure notice`() {
    render(state(error = null))

    composeRule.onNodeWithText("无法连接到服务器，请检查网络后重试").assertDoesNotExist()
    composeRule.onNodeWithText("直播0", useUnmergedTree = true).assertExists()
  }

  @Test
  fun `the retry button asks for the same refresh the pull does`() {
    val actions = mutableListOf<UiAction>()
    render(state(error = "网络断了"), onAction = { actions += it })

    // The notice's own button, not the empty state's: this composition has rooms, so only one
    // 重试 node exists and the index is not needed.
    composeRule.onNodeWithText("重试").performClick()
    composeRule.waitForIdle()

    assertEquals(listOf<UiAction>(UiAction.Refresh), actions)
  }
}

/** The notice on its own, so its copy and its action are pinned independently of the home screen. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class InlineErrorNoticeTest {

  @get:Rule
  val composeRule = createComposeRule()

  @Test
  fun `the notice renders the message and reports a retry`() {
    var retried = 0
    composeRule.setContent {
      LiveHubTheme {
        InlineErrorNotice(message = "加载失败：超时", onRetry = { retried++ })
      }
    }

    composeRule.onNodeWithText("加载失败：超时").assertIsDisplayed()
    composeRule.onNodeWithText("重试").performClick()
    composeRule.waitForIdle()

    assertEquals(1, retried)
  }
}
