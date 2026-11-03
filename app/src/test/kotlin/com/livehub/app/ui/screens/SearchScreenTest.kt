package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
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
 * What the search screen shows, and at which moment.
 *
 * The page keyed its empty state on the text field rather than on the keyword that had actually
 * been searched. The field changes on every keystroke and a search only runs on submit, so the
 * first character typed replaced the page with "没有找到「英」" — a claim about a search that had
 * not happened. A second symptom of the same mix-up: after a search, editing the field renamed
 * the empty state to the new text while the results on screen still answered the old one.
 *
 * The results themselves are rows, not grid tiles (`StreamerCardStyle.Search` in the reference
 * client). The layout assertions measure instead of counting nodes, because a result row and a
 * grid tile both contain the title, the streamer name and the viewer count — only their
 * positions differ.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class SearchScreenTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun streamer(
    id: String,
    name: String,
    title: String,
    viewers: Long = 0,
    live: Boolean = true,
  ) = Streamer(
    platform = Platform.DOUYU,
    roomId = id,
    streamerName = name,
    title = title,
    viewerCount = viewers,
    isLive = live,
  )

  private fun searchState() = UiState(platform = Platform.DOUYU, screen = Screen.Search)

  private fun render(state: UiState) {
    composeRule.setContent {
      Box(modifier = Modifier.size(320.dp, 640.dp)) {
        SearchScreen(state = state, onAction = {})
      }
    }
    composeRule.waitForIdle()
  }

  /**
   * The node's box in the composed window, in dp.
   *
   * `useUnmergedTree` is what makes this measure the text rather than the card: a result row is
   * clickable, and a clickable container merges its children's semantics into itself, so the
   * merged node answers to every text it contains. Matching the merged node made the title and
   * the viewer count resolve to the same box — 92dp tall, the whole card — and the position
   * assertions then compared a node with itself.
   */
  private fun boxOf(node: SemanticsNodeInteraction): NodeBox = with(composeRule.density) {
    val rect = node.fetchSemanticsNode().boundsInRoot
    NodeBox(
      left = rect.left.toDp().value,
      top = rect.top.toDp().value,
      height = rect.height.toDp().value,
    )
  }

  private fun bounds(text: String): NodeBox =
    boxOf(composeRule.onNodeWithText(text, useUnmergedTree = true))

  private data class NodeBox(val left: Float, val top: Float, val height: Float)

  @Test
  fun `typing alone never claims the keyword was not found`() {
    render(searchState().copy(searchQuery = "英雄"))

    composeRule.onNodeWithText("搜索主播或直播间").assertIsDisplayed()
    composeRule.onNodeWithText("没有找到「英雄」").assertDoesNotExist()
  }

  @Test
  fun `the no-result state names the keyword that was searched, not the edited text`() {
    render(
      searchState().copy(
        searchQuery = "英雄联盟s",
        lastSearchQuery = "英雄联盟",
      ),
    )

    composeRule.onNodeWithText("没有找到「英雄联盟」").assertIsDisplayed()
    composeRule.onNodeWithText("没有找到「英雄联盟s」").assertDoesNotExist()
  }

  @Test
  fun `results are one per row, not two per row`() {
    render(
      searchState().copy(
        searchQuery = "英雄联盟",
        lastSearchQuery = "英雄联盟",
        searchResults = listOf(
          streamer("1", "一条小团团", "今天继续冲分！冲击王者一百星", 128_500),
          streamer("2", "旭旭宝宝", "DNF 深渊日常，出货就送", 96_300),
        ),
      ),
    )

    val first = bounds("今天继续冲分！冲击王者一百星")
    val second = bounds("DNF 深渊日常，出货就送")

    // A two-column grid puts the second card on the same row, half a screen to the right.
    assertEquals("the second result starts a new column: $first vs $second", first.left, second.left, 1f)
    assertTrue("the second result must be below the first: $first vs $second", second.top > first.top)
  }

  /**
   * The viewer count belongs in the text column.
   *
   * On the grid tile it is an overlay inside the cover, which puts it *above* the title; a row
   * has room for it as plain text under the title, which is where the reference client puts it.
   */
  @Test
  fun `the viewer count sits under the title instead of on the cover`() {
    render(
      searchState().copy(
        searchQuery = "英雄联盟",
        lastSearchQuery = "英雄联盟",
        searchResults = listOf(streamer("1", "一条小团团", "今天继续冲分！冲击王者一百星", 128_500)),
      ),
    )

    val title = bounds("今天继续冲分！冲击王者一百星")
    val viewers = bounds("12.9万")

    assertTrue("the viewer count is drawn on the cover: $viewers vs $title", viewers.top > title.top)
  }

  /**
   * The follow action belongs to the row, not to the cover.
   *
   * On the grid tile the star is drawn over the thumbnail's top-right corner, i.e. *above* the
   * title; a row puts it at the trailing edge, level with the text. This is the assertion the
   * two-line title could not carry: Robolectric measures text at roughly one pixel per
   * character, so a realistic Chinese title never wraps here and a height comparison proves
   * nothing either way. The 2-line clamp is visible in `11-search-results.png` instead, which is
   * captured on a device.
   */
  @Test
  fun `the follow action sits at the end of the row, level with the text`() {
    render(
      searchState().copy(
        searchQuery = "英雄联盟",
        lastSearchQuery = "英雄联盟",
        searchResults = listOf(streamer("1", "一条小团团", "今天继续冲分！冲击王者一百星", 128_500)),
      ),
    )

    val title = bounds("今天继续冲分！冲击王者一百星")
    val star = boxOf(composeRule.onNodeWithContentDescription("关注", useUnmergedTree = true))

    assertTrue("the follow star is still drawn on the cover: $star vs $title", star.top > title.top)
    assertTrue("the follow star is not at the trailing edge: $star vs $title", star.left > title.left)
  }

  /**
   * A failure replaces the list it failed to answer.
   *
   * The view model clears the previous keyword's results on a failed search (see
   * `SearchPagingTest`); this is the render that proves the screen shows the failure rather than
   * a stale list, which is what "no results but an error" looked like before.
   */
  @Test
  fun `a failed search offers a retry`() {
    render(
      searchState().copy(
        searchQuery = "英雄联盟",
        lastSearchQuery = "英雄联盟",
        error = "斗鱼返回了网页而不是数据，请稍后重试",
      ),
    )

    composeRule.onNodeWithText("搜索失败").assertIsDisplayed()
    composeRule.onNodeWithText("重试").assertIsDisplayed()
    composeRule.onNodeWithText("斗鱼返回了网页而不是数据，请稍后重试").assertIsDisplayed()
  }

  /** A home-screen load failure must not be reported here as a search failure. */
  @Test
  fun `a load error from another screen is not a search failure`() {
    render(searchState().copy(error = "无法连接到服务器，请检查网络后重试"))

    composeRule.onNodeWithText("搜索主播或直播间").assertIsDisplayed()
    composeRule.onNodeWithText("搜索失败").assertDoesNotExist()
  }
}
