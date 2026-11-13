package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The platform pager: a horizontal swipe changes platform, a tab tap animates to it.
 *
 * The pager is the one piece of navigation in this app that is driven by a *gesture*, so it is the
 * one that cannot be checked by looking at a single render — a screenshot shows a page, not the
 * transition into it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class PlatformPagerTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val categories = listOf(
    Category.aggregate(Platform.DOUYU, "全部直播"),
    Category(Platform.DOUYU, "86", "英雄联盟", "1", "网游", sortOrder = 0),
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

  private fun state(platform: Platform = Platform.DOUYU) = UiState(
    platform = platform,
    categories = categories,
    selectedCategory = categories.first(),
    rooms = rooms,
  )

  private fun compose(
    state: UiState = state(),
    actions: MutableList<UiAction> = mutableListOf(),
  ) {
    composeRule.setContent {
      Box(modifier = Modifier.size(320.dp, 640.dp)) {
        HomeScreen(state = state, onAction = { actions += it })
      }
    }
    composeRule.waitForIdle()
  }

  /**
   * Drags horizontally across the page content.
   *
   * Hand-rolled rather than `swipeLeft()`: the pager needs a drag that covers a real fraction of
   * its width *and* carries fling velocity, and the built-in helper's fixed 500ms makes the
   * velocity depend on the node's measured size in a way that is hard to reason about. Small
   * increments per frame are also what a finger actually produces — the same approach
   * `BackSwipeContainerTest` takes.
   */
  private fun swipe(on: String = "主播1", toLeft: Boolean = true) {
    val width = with(composeRule.density) { 320.dp.toPx() }
    val startX = if (toLeft) width * 0.8f else width * 0.2f
    val endX = if (toLeft) width * 0.2f else width * 0.8f
    composeRule.onNodeWithText(on).performTouchInput {
      down(Offset(startX, height * 0.5f))
      val steps = 12
      val step = (endX - startX) / steps
      repeat(steps) { index -> moveTo(Offset(startX + step * (index + 1), height * 0.5f)) }
      up()
    }
    composeRule.waitForIdle()
  }

  @Test
  fun `swiping left asks for the next platform`() {
    val actions = mutableListOf<UiAction>()
    compose(actions = actions)

    swipe()

    val selected = actions.filterIsInstance<UiAction.SelectPlatform>()
    assertEquals(Platform.HUYA, selected.firstOrNull()?.platform)
  }

  @Test
  fun `swiping right from the first page does not wrap around`() {
    val actions = mutableListOf<UiAction>()
    compose(actions = actions)

    swipe(toLeft = false)

    // 斗鱼 is the first page, so there is nothing to the left: the pager cannot move and no
    // platform change is emitted.
    assertTrue(
      "a right swipe on the first page must be inert, got $actions",
      actions.none { it is UiAction.SelectPlatform },
    )
  }

  @Test
  fun `a tab tap asks for that platform`() {
    val actions = mutableListOf<UiAction>()
    compose(actions = actions)

    composeRule.onNodeWithText("B站").performClick()

    val selected = actions.filterIsInstance<UiAction.SelectPlatform>()
    assertEquals(Platform.BILIBILI, selected.firstOrNull()?.platform)
  }

  /**
   * The pager opens on the platform the ViewModel restored, not on the first page.
   *
   * `lastPlatform` is persisted, so a user who left the app on B站 must not be handed 斗鱼 and
   * a "switch back" swipe on launch. The assertion is on the *tab*, not on the grid: this fixture
   * carries Douyu rooms regardless of platform (the ViewModel would have replaced them), so what
   * is actually being checked is that the pager — and therefore the selected tab — started on the
   * restored page rather than at index 0.
   */
  @Test
  fun `the pager opens on the restored platform`() {
    compose(state(platform = Platform.BILIBILI))

    composeRule.onNodeWithText("B站").assertIsSelected()
    composeRule.onNodeWithText("斗鱼").assertIsNotSelected()
  }

  @Test
  fun `the tab row is still reachable while a page is showing`() {
    compose()

    composeRule.onNodeWithText("斗鱼").assertIsDisplayed()
    composeRule.onNodeWithText("虎牙").assertIsDisplayed()
    composeRule.onNodeWithText("抖音").assertIsDisplayed()
    composeRule.onNodeWithText("B站").assertIsDisplayed()
  }

  @Test
  fun `the refresh control survives the pager`() {
    compose()

    composeRule.onNodeWithContentDescription("刷新").assertIsDisplayed()
  }

  /**
   * Every tab label is drawn at the width it asks for.
   *
   * The four tabs share `(320 - 8 - 48) / 4 = 66dp` before the tab's own padding, and "哔哩哔哩"
   * is four characters of it: it rendered as "哔哩", with no ellipsis and no error, because a
   * one-line `Text` clips by default. `Platform.shortName` is the fix ("B站"); this is what keeps
   * it fixed — and it would also notice the font-size increase that would break it again, which
   * no existence check can.
   *
   * The **layout result** is the evidence, not the node's bounds: a clipped label reports the
   * width it was allowed, so bounds are identical in both cases. `didOverflowWidth` is the one
   * value that separates "this label fits" from "this label was cut".
   */
  @Test
  fun `every tab label is drawn in full`() {
    compose()

    Platform.entries.forEach { platform ->
      val node = composeRule
        .onNodeWithText(platform.shortName, useUnmergedTree = true)
        .fetchSemanticsNode()
      val results = mutableListOf<TextLayoutResult>()
      node.config[SemanticsActions.GetTextLayoutResult]?.action?.invoke(results)

      val layout = results.firstOrNull()
      assertNotNull("no text layout result for ${platform.shortName}", layout)
      assertFalse(
        "${platform.shortName} was clipped: it wants " +
          "${layout!!.multiParagraph.intrinsics.maxIntrinsicWidth}px and was given " +
          "${layout.multiParagraph.width}px",
        layout.didOverflowWidth,
      )
    }
  }
}
