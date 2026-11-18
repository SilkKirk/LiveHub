package com.livehub.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.livehub.core.designsystem.LiveHubTheme
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * No click target in the app may be smaller than 48dp.
 *
 * ## Why this test exists at all
 *
 * The 48dp minimum has been broken here repeatedly, always in a way that looked fixed: `size(32.dp)`
 * on an `IconButton` (round 61), then `size(48.dp).padding(8.dp)` — a 48dp node around a 32dp
 * clickable area, because `IconButton` applies Material's minimum *inside* the caller's modifier
 * (round 65). Neither was caught by a test; both were caught by reading the code.
 *
 * The guard meant to cover the third occurrence was `lint { error += "TouchTargetSizeCheck" }`, and
 * it was **fiction**: that string is not a Gradle lint issue id, so lint only ever answered
 * `UnknownIssueId` and enforced nothing. (It names an analyzer in Android Studio's visual lint and
 * in Google's Accessibility Test Framework — the tools that actually print "Touch target size too
 * small". Compose ships no such lint rule at all.) This test is the replacement.
 *
 * ## What is measured
 *
 * `touchBoundsInRoot` of every node carrying a click action — what a finger can actually hit, not
 * where the node happens to be laid out. That is the property the two historical bugs broke, and the
 * one accessibility is about.
 *
 * The first version of this test measured the *layout* bound instead, and it was wrong in a way
 * worth recording: Material's `FilterChip` lays out 36dp tall and its `Switch` 32dp **by design**,
 * both with a 48dp touch target. Measuring layout therefore flagged every chip and switch in the app
 * — 12 false positives across 搜索, 设置 and 同步 — and would have pushed toward either an allowlist
 * (which makes the guard decorative again) or inflating controls the design had settled on.
 *
 * ## What it found on the way in
 *
 * Six `IconButton`s carried no explicit size, because Material's default is a 40dp state layer:
 * `刷新` on 首页, `清空` and `搜索` on 搜索, and `刷新` / `解除关联` / `重新扫描` on 同步. They now carry
 * `Modifier.size(48.dp)` like the other eight in the project, so the app has one button size rather
 * than two.
 *
 * ## Scope
 *
 * The five dock screens, each rendered through [LiveHubRoot] so what is measured is the shell a
 * user actually gets. The player screen is not included: it composes an `ExoPlayer`, and its
 * buttons are pinned by `PlayerChromeTest` instead. That is a real gap rather than a judgement —
 * noted here so the next person extends this rather than assuming the player is covered.
 *
 * ## The last test is the important one
 *
 * A "no violations" assertion is worthless if the detector silently finds nothing: a scan that never
 * fires looks identical to a clean app. [a too small target is reported] renders one deliberately
 * broken button and requires the same scan to name it, so the suite fails if the measurement ever
 * stops measuring.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class TouchTargetSizeTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val rooms = listOf(
    Streamer(
      platform = Platform.DOUYU,
      roomId = "1",
      streamerName = "一条小团团",
      title = "今天继续冲分",
      viewerCount = 128_500,
      isLive = true,
    ),
    Streamer(
      platform = Platform.DOUYU,
      roomId = "2",
      streamerName = "张大仙",
      title = "王者荣耀上分",
      viewerCount = 52_000,
      isLive = true,
    ),
  )

  @Test
  fun `home has no undersized click target`() = assertShellHasNoSmallTargets(Screen.Home)

  @Test
  fun `search has no undersized click target`() = assertShellHasNoSmallTargets(Screen.Search)

  @Test
  fun `follow has no undersized click target`() = assertShellHasNoSmallTargets(Screen.Follow)

  @Test
  fun `sync has no undersized click target`() = assertShellHasNoSmallTargets(Screen.Sync)

  @Test
  fun `settings has no undersized click target`() = assertShellHasNoSmallTargets(Screen.Settings)

  /**
   * Proves the scan can fail.
   *
   * `size(32.dp)` on an `IconButton` is the round-61 shape verbatim. If this ever passes while the
   * button really is 32dp, every assertion above has stopped measuring anything.
   */
  @Test
  fun `a too small target is reported`() {
    composeRule.setContent {
      LiveHubTheme {
        IconButton(onClick = {}, modifier = Modifier.size(32.dp)) {
          Icon(imageVector = Icons.Filled.Close, contentDescription = "过小的按钮")
        }
      }
    }
    composeRule.waitForIdle()

    val violations = scanClickTargets()
    assertFalse(
      "a 32dp button must be reported as a violation, but the scan found none",
      violations.isEmpty(),
    )
    assertTrue(
      "the report must name the offending button, was:\n" + violations.joinToString("\n"),
      violations.any { it.contains("过小的按钮") },
    )
  }

  /** Renders [screen] inside the real shell and asserts every click target clears the minimum. */
  private fun assertShellHasNoSmallTargets(screen: Screen) {
    val state = UiState(
      screen = screen,
      platform = Platform.DOUYU,
      rooms = rooms,
      // Populated so the follow and search screens draw cards rather than an empty state; an empty
      // screen would have almost no click targets in it and would pass while proving nothing.
      followed = rooms,
      searchResults = rooms,
      searchQuery = "团团",
      lastSearchQuery = "团团",
    )
    composeRule.setContent {
      LiveHubTheme {
        Box(modifier = Modifier.size(411.dp, 891.dp)) {
          LiveHubRoot(state = state, onAction = {})
        }
      }
    }
    composeRule.waitForIdle()

    val violations = scanClickTargets()
    assertTrue(
      "${screen.name}: these click targets are under ${MIN_TOUCH_TARGET_DP.toInt()}dp:\n" +
        violations.joinToString("\n"),
      violations.isEmpty(),
    )
  }

  /**
   * Every clickable node whose *touch* area is smaller than [MIN_TOUCH_TARGET_DP] on either side.
   *
   * ## Why the touch bound and not the layout bound
   *
   * `boundsInRoot` is where the node is laid out; `touchBoundsInRoot` is what a finger can hit. The
   * second one is the property accessibility is about, and it is the one the two historical bugs
   * actually broke — `size(32.dp)` shrank both, and `size(48.dp).padding(8.dp)` kept the layout at
   * 48 while the clickable inside the padding was 32.
   *
   * Measuring the layout bound instead was the first version of this test, and it produced false
   * positives on components that are *deliberately* shorter than 48dp while their touch target is
   * 48dp: Material's `FilterChip` lays out 36dp tall and its `Switch` 32dp, both by design. Failing
   * those would have meant either weakening the assertion with an allowlist or inflating controls
   * the design had already decided on — neither of which is what this guard is for.
   *
   * Both numbers are reported so a failure says whether the layout or only the touch area is small.
   */
  private fun scanClickTargets(): List<String> =
    composeRule.onAllNodes(hasClickAction())
      .fetchSemanticsNodes()
      .mapNotNull { node ->
        val touch = dpSize(node.touchBoundsInRoot)
        if (touch.first >= MIN_TOUCH_TARGET_DP && touch.second >= MIN_TOUCH_TARGET_DP) {
          null
        } else {
          val layout = dpSize(node.boundsInRoot)
          "touch ${touch.first.roundToInt()}x${touch.second.roundToInt()}dp" +
            " (layout ${layout.first.roundToInt()}x${layout.second.roundToInt()}dp)" +
            "  ${describe(node)}"
        }
      }

  /** A bounds rectangle in dp, as `(width, height)`. */
  private fun dpSize(rect: Rect): Pair<Float, Float> =
    with(composeRule.density) { rect.width.toDp().value to rect.height.toDp().value }

  /** Best-effort human label for a node: its text, its content description, or neither. */
  private fun describe(node: SemanticsNode): String {
    val text = node.config.getOrNull(SemanticsProperties.Text)
      ?.joinToString(" ") { it.text }
      ?.takeIf { it.isNotBlank() }
    val description = node.config.getOrNull(SemanticsProperties.ContentDescription)
      ?.joinToString(" ")
      ?.takeIf { it.isNotBlank() }
    val label = listOfNotNull(
      text?.let { "text=\"$it\"" },
      description?.let { "desc=\"$it\"" },
    ).joinToString(" ")
    return if (label.isEmpty()) "(no text)" else label
  }

  private companion object {
    /** Material's minimum interactive component size, in dp. */
    const val MIN_TOUCH_TARGET_DP = 48f
  }
}
