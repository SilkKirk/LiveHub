package com.livehub.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.livehub.core.designsystem.LiveHubTheme
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 特别关注 strip at 2.0×, the top of the system font slider.
 *
 * Every render in `ScreenshotTest` stopped at 1.5× until round 114, and 2.0× is one drag further —
 * an ordinary accessibility setting, not an exotic one. The strip draws each name under a ring in a
 * slot that was a fixed **86dp**. `labelSmall` is 16dp of line height at 1.0× and 24dp at 1.5×, so
 * 56 + 6 + line fitted by luck (78 and 86); at **2.0×** the line is 32dp, the fixed slot left 24dp
 * for it, and the name was clipped rather than ellipsized — on the one row whose whole job is
 * showing who is streaming.
 *
 * The assertion is about the geometry that guarantees the outcome rather than about pixels: a name
 * node that was clipped reports the 24dp leftover, one that fits reports its own 32dp line.
 *
 * The dialog half of the same review (`MatchingCodeDialog` is a fixed *width* surface with no
 * height limit) is **not** covered here on purpose. Making its body scroll would change what
 * `SyncDialogSpaceTest` measures — that test's premise is "the body does not scroll, so the last row
 * must sit inside the window" — and an earlier attempt at exactly that change is recorded in its
 * header as having made Robolectric spin for 60 seconds without idling. It needs its own round.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HugeFontLayoutTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val room = Streamer(
    platform = Platform.DOUYU,
    roomId = "1",
    streamerName = "一条小团团",
    title = "今天继续冲分",
    isLive = true,
  )

  /** The same `Density(density, fontScale)` trick `ScreenshotTest` uses, applied to one test. */
  private fun render(fontScale: Float, content: @Composable () -> Unit) {
    composeRule.setContent {
      val density = LocalDensity.current
      LiveHubTheme {
        CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale)) {
          Box(modifier = Modifier.size(320.dp, 640.dp)) {
            SpecialFollowRow(
              streamers = listOf(room),
              onClick = {},
              onReorder = { _, _ -> },
              onAdd = {},
            )
          }
        }
      }
    }
    composeRule.waitForIdle()
  }

  /**
   * The regression: the name was clipped to the 24dp the 86dp slot left over.
   *
   * `labelSmall` is 11sp/16sp, so one line at 2.0× is 32dp. Asserting 31dp leaves the `sp → dp → px`
   * rounding room while still failing for anything that clips the line.
   */
  @Test
  fun `the special follow slot keeps a full name line at 2x`() {
    render(fontScale = 2f)

    composeRule.onNodeWithText("一条小团团", useUnmergedTree = true)
      .assertHeightIsAtLeast(31.dp)
  }
}
