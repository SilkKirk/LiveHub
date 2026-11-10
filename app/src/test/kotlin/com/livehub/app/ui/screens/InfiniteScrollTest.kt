package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasAnyDescendant
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Scrolling to the bottom has to ask for the next page — starting from an **empty** first render.
 *
 * This is the half of infinite scroll no existing test covered. `CategoryPaginationTest` and
 * `SearchPagingTest` both drive `MainViewModel` directly (`onAction(UiAction.LoadMore)`), so they
 * prove the *request* is well formed but say nothing about whether the screen ever sends it.
 * It did not:
 *
 * ```
 * val shouldLoadMore by remember { derivedStateOf { ... state.hasMore ... } }
 * ```
 *
 * `UiState` is an immutable data class, not snapshot state, so `derivedStateOf` recomputes only
 * when `gridState.layoutInfo` changes and then reads whichever instance the lambda captured —
 * and `remember` with no key captured the **first** one. On a cold start that instance is
 * `rooms = emptyList(), hasNextPage = false`, so `hasMore` stayed false for the lifetime of the
 * page and `LoadMore` was never dispatched. Visiting another tab appeared to fix it only because
 * that destroys the composable, so the `remember` captures a state that already has rooms.
 *
 * Hence the shape of these tests: **compose empty, then push data in**, which is what a real
 * launch does. A test that composes a populated state passes against the broken code, and that is
 * precisely why the defect survived fifty-eight rounds.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class InfiniteScrollTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val categories = listOf(
    Category.aggregate(Platform.DOUYU, "全部直播"),
    Category(Platform.DOUYU, "1", "英雄联盟", "1", "网游", sortOrder = 0),
  )

  /** Enough rows that the first page does not fit on a 320x640 screen. */
  private fun rooms(prefix: String, count: Int) = (1..count).map { index ->
    Streamer(
      platform = Platform.DOUYU,
      roomId = "$prefix$index",
      // Braces are required, not cosmetic: CJK characters are valid Kotlin identifier
      // characters, so `"$prefix主播"` parses as one identifier named `prefix主播` and fails to
      // resolve. This is the one place a template needs delimiting.
      streamerName = "${prefix}主播$index",
      title = "$prefix 标题 $index",
      isLive = true,
      viewerCount = 1_000L * index,
    )
  }

  private fun baseState() = UiState(
    platform = Platform.DOUYU,
    categories = categories,
    selectedCategory = categories.first(),
  )

  /**
   * The vertical list containing [name], not the chip rows or the pager above it.
   *
   * At 320dp the home screen carries four scrollable nodes: the group row, the partition row, the
   * `HorizontalPager` and the grid. A bare `hasScrollAction()` matches all of them and `onNode`
   * fails on ambiguity — and picking "the last one" would silently start testing the wrong node
   * the day a row is added. Matching on a *vertical* axis range narrows it to the chip rows and
   * the grid, and anchoring on a card that only the grid contains makes it unique.
   */
  private fun verticalListContaining(name: String) = composeRule.onNode(
    SemanticsMatcher.keyIsDefined(SemanticsProperties.VerticalScrollAxisRange) and
      hasAnyDescendant(hasText(name)),
  )

  /**
   * Home: an empty first render, then a page of rooms, then a scroll to the end.
   *
   * The assertion is on the emitted action rather than on a spinner, because the action is what
   * the ViewModel reacts to; a spinner with no request behind it is the failure being guarded
   * against.
   */
  @Test
  fun `scrolling the home grid to the bottom asks for the next page`() {
    val actions = mutableListOf<UiAction>()
    var state by mutableStateOf(baseState())

    composeRule.setContent {
      Box(modifier = Modifier.size(320.dp, 640.dp)) {
        HomeScreen(state = state, onAction = { actions += it })
      }
    }
    composeRule.waitForIdle()

    // The cold-start shape: nothing loaded yet, so there is nothing to scroll.
    assertTrue(
      "the fixture must start empty, otherwise this test cannot reproduce a cold start",
      state.rooms.isEmpty(),
    )

    // The first page arrives, and the platform reports another one behind it.
    state = state.copy(rooms = rooms("a", 12), hasNextPage = true)
    composeRule.waitForIdle()

    verticalListContaining("a主播1").performScrollToIndex(11)
    composeRule.waitForIdle()

    assertTrue(
      "scrolling to the bottom of the grid must dispatch LoadMore, got $actions",
      actions.contains(UiAction.LoadMore),
    )
  }

  /**
   * Search: same defect, same shape — and here it cost every keyword search its second page.
   *
   * The search page is always entered before anything has been searched, so the captured
   * `searchResults = emptyList()` was guaranteed on every visit, unlike home, which a tab round
   * trip could repair.
   */
  @Test
  fun `scrolling the search results to the bottom asks for the next page`() {
    val actions = mutableListOf<UiAction>()
    var state by mutableStateOf(baseState().copy(screen = Screen.Search))

    composeRule.setContent {
      Box(modifier = Modifier.size(320.dp, 640.dp)) {
        SearchScreen(state = state, onAction = { actions += it })
      }
    }
    composeRule.waitForIdle()

    assertTrue(
      "the fixture must start with no results, which is how the search page is always entered",
      state.searchResults.isEmpty(),
    )

    state = state.copy(
      searchQuery = "英雄联盟",
      lastSearchQuery = "英雄联盟",
      searchResults = rooms("b", 12),
      searchHasMore = true,
    )
    composeRule.waitForIdle()

    verticalListContaining("b主播1").performScrollToIndex(11)
    composeRule.waitForIdle()

    assertTrue(
      "scrolling to the bottom of the results must dispatch LoadMoreSearch, got $actions",
      actions.contains(UiAction.LoadMoreSearch),
    )
  }

  /**
   * The guard the fix must not break: a single-page platform stays quiet.
   *
   * `hasNextPage = false` is Douyin's feed and Bilibili's hot tab. Without this, a "fix" that
   * simply always dispatched would pass the two tests above and spin the network forever on
   * exactly the lists that have no second page.
   */
  @Test
  fun `a single page platform never asks for more`() {
    val actions = mutableListOf<UiAction>()
    var state by mutableStateOf(baseState())

    composeRule.setContent {
      Box(modifier = Modifier.size(320.dp, 640.dp)) {
        HomeScreen(state = state, onAction = { actions += it })
      }
    }
    composeRule.waitForIdle()

    state = state.copy(rooms = rooms("c", 12), hasNextPage = false)
    composeRule.waitForIdle()

    verticalListContaining("c主播1").performScrollToIndex(11)
    composeRule.waitForIdle()

    assertTrue(
      "a list with no next page must not dispatch LoadMore, got $actions",
      actions.none { it == UiAction.LoadMore },
    )
  }
}
