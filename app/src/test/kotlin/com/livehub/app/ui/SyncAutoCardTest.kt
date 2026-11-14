package com.livehub.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.screens.AUTO_SYNC_SWITCH_TAG
import com.livehub.app.ui.screens.SyncScreen
import com.livehub.core.designsystem.LiveHubTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 自动同步 card on the sync screen: reachable, honest about the setting, and wired to an action.
 *
 * The card was added in round 87 with no test at all, and it sits *after* 同步内容 — i.e. below the
 * fold on the smallest phone the app targets. That placement was a deliberate choice (moving it
 * above 同步内容 would shift the 立即同步/关联设备 row that `SyncDialogSpaceTest` clicks, and a
 * `LazyColumn` does not compose what is off screen), so whether the card is actually reachable is
 * exactly what needs proving rather than assuming.
 *
 * The assertions measure instead of counting nodes: `performScrollToNode` proves the user can get
 * there, and the bounds comparisons prove the card — not just its first line — ends up inside the
 * window. `assertIsDisplayed()` alone would pass with the subtitle and the switch parked under the
 * bottom edge.
 *
 * The second half of the file covers the row *above* that card — 立即同步 / 关联设备 / 刷新 — which
 * is where a 1.5× text scale used to break two controls at once (`31-sync-large-font`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class SyncAutoCardTest {

  @get:Rule
  val composeRule = createComposeRule()

  /**
   * The copy the card has to show, written out rather than matched loosely: the card's whole job is
   * to explain a trade in one sentence, so the sentence itself is what the test pins.
   */
  private val subtitle = "应用打开时与已配对设备同步一次；服务端只在前台运行。"

  /**
   * A sync page with nothing paired and a code that has no time left on it.
   *
   * `pairingCodeSecondsLeft = 0` is load-bearing: `SyncScreen` derives the countdown in a
   * `LaunchedEffect` that loops while there is time left, which never lets the composition idle.
   */
  private fun state(autoSync: Boolean) = UiState(
    screen = Screen.Sync,
    deviceName = "Pixel 7",
    pairingCode = "482913",
    pairingCodeSecondsLeft = 0,
    autoSyncEnabled = autoSync,
  )

  /**
   * Renders the page in a 320×640 box — the smallest phone the app targets — and tags that box, so
   * the bounds checks below measure against the page rather than against whatever size the test
   * window happens to be.
   *
   * [fontScale] mirrors the system accessibility text multiplier the way `ScreenshotTest` applies it:
   * the *font* scale is replaced while the pixel density stays, so dp sizes (and therefore the page)
   * are unchanged and only the text grows.
   */
  private fun render(
    state: UiState,
    fontScale: Float = 1f,
    onAction: (UiAction) -> Unit = {},
  ) {
    composeRule.setContent {
      val density = LocalDensity.current
      LiveHubTheme {
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
          Box(modifier = Modifier.size(320.dp, 640.dp).testTag(VIEWPORT_TAG)) {
            SyncScreen(state = state, onAction = onAction)
          }
        }
      }
    }
    composeRule.waitForIdle()
  }

  /**
   * Scrolls to the deepest part of the card, the way a user would.
   *
   * The *subtitle* rather than the title: scrolling until the title is visible would leave the
   * second line and the switch clipped by the bottom edge, which is the failure mode the bounds
   * assertions below exist to catch.
   */
  private fun scrollToAutoSyncCard() {
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(subtitle))
    composeRule.waitForIdle()
  }

  @Test
  fun `the auto sync card is reachable and fits the screen`() {
    render(state(autoSync = true))
    scrollToAutoSyncCard()

    composeRule.onNodeWithText("自动同步").assertIsDisplayed()
    composeRule.onNodeWithText(subtitle).assertIsDisplayed()
    composeRule.onNodeWithTag(AUTO_SYNC_SWITCH_TAG).assertIsDisplayed()

    val page = composeRule.onNodeWithTag(VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot
    val title = composeRule.onNodeWithText("自动同步").fetchSemanticsNode().boundsInRoot
    val switch = composeRule.onNodeWithTag(AUTO_SYNC_SWITCH_TAG).fetchSemanticsNode().boundsInRoot

    assertTrue("the card is above the top edge: $title vs $page", title.top >= page.top)
    assertTrue("the card is below the bottom edge: $title vs $page", title.bottom <= page.bottom)
    assertTrue("the switch is above the top edge: $switch vs $page", switch.top >= page.top)
    assertTrue(
      "the switch is below the bottom edge: $switch vs $page",
      switch.bottom <= page.bottom,
    )
  }

  /** The switch renders the stored setting, and a tap is reported as the opposite of it. */
  @Test
  fun `the switch shows the setting and reports a tap`() {
    val actions = mutableListOf<UiAction>()
    render(state(autoSync = true), onAction = { actions += it })
    scrollToAutoSyncCard()

    composeRule.onNodeWithTag(AUTO_SYNC_SWITCH_TAG).assertIsOn().performClick()
    composeRule.waitForIdle()

    assertEquals(
      "tapping a switched-on switch must ask for it to be switched off",
      listOf<UiAction>(UiAction.SetAutoSync(false)),
      actions,
    )
  }

  /** The other direction, which is the state a user who turned it off comes back to. */
  @Test
  fun `the switch is off when automatic sync is off`() {
    val actions = mutableListOf<UiAction>()
    render(state(autoSync = false), onAction = { actions += it })
    scrollToAutoSyncCard()

    composeRule.onNodeWithTag(AUTO_SYNC_SWITCH_TAG).assertIsOff().performClick()
    composeRule.waitForIdle()

    assertEquals(
      "tapping a switched-off switch must ask for it to be switched on",
      listOf<UiAction>(UiAction.SetAutoSync(true)),
      actions,
    )
  }

  // ---------------------------------------------------------------- the action row ---

  private fun scrollToActions() {
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("立即同步"))
    composeRule.waitForIdle()
  }

  /**
   * Both labels on one line, both buttons on screen, and the icon-only button at its own size.
   *
   * The label comparison is between two nodes of the same style in the same row: a squeezed label
   * stacks one character per line and ends up several lines tall while its neighbour stays at one,
   * so the heights only agree while the text is laid out the way it is meant to be. The `+ 1` is
   * rounding, not tolerance for a second line — one line at 14sp and 1.5× is ~30dp.
   *
   * The floor for the icon button is 40dp, its own box, rather than the 48dp tap target: Material
   * adds the rest through its interactive-size enforcement, which is a property of the theme (it can
   * be switched off through a `CompositionLocal`) and not of this screen. 40dp is still four times
   * the few dp the squeezed button had, so the assertion fails on the defect and passes on any
   * variant of the fix.
   */
  private fun assertActionRowUsable() {
    composeRule.onNodeWithText("立即同步").assertIsDisplayed()
    composeRule.onNodeWithText("关联设备").assertIsDisplayed()

    val syncLabel = composeRule.onNodeWithText("立即同步", useUnmergedTree = true)
      .fetchSemanticsNode().size.height
    val pairLabel = composeRule.onNodeWithText("关联设备", useUnmergedTree = true)
      .fetchSemanticsNode().size.height
    assertTrue(
      "「关联设备」的标签竖排了：它高 ${pairLabel}px，" +
        "同一行的「立即同步」只有 ${syncLabel}px",
      pairLabel <= syncLabel + 1,
    )

    val icon = composeRule.onNodeWithContentDescription("刷新").fetchSemanticsNode().size
    val floor = with(composeRule.density) { 40.dp.roundToPx() }
    assertTrue(
      "刷新图标的按钮只有 ${icon.width}×${icon.height}px，低于它自己的 ${floor}px（40dp）",
      icon.width >= floor && icon.height >= floor,
    )
  }

  /**
   * The row as it renders today, at 1.0×.
   *
   * Worth pinning at the normal text size too, because the row is already over budget there: by
   * Material's tokens two icon-and-label buttons plus a 48dp icon button need about 332dp and this
   * 320dp screen leaves 288dp. The old `Row` squeezed the refresh icon to a few dp whenever it ran
   * out of width, so the assertion below catches at 1.0× the same squeeze the 1.5× test catches at
   * the larger scale.
   */
  @Test
  fun `the three actions are usable at the normal text size`() {
    val actions = mutableListOf<UiAction>()
    render(state(autoSync = true), onAction = { actions += it })
    scrollToActions()
    assertActionRowUsable()

    composeRule.onNodeWithContentDescription("刷新").performClick()
    composeRule.waitForIdle()

    assertEquals(
      "点刷新图标必须发出重新扫描的指令",
      listOf<UiAction>(UiAction.RefreshPeers),
      actions,
    )
  }

  /**
   * The same row at 1.5×, which is the render that showed the defect: 「关联设备」came out as
   * 关/联/设/备 down the side of a one-character-wide button and the refresh icon was squeezed to
   * nothing. A wrapping row puts the overflow on a second line, so both labels stay whole and the
   * icon-only button keeps its own box.
   */
  @Test
  fun `the three actions are usable at 1_5x text`() {
    render(state(autoSync = true), fontScale = 1.5f)
    scrollToActions()
    assertActionRowUsable()
  }

  private companion object {
    /** The page's own box, so "inside the window" means inside what the screen was given. */
    const val VIEWPORT_TAG = "syncAutoCardViewport"
  }
}
