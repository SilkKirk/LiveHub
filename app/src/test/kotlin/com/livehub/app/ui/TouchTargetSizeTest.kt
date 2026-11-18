package com.livehub.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.ui.Modifier
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
 * No click target in the app shell may be smaller than 48dp.
 *
 * ## Why this test exists at all
 *
 * The 48dp minimum has been broken twice here, both times in a way that looked fixed: `size(32.dp)`
 * on an `IconButton` (round 61), and then `size(48.dp).padding(8.dp)` — a 48dp node around a 32dp
 * clickable area, because `IconButton` applies Material's minimum *inside* the caller's modifier
 * (round 65). Neither was caught by a test; both were caught by reading the code.
 *
 * The guard that was supposed to cover the third occurrence was `lint { error +=
 * "TouchTargetSizeCheck" }`, and it was **fiction**: that string is not a Gradle lint issue id, so
 * lint merely answered `UnknownIssueId` and enforced nothing. (It names an analyzer in Android
 * Studio's visual lint and in Google's Accessibility Test Framework — the things that actually
 * print "Touch target size too small". Compose ships no such lint rule at all.) This test is the
 * replacement, and unlike the lint line it fails when the property is violated.
 *
 * ## What is measured
 *
 * `boundsInRoot` of every node that carries a click action. That is the *layout* bound, which is
 * exactly what the two historical bugs shrank — an expanded touch area does not rescue a target
 * whose layout is 32dp, because the padding that shrank it also shrank what the finger can reach.
 *
 * ## The second test is the important one
 *
 * A screen-wide "no violations" assertion is worthless if the detector silently finds nothing —
 * and a detector that never fires looks identical to a clean app. [a too small target is reported]
 * renders one deliberately broken button and requires the same scan to name it, so the suite fails
 * if the measurement ever stops working.
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

  private fun shellState() = UiState(
    screen = Screen.Home,
    platform = Platform.DOUYU,
    rooms = rooms,
  )

  @Test
  fun `no click target in the app shell is under 48dp`() {
    composeRule.setContent {
      LiveHubTheme {
        Box(modifier = Modifier.size(411.dp, 891.dp)) {
          LiveHubRoot(state = shellState(), onAction = {})
        }
      }
    }
    composeRule.waitForIdle()

    val violations = scanClickTargets()
    assertTrue(
      "these click targets are under ${MIN_TOUCH_TARGET_DP.toInt()}dp:\n" +
        violations.joinToString("\n"),
      violations.isEmpty(),
    )
  }

  /**
   * Proves the scan can fail.
   *
   * `size(32.dp)` on an `IconButton` is the round-61 shape verbatim. If this test ever passes while
   * the button really is 32dp, the measurement above has stopped measuring anything.
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

  /**
   * Every clickable node whose layout is smaller than [MIN_TOUCH_TARGET_DP] on either side.
   *
   * Described rather than returned as nodes so the message names what to go and fix; a bare count
   * would leave the next reader diffing the whole screen.
   */
  private fun scanClickTargets(): List<String> =
    composeRule.onAllNodes(hasClickAction())
      .fetchSemanticsNodes()
      .mapNotNull { node ->
        val bounds = node.boundsInRoot
        val width = with(composeRule.density) { bounds.width.toDp().value }
        val height = with(composeRule.density) { bounds.height.toDp().value }
        if (width >= MIN_TOUCH_TARGET_DP && height >= MIN_TOUCH_TARGET_DP) {
          null
        } else {
          "${width.roundToInt()}x${height.roundToInt()}dp  ${describe(node)}"
        }
      }

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
