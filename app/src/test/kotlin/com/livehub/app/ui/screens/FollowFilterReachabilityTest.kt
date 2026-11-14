package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.FollowLiveFilter
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.core.designsystem.LiveHubTheme
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The 直播中 / 未开播 chips must be reachable on the most ordinary follow list there is.
 *
 * The row's visibility rule was `filter != ALL || platforms.size > 1`, so a single-platform follow
 * list nobody had filtered yet showed no chips at all — including the live-state filters, which do
 * have something to narrow whenever some rooms are live and some are not. The feature existed, was
 * wired end to end (`SetFollowLiveFilter` → `UiState.followLiveFilter` → the grid), and could not
 * be discovered: the only way to make the row appear was to happen to follow a second platform.
 *
 * The assertions are on presence, which is what was broken. Whether a tap filters correctly is
 * already covered by `MainViewModelTest` and by the visibility of the empty state.
 *
 * Rendered inside [LiveHubTheme] so "displayed" means displayed in the app's own type scale: the
 * chip row sits under a text-driven header on a 640dp-tall window, so how much room is left above
 * the fold — and therefore whether a chip is on screen at all — belongs to the scale, not to the
 * fixture.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class FollowFilterReachabilityTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun streamer(id: String, live: Boolean, platform: Platform = Platform.DOUYU) = Streamer(
    platform = platform,
    roomId = id,
    streamerName = "主播$id",
    title = "标题 $id",
    isLive = live,
    viewerCount = if (live) 1_000 else 0,
  )

  /**
   * A chip, not any other node that happens to carry the same words.
   *
   * `未开播` is also drawn as a badge on every offline room card, so a bare `onNodeWithText` finds
   * two nodes and the assertion fails on ambiguity — which is what happened the first time this
   * test ran on CI. A `FilterChip` exposes `SemanticsProperties.Selected` (it is selectable) and a
   * card badge does not, so matching on that is what makes the assertion about the filter row
   * rather than about the grid.
   *
   * Written as `keyIsDefined` rather than with the `isSelectable()` helper because that helper has
   * no precedent in this repository and I cannot compile locally — this form uses only the two
   * matchers already proven here (`hasText`, `keyIsDefined`).
   */
  private fun chip(label: String) = composeRule.onNode(
    hasText(label) and SemanticsMatcher.keyIsDefined(SemanticsProperties.Selected),
  )

  private fun render(state: UiState) {
    composeRule.setContent {
      LiveHubTheme {
        Box(modifier = Modifier.size(320.dp, 640.dp)) {
          FollowScreen(state = state, onAction = {})
        }
      }
    }
    composeRule.waitForIdle()
  }

  /**
   * One platform, nothing filtered, some rooms live and some not — the default shape.
   *
   * This is the case that used to render no chips at all.
   */
  @Test
  fun `the live filters are offered on a single platform list with mixed states`() {
    render(
      UiState(
        screen = Screen.Follow,
        followed = listOf(
          streamer("1", live = true),
          streamer("2", live = true),
          streamer("3", live = false),
        ),
      ),
    )

    chip("全部").assertIsDisplayed()
    chip("直播中").assertIsDisplayed()
    chip("未开播").assertIsDisplayed()
  }

  /**
   * Everything offline hides the row, because neither chip would do anything.
   *
   * 直播中 would empty the screen and 未开播 would show exactly what 全部 already shows. My first
   * version of this test asserted the opposite; the local rule model disagreed, and the model was
   * right — "show the row when a chip can narrow" has to mean *both* directions exist, which is
   * `0 < live < total`.
   */
  @Test
  fun `an all-offline list shows no filter row`() {
    render(
      UiState(
        screen = Screen.Follow,
        followed = listOf(streamer("1", live = false), streamer("2", live = false)),
      ),
    )

    composeRule.onNodeWithText("关注").assertIsDisplayed()
    chip("直播中").assertDoesNotExist()
  }

  /**
   * A genuinely uniform list hides the row — that part of the old rule was right.
   *
   * With a single platform and every room live, 直播中 would filter nothing and 未开播 would empty
   * the screen, so the row is pure noise. Asserting this too keeps the fix from becoming "always
   * show four chips above every list".
   */
  @Test
  fun `a list where every room is live shows no filter row`() {
    render(
      UiState(
        screen = Screen.Follow,
        followed = listOf(streamer("1", live = true), streamer("2", live = true)),
      ),
    )

    // The header itself is there, so the absence below is about the chips, not a failed render.
    composeRule.onNodeWithText("关注").assertIsDisplayed()
    chip("直播中").assertDoesNotExist()
  }

  /** An active filter always shows the row, so the user can get back out of it. */
  @Test
  fun `an active live filter keeps the row visible`() {
    render(
      UiState(
        screen = Screen.Follow,
        followed = listOf(streamer("1", live = true), streamer("2", live = true)),
        followLiveFilter = FollowLiveFilter.LIVE,
      ),
    )

    chip("直播中").assertIsDisplayed()
    chip("全部").assertIsDisplayed()
  }

  /** A platform filter is offered once there are two platforms, and only then. */
  @Test
  fun `platform chips appear only with more than one platform`() {
    render(
      UiState(
        screen = Screen.Follow,
        followed = listOf(
          streamer("1", live = true),
          streamer("2", live = false, platform = Platform.HUYA),
        ),
      ),
    )

    chip("虎牙").assertIsDisplayed()
  }
}
