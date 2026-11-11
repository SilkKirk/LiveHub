package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * How many room tiles fit in a row, at the two widths that matter.
 *
 * The grids used `GridCells.Fixed(2)`, so a tablet got two tiles stretched across the whole screen —
 * the same 160dp-ish tile the phone gets, just with a lot of empty space around it, and a 640dp
 * picture band that makes the covers look like banners. They now use
 * `GridCells.Adaptive(CardMinWidth)`.
 *
 * The number that had to be *derived* rather than guessed is [com.livehub.app.ui.components.CardMinWidth]:
 * at the 320dp phone width the grids have 296dp of usable space (12dp padding each side), and two
 * columns need `2 * minSize + 10 <= 296`, so `minSize` must be at most 143dp. The first value tried
 * was 156dp, which silently collapsed the phone grid to **one** column — the assertion below at
 * 320dp is what would have caught it, and it is here precisely because nothing else would.
 *
 * There is no wide *screenshot*: `ScreenshotTest` renders on a 320×640 emulator, and changing the
 * display size mid-run is a device-global action that round 35 established wrecks the other 39
 * renders. Column counts are geometry, so they are asserted rather than photographed.
 */
@RunWith(RobolectricTestRunner::class)
class GridColumnCountTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val categories = listOf(
    Category.aggregate(Platform.DOUYU, "全部直播"),
    Category(Platform.DOUYU, "1", "英雄联盟", "1", "网游", sortOrder = 0),
  )

  private fun rooms(count: Int) = (1..count).map { index ->
    Streamer(
      platform = Platform.DOUYU,
      roomId = "$index",
      streamerName = "主播$index",
      title = "标题 $index",
      isLive = true,
    )
  }

  private fun state() = UiState(
    screen = Screen.Home,
    platform = Platform.DOUYU,
    categories = categories,
    selectedCategory = categories.first(),
    rooms = rooms(12),
  )

  private fun render(widthDp: Int, heightDp: Int) {
    composeRule.setContent {
      Box(modifier = Modifier.size(widthDp.dp, heightDp.dp)) {
        HomeScreen(state = state(), onAction = {})
      }
    }
    composeRule.waitForIdle()
  }

  /** The y coordinate of a room title, in dp, for grouping cards into rows. */
  private fun topOf(title: String): Float =
    composeRule.onNodeWithText(title, useUnmergedTree = true)
      .fetchSemanticsNode()
      .boundsInRoot
      .top

  private fun isDisplayed(title: String): Boolean =
    composeRule.onAllNodesWithText(title, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

  /**
   * Two columns on the phone, which is what every existing layout test and screenshot assumes.
   *
   * Rows are identified by "two titles share a y coordinate": tiles in the same grid row are laid
   * out at the same top because they all start at the top of their cell.
   */
  @Test
  @Config(sdk = [34], qualifiers = "w320dp-h640dp")
  fun `a phone shows two columns`() {
    render(320, 640)

    // 1 and 2 share a row; 3 starts a new one.
    val first = topOf("标题 1")
    val second = topOf("标题 2")
    assertTrueSameRow("标题 1", first, "标题 2", second)

    if (isDisplayed("标题 3")) {
      assertTrueNewRow("标题 3", topOf("标题 3"), first)
    }
  }

  /**
   * Four columns on a 640dp-wide window.
   *
   * `Adaptive(140.dp)` over 616dp of usable space gives four: `4 * 140 + 3 * 10 = 590 <= 616`, and
   * five would need `5 * 140 + 4 * 10 = 740`.
   */
  @Test
  @Config(sdk = [34], qualifiers = "w640dp-h480dp")
  fun `a tablet shows four columns`() {
    render(640, 480)

    val first = topOf("标题 1")
    // 2, 3 and 4 must all sit on the first tile's line.
    listOf("标题 2", "标题 3", "标题 4").forEach { title ->
      assertTrueSameRow(title, topOf(title), "标题 1", first)
    }
    // And the fifth must wrap to a second row — otherwise the grid is not wrapping at all and the
    // assertions above would pass on a single row of twelve.
    if (isDisplayed("标题 5")) {
      assertTrueNewRow("标题 5", topOf("标题 5"), first)
    }
  }

  /** Legacy screens where the wide layout must not change the small one. */
  @Test
  @Config(sdk = [34], qualifiers = "w360dp-h640dp")
  fun `a narrow-but-taller phone still shows two columns`() {
    render(360, 640)

    // 360dp leaves 336dp: two columns need 290, three would need 440.
    assertTrueSameRow("标题 1", topOf("标题 1"), "标题 2", topOf("标题 2"))
  }

  private fun assertTrueSameRow(aLabel: String, aTop: Float, bLabel: String, bTop: Float) {
    org.junit.Assert.assertTrue(
      "$aLabel and $bLabel must share a row ($aTop vs $bTop)",
      kotlin.math.abs(aTop - bTop) < 4f,
    )
  }

  private fun assertTrueNewRow(label: String, top: Float, firstRowTop: Float) {
    org.junit.Assert.assertTrue(
      "$label must start a new row ($top vs $firstRowTop)",
      top > firstRowTop + 8f,
    )
  }
}
