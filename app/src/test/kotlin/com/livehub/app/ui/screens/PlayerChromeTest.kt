package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.designsystem.LiveHubTheme
import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The full-screen chrome has to be reachable again after it auto-hides.
 *
 * The full-screen bar and the side buttons hide themselves three seconds after playback
 * starts, and the only gesture layer was declared `if (!fullscreen)`: once the chrome was
 * gone, tapping the picture did nothing, so 播放设置, 刷新 and 退出全屏 could not be reached
 * for the rest of the session. The system back gesture was the only way out.
 *
 * The clock is frozen and advanced by hand for the same reason the screenshot harness does
 * it: `waitForIdle` with the automatic clock would run the real three-second timer and the
 * danmaku animation to completion, and the state under test would never be observed.
 *
 * Rendered inside [LiveHubTheme] because the chrome is sized by the type scale: the action pills
 * are `labelLarge` in a 48dp ring, the notices and danmaku rows are `bodySmall`, and the assertions
 * below compare the y-positions of exactly those controls against the picture band. Laid out under
 * Material's default scale they describe a player the app does not draw.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp")
class PlayerChromeTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val room = Streamer(
    platform = Platform.DOUYU,
    roomId = "1",
    streamerName = "一条小团团",
    title = "今天继续冲分",
    viewerCount = 128_500,
    isLive = true,
  )

  private val variant = StreamVariant(
    qualityLabel = "原画",
    url = "https://example.com/a.flv",
    lineName = "ws-h5",
    qualityRank = 0,
    qualityCode = 0,
  )

  /**
   * A player state.
   *
   * [playing] is false for the resolve-failure case: a room that still has a variant hands
   * the picture to the video surface, so the error branch — and its 重试 button — is only
   * reachable once the variant is gone.
   */
  private fun uiState(error: String? = null, playing: Boolean = true) = UiState(
    screen = com.livehub.app.ui.Screen.Player,
    platform = Platform.DOUYU,
    categories = listOf(Category(Platform.DOUYU, "86", "英雄联盟", "1", "网游")),
    room = room,
    variants = if (playing) listOf(variant) else emptyList(),
    selectedVariant = variant.takeIf { playing },
    lines = listOf("ws-h5", "ali"),
    selectedLine = "ws-h5",
    error = error,
  )

  /**
   * Renders the player body with a stand-in surface and a frozen clock.
   *
   * The stub keeps the real Media3 player — and therefore a decoder — out of the test; it is
   * the same substitution the screenshot harness makes.
   */
  private fun render(
    state: UiState = uiState(),
    fullscreen: Boolean = false,
    compact: Boolean = false,
    landscapeWindow: Boolean = false,
    videoAspect: Float? = null,
    onAction: (UiAction) -> Unit = {},
  ) {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      LiveHubTheme {
        PlayerContent(
          state = state,
          onAction = onAction,
          fullscreen = fullscreen,
          onFullscreenChange = {},
          onOpenSettings = {},
          videoSurface = { surfaceModifier -> Box(surfaceModifier.fillMaxSize()) },
          compact = compact,
          landscapeWindow = landscapeWindow,
          videoAspectHint = videoAspect,
        )
      }
    }
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.waitForIdle()
  }

  private fun count(description: String): Int =
    composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size

  private fun chromeVisible(): Boolean = count("播放设置") > 0

  /** Lets the auto-hide timer (3 s) expire on the frozen clock. */
  private fun hideChrome() {
    composeRule.mainClock.advanceTimeBy(3_500L)
    composeRule.waitForIdle()
    assertFalse("the chrome is supposed to hide itself", chromeVisible())
  }

  /**
   * Taps a point of the video surface.
   *
   * The click is followed by 500 ms of virtual time: `detectTapGestures` is given an
   * `onDoubleTap`, so it holds every tap until the double-tap timeout expires before it calls
   * `onTap` — on a frozen clock that timeout never elapses and the tap is silently dropped.
   * 500 ms is well inside the 3 s after which the chrome hides itself again, so the assertion
   * that follows still sees what the tap produced.
   *
   * [y] is explicit because the portrait video is a 16:9 band at the top of the window: the
   * centre of the *window* there is the danmaku list, not the picture, so a tap at the centre
   * would test nothing.
   */
  private fun tapPicture(y: Float) {
    composeRule.onRoot().performTouchInput {
      click(Offset(center.x, y))
    }
    composeRule.mainClock.advanceTimeBy(500L)
    composeRule.waitForIdle()
  }

  @Test
  fun `tapping the picture in full screen brings the hidden chrome back`() {
    render(fullscreen = true)

    hideChrome()
    assertEquals("播放设置 must be gone with the chrome", 0, count("播放设置"))

    tapPicture(y = 300f)

    assertEquals("the side controls come back with the tap", 1, count("播放设置"))
    assertTrue(
      "the exit control comes back too, got ${count("退出全屏")}",
      count("退出全屏") >= 1,
    )
  }

  /**
   * The portrait layout already worked; this keeps the shared gesture layer from regressing
   * it while the full-screen branch is fixed.
   */
  @Test
  fun `tapping the picture in portrait toggles the chrome`() {
    render(fullscreen = false)

    assertTrue("the portrait chrome starts visible", chromeVisible())

    // Inside the 16:9 picture, which sits at the top of the window in portrait.
    tapPicture(y = 80f)
    assertEquals("a tap hides it", 0, count("播放设置"))

    tapPicture(y = 80f)
    assertEquals("a second tap shows it again", 1, count("播放设置"))
  }

  /**
   * A failed resolve offers 重试, and 重试 asks for the room again.
   *
   * Without this the retry could silently stop emitting the action it is named after — which
   * is how it behaved for decoder failures before the error moved into `UiState`.
   */
  @Test
  fun `the error state retries by reopening the room`() {
    val actions = mutableListOf<UiAction>()
    render(
      state = uiState(error = "无法连接到服务器", playing = false),
      onAction = { actions += it },
    )

    composeRule.onAllNodesWithText("重试")[0].performClick()
    composeRule.waitForIdle()

    assertEquals(1, actions.size)
    assertTrue("got ${actions.first()}", actions.first() is UiAction.OpenRoom)
    assertEquals(room.key, (actions.first() as UiAction.OpenRoom).streamer.key)
  }

  private fun heightOfDp(node: androidx.compose.ui.test.SemanticsNodeInteraction): Float =
    with(composeRule.density) { node.fetchSemanticsNode().boundsInRoot.height.toDp().value }

  /**
   * The action row's 关注 is a 48dp target.
   *
   * The pill's height came from its label plus 7dp of vertical padding — about 34dp — because the
   * `Surface` itself was the clickable node. It stays that size; the tap area grew around it.
   */
  @Test
  fun `the follow pill is a 48dp target`() {
    render(fullscreen = false)

    val height = heightOfDp(composeRule.onNodeWithText("关注"))
    assertTrue("the follow pill is only ${height}dp tall", height >= 48f)
  }

  /**
   * Both controls in the full-screen bar are 48dp targets.
   *
   * They float over the picture with no disc behind them and only 34dp of hit area, so a miss read
   * as "the button is not there" rather than as a miss. The side buttons were fixed the same way in
   * round 64; these two are the ones it left behind.
   */
  @Test
  fun `the full screen bar controls are 48dp targets`() {
    render(fullscreen = true)

    val heights = composeRule.onAllNodesWithContentDescription("退出全屏")
      .fetchSemanticsNodes()
      .map { node -> with(composeRule.density) { node.boundsInRoot.height.toDp().value } }

    // The bar's back arrow and the side column's exit-fullscreen button.
    assertTrue("expected two exit controls, got ${heights.size}", heights.size >= 2)
    heights.forEach { height ->
      assertTrue("an exit control is only ${height}dp tall", height >= 48f)
    }
  }

  /**
   * The thumbnail composition draws the picture and nothing else.
   *
   * Everything the player normally puts over the picture — the side column, the full-screen bar, the
   * action row and the danmaku list — needs a surface a finger can aim at, and a thumbnail has none
   * of that size. The mini window puts its own tap-and-close layer there, and picture-in-picture
   * gets the system's.
   */
  @Test
  fun `the compact composition renders the picture only`() {
    render(fullscreen = false, compact = true)

    assertEquals("the side column must be gone", 0, count("播放设置"))
    assertEquals(
      "the action row must be gone, found ${composeRule.onAllNodesWithText("关注").fetchSemanticsNodes().size}",
      0,
      composeRule.onAllNodesWithText("关注").fetchSemanticsNodes().size,
    )
    assertEquals(
      "the danmaku panel must be gone",
      0,
      composeRule.onAllNodesWithText("正在连接弹幕…").fetchSemanticsNodes().size,
    )
  }

  /**
   * A paused stream says so.
   *
   * The badge used to be driven by a `remember` inside this composable; it now comes from
   * `UiState.playerPaused`, which is what lets the activity pause the stream on its way out.
   */
  @Test
  fun `a paused stream shows the badge`() {
    render(state = uiState().copy(playerPaused = true))

    composeRule.onNodeWithText("已暂停 · 双击继续").assertIsDisplayed()
  }

  // ------------------------------------------------------------- the way out ---
  //
  // Portrait has no header bar, and for three rounds the documented exit was "the left-edge
  // swipe or the system back gesture". That left the screen with no control at all for leaving
  // it — the same "gesture nobody has discovered" the mini window's × was added to avoid.

  /**
   * The portrait player carries a visible way out, over the picture, above the action row.
   *
   * Asserting existence alone would pass for a button parked under the picture or behind the
   * danmaku list, so the position is checked against the action row: the picture is the 16:9
   * band at the top, and the row starts underneath it.
   */
  @Test
  fun `the portrait player shows a way out over the picture`() {
    val actions = mutableListOf<UiAction>()
    render(onAction = { actions += it })

    val back = composeRule.onNodeWithContentDescription("返回").fetchSemanticsNode().boundsInRoot
    val follow = composeRule.onNodeWithText("关注").fetchSemanticsNode().boundsInRoot
    assertTrue(
      "the back control ends at ${back.bottom} but the action row starts at ${follow.top}",
      back.bottom <= follow.top,
    )
    assertTrue("the back control is not in the corner: ${back.left}", back.left < 120f)

    composeRule.onNodeWithContentDescription("返回").performClick()
    composeRule.waitForIdle()

    assertEquals(1, actions.size)
    assertTrue(
      "leaving the player is 缩小 into the mini window, got ${actions.first()}",
      actions.first() is UiAction.MinimizePlayer,
    )
  }

  /**
   * A landscape window puts the same way out on the full-screen bar.
   *
   * Nothing about the landscape player could be asserted before: its layout is derived from the
   * window, and the only renderer runs at 320x640. Here the window is passed in, which is also
   * how `PlayerScreen` decides — see `playerFullscreen`.
   *
   * The side column must lose its fullscreen toggle in the same breath: in a landscape window
   * leaving full screen has no portrait arrangement to land in, so that button could only be a
   * control that does nothing.
   */
  @Test
  fun `a landscape window puts the way out on the bar`() {
    val actions = mutableListOf<UiAction>()
    render(fullscreen = true, landscapeWindow = true, onAction = { actions += it })

    assertEquals("the bar keeps one back control", 1, count("返回"))
    assertEquals("the fullscreen toggle has nowhere to go", 0, count("退出全屏"))

    composeRule.onNodeWithContentDescription("返回").performClick()
    composeRule.waitForIdle()

    assertEquals(1, actions.size)
    assertTrue(
      "back leaves the room in landscape, got ${actions.first()}",
      actions.first() is UiAction.MinimizePlayer,
    )
  }

  /**
   * A vertical stream fills the window, so the action row is not composed — and the star on the
   * bar is the only follow control left.
   *
   * This branch had no test and no render at all: `videoAspect` is learned from the real
   * decoder, and every harness replaces the surface. Before this it also had no follow control,
   * which made a vertical room (every 抖音 room) impossible to follow without leaving it.
   */
  @Test
  fun `a vertical stream keeps follow on the bar`() {
    val actions = mutableListOf<UiAction>()
    render(videoAspect = 9f / 16f, onAction = { actions += it })

    assertEquals(
      "the action row is not drawn for a full-bleed picture",
      0,
      composeRule.onAllNodesWithText("关注").fetchSemanticsNodes().size,
    )
    assertEquals("the bar offers the follow action", 1, count("关注"))
    composeRule.onNodeWithText("一条小团团").assertIsDisplayed()

    composeRule.onNodeWithContentDescription("关注").performClick()
    composeRule.waitForIdle()

    assertEquals(1, actions.size)
    assertTrue("got ${actions.first()}", actions.first() is UiAction.ToggleFollow)
  }

  /**
   * Rotating the phone is a request for full screen, and rotating back is a request to leave it.
   *
   * The rule is asserted on its own because the composable that applies it builds an ExoPlayer
   * — the one thing a JVM test cannot construct. Without it, a landscape window renders the
   * portrait arrangement: a 16:9 picture exactly as tall as the window, with the action row and
   * the danmaku list pushed off the bottom and the overlay switched off.
   */
  @Test
  fun `rotation decides full screen`() {
    assertFalse(
      "a portrait window is not full screen until the button is pressed",
      playerFullscreen(landscapeWindow = false, manualFullscreen = false),
    )
    assertTrue(
      "a landscape window is full screen with no button press",
      playerFullscreen(landscapeWindow = true, manualFullscreen = false),
    )
    assertTrue(
      "the button still works in portrait",
      playerFullscreen(landscapeWindow = false, manualFullscreen = true),
    )
  }
}
