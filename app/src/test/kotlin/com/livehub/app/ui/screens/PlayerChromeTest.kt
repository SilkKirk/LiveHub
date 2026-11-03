package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
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
    onAction: (UiAction) -> Unit = {},
  ) {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      PlayerContent(
        state = state,
        onAction = onAction,
        fullscreen = fullscreen,
        onFullscreenChange = {},
        onOpenSettings = {},
        videoSurface = { surfaceModifier -> Box(surfaceModifier.fillMaxSize()) },
      )
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
}
