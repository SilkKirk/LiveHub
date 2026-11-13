package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
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
   * Every tab label is short enough for the space a tab gets.
   *
   * The four tabs share `(320 - 8 - 48) / 4 = 66dp`, and a `titleMedium` CJK glyph is 16dp, so the
   * budget is two characters minus the tab's own padding. "哔哩哔哩" needed four and was drawn as
   * "哔哩" — no ellipsis, no error, because a one-line `Text` clips by default. `Platform.shortName`
   * is the fix; this is what stops a new platform from arriving with a long one.
   *
   * The assertion is on the *name*, not on the render, and that is not laziness: Robolectric stubs
   * font metrics, so a two-glyph label reports an intrinsic width of 2.5px and
   * `TextLayoutResult.didOverflowWidth` is true for a label that visibly fits. Neither node bounds
   * nor the layout result can tell "fits" from "clipped" in this environment — which is also why
   * every density test in this repo asserts *positions* rather than widths. The render itself is
   * the screenshot `01-home.png`, where the fourth tab reads B站 in full on real fonts.
   */
  @Test
  fun `every platform tab label fits two characters`() {
    Platform.entries.forEach { platform ->
      assertTrue(
        "${platform.id}'s short name is ${platform.shortName.length} characters " +
          "(\"${platform.shortName}\"), and a tab fits two",
        platform.shortName.length <= 2,
      )
    }
  }
}
