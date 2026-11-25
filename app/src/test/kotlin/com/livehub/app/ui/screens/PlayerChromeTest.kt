package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
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
import androidx.compose.ui.test.printToString
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.designsystem.LiveHubTheme
import com.livehub.core.model.Category
import com.livehub.core.model.DanmakuMessage
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
    exitReturnsToPortrait: Boolean = true,
    videoAspect: Float? = null,
    onAction: (UiAction) -> Unit = {},
    onFullscreenChange: (Boolean) -> Unit = {},
  ) {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      LiveHubTheme {
        PlayerContent(
          state = state,
          onAction = onAction,
          fullscreen = fullscreen,
          onFullscreenChange = onFullscreenChange,
          onOpenSettings = {},
          videoSurface = { surfaceModifier -> Box(surfaceModifier.fillMaxSize()) },
          compact = compact,
          exitReturnsToPortrait = exitReturnsToPortrait,
          videoAspectHint = videoAspect,
        )
      }
    }
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.waitForIdle()
  }

  private fun count(description: String): Int =
    composeRule.onAllNodesWithContentDescription(description).fetchSemanticsNodes().size

  private fun chromeVisible(): Boolean = count("刷新") > 0

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
    assertEquals("the strip must be gone with the chrome", 0, count("刷新"))

    tapPicture(y = 300f)

    assertEquals("the strip comes back with the tap", 1, count("刷新"))
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
    assertEquals("a tap hides it", 0, count("刷新"))

    tapPicture(y = 80f)
    assertEquals("a second tap shows it again", 1, count("刷新"))
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
   * Every control in the full-screen strip is a 48dp target.
   *
   * They float over the picture with no label behind them, so a miss read as "the button is not
   * there" rather than as a miss. The side column was fixed this way in round 64; round 144 moved
   * the same controls to the bottom of the picture and this keeps them honest there.
   */
  @Test
  fun `the full screen strip controls are 48dp targets`() {
    render(fullscreen = true)

    val heights = composeRule.onAllNodesWithContentDescription("退出全屏")
      .fetchSemanticsNodes()
      .map { node -> with(composeRule.density) { node.boundsInRoot.height.toDp().value } }

    assertTrue("expected the strip's exit control, got ${heights.size}", heights.size >= 1)
    heights.forEach { height ->
      assertTrue("an exit control is only ${height}dp tall", height >= 48f)
    }
    // And the strip is at the bottom, not a second top bar.
    val exit = composeRule.onAllNodesWithContentDescription("退出全屏")[0]
      .fetchSemanticsNode()
      .boundsInRoot
    val window = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
    assertTrue(
      "the exit control sits at ${exit.bottom} of ${window.bottom}: the strip belongs at the bottom",
      exit.bottom > window.bottom * 0.6f,
    )
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

    assertEquals("the strip must be gone", 0, count("刷新"))
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

  // ------------------------------------------ what hides the strip, and what must not hide it ---
  //
  // Round 144 moved every control into a strip that hides itself, and the flag that says whether it
  // is showing is the only state in `PlayerContent` that belongs to neither `UiState` nor layout.
  // Three paths let that flag outlive the reason it was hidden; all three are "something the user did
  // not change is being treated as 'the strip has timed out'".

  /**
   * Composes the player body around state the test can change afterwards.
   *
   * [render] hands `PlayerContent` fixed arguments, which answers "what does this arrangement look
   * like" and cannot answer "what happens to the strip when the room changes": that change has to
   * arrive as a recomposition of the *same* composable, which is what the device does — one call
   * site serves every room and all three window sizes.
   */
  private fun renderBody(
    state: MutableState<UiState>,
    compact: MutableState<Boolean> = mutableStateOf(false),
    settingsOpen: MutableState<Boolean> = mutableStateOf(false),
    fullscreen: Boolean = true,
  ) {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      LiveHubTheme {
        PlayerContent(
          state = state.value,
          onAction = {},
          // Full screen by default: that is where the countdown is shortest (3s), and a bare picture
          // there has nothing below it to fall back on.
          fullscreen = fullscreen,
          onFullscreenChange = {},
          onOpenSettings = {},
          videoSurface = { surfaceModifier -> Box(surfaceModifier.fillMaxSize()) },
          compact = compact.value,
          settingsOpen = settingsOpen.value,
        )
      }
    }
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.waitForIdle()
  }

  /** Many frames of virtual time, still short of the 3s the strip gives itself. */
  private fun settle() {
    composeRule.mainClock.advanceTimeBy(500L)
    composeRule.waitForIdle()
  }

  /**
   * Applies a state write from the test, and lets the composition see it.
   *
   * A bare assignment is not enough here, and `runOnIdle` is not either: this class freezes the clock
   * (`autoAdvance = false`) so the strip's own countdown can be stepped by hand, and **with the clock
   * frozen `waitForIdle()` never produces the frame that carries a test-thread write into the
   * composition**. The screen keeps drawing the state it had while the assertion below asks about the
   * new one. Round 145's third red run dumped the tree and showed exactly that: `compact = true`
   * written, strip still on screen at full width.
   *
   * Letting the clock run for one idle cycle is what applies it. The strip's own deadline is 3s away
   * and a `delay` is not "pending work" to the idling resource, so nothing else moves.
   */
  private fun apply(write: () -> Unit) {
    composeRule.runOnUiThread(write)
    composeRule.mainClock.autoAdvance = true
    composeRule.waitForIdle()
    composeRule.mainClock.autoAdvance = false
  }

  /**
   * What was on screen, for a failure message.
   *
   * "The control did not come back" cannot be told apart from the message alone between "the state
   * never changed", "the change did not reach the composition" and "the strip is drawn but empty".
   * Counting the controls plus dumping the tree answers which of the three it was.
   */
  private fun screenDump(): String =
    "刷新=${count("刷新")} 退出全屏=${count("退出全屏")}\n" +
      composeRule.onRoot().printToString(maxDepth = 30)

  /**
   * A room the user just opened starts with its controls on screen.
   *
   * `controlsVisible` was a bare `remember` while this composable is one call site for every room, so
   * "the strip timed out in the room I left" was still true in the room I opened: the second room of
   * a session came up bare, and 清晰度/刷新/全屏 were reachable only by tapping a picture that gave no
   * sign they existed.
   */
  @Test
  fun `a room the user just opened starts with its strip`() {
    val state = mutableStateOf(uiState())
    // Portrait, so the action row below the picture can prove the room change reached the
    // composition at all — the strip itself is what is in question.
    renderBody(state = state, fullscreen = false)

    assertTrue("the strip is drawn when the player opens: ${screenDump()}", chromeVisible())

    // Portrait hides it after 4s, not 3.
    composeRule.mainClock.advanceTimeBy(4_500L)
    composeRule.waitForIdle()
    assertFalse("the strip times out on its own: ${screenDump()}", chromeVisible())

    apply {
      state.value = state.value.copy(
        room = room.copy(roomId = "2", streamerName = "另一个主播"),
      )
    }
    settle()

    composeRule.onNodeWithText("另一个主播").assertExists()
    assertTrue("a freshly opened room must show its controls: ${screenDump()}", chromeVisible())
  }

  /**
   * Expanding the thumbnail starts the chrome over, instead of inheriting its deadline.
   *
   * A thumbnail draws no chrome, so "hidden" means nothing there — but the countdown ran in the
   * corner all the same, and the picture came back full size already past its deadline. Back, then
   * tap the corner, is the ordinary way back into a stream.
   */
  @Test
  fun `expanding the thumbnail starts the strip over`() {
    val state = mutableStateOf(uiState())
    val compact = mutableStateOf(false)
    renderBody(state = state, compact = compact)

    assertTrue("the strip is drawn when the player opens: ${screenDump()}", chromeVisible())

    apply { compact.value = true }
    settle()
    assertEquals("a thumbnail draws no chrome: ${screenDump()}", 0, count("刷新"))

    // Five seconds in the corner: past the deadline the strip would have had.
    composeRule.mainClock.advanceTimeBy(5_000L)
    composeRule.waitForIdle()

    apply { compact.value = false }
    settle()

    assertTrue(
      "expanding must not land on a picture with no controls: ${screenDump()}",
      chromeVisible(),
    )
  }

  /**
   * The panel the strip opened does not get to watch the strip expire underneath it.
   *
   * 清晰度 opens the settings sheet, and the countdown knew nothing about it: pick a quality, close
   * the sheet, and the picture has no strip — two extra taps for every second comparison. In full
   * screen the strip disappeared from behind the open panel, which reads as the panel having eaten
   * the controls.
   */
  @Test
  fun `the settings panel keeps the strip on screen`() {
    val state = mutableStateOf(uiState())
    val settingsOpen = mutableStateOf(true)
    renderBody(state = state, settingsOpen = settingsOpen)

    // Well past the deadline: the user is looking at the panel, not at the picture.
    composeRule.mainClock.advanceTimeBy(6_000L)
    composeRule.waitForIdle()
    assertTrue("the countdown must not run under an open panel", chromeVisible())

    apply { settingsOpen.value = false }
    settle()
    assertTrue("closing the panel must leave the strip on screen", chromeVisible())
  }

  // ------------------------------------------------------------- the way out ---
  //
  // Portrait has no header bar, and for three rounds the documented exit was "the left-edge
  // swipe or the system back gesture". That left the screen with no control at all for leaving
  // it — the same "gesture nobody has discovered" the mini window's × was added to avoid.

  /**
   * The picture draws no way-out control any more: leaving is the gesture.
   *
   * Portrait used to carry a 返回 arrow in the corner, added in round 88 because "a gesture nobody
   * has discovered is not a control". Round 144 removed it again on the user's request: every phone
   * this app installs on has a back gesture, `LiveHubRoot` turns that gesture into 缩小, and the
   * left-edge swipe does the same — so the arrow was a third way to do it, drawn over the picture.
   *
   * The error and offline states are the exception and keep their own 返回 pill (asserted by their
   * own tests): there is no picture to swipe and no chrome to reveal, so the pill is the only control.
   */
  @Test
  fun `the portrait picture draws no way-out control`() {
    val actions = mutableListOf<UiAction>()
    render(onAction = { actions += it })

    assertEquals(
      "a back arrow over the picture is what round 144 removed",
      0,
      count("返回"),
    )
    assertTrue("the strip is still the chrome", chromeVisible())
    assertTrue("and it is not a control that leaves the room: $actions", actions.isEmpty())
  }

  /**
   * The danmaku layer stays above the strip, not across it.
   *
   * It used to be inset by a *top* bar's height (56dp against a 60dp bar); with the chrome at the
   * bottom now, the thing a label can collide with is the strip. Same assertion shape as before —
   * compare laid-out edges rather than the constants, so a change to either side fails here.
   */
  @Test
  fun `the first danmaku label stays above the strip`() {
    render(
      state = uiState().copy(
        danmaku = listOf(
          DanmakuMessage(roomKey = room.key, userName = "观众", text = "别压着按钮"),
        ),
      ),
      fullscreen = true,
    )
    // The bubble is placed by an effect and animated by an `Animatable`; one frame composes the
    // effect but the label needs the following one to exist in the tree.
    composeRule.mainClock.advanceTimeBy(500L)
    composeRule.waitForIdle()

    val exit = composeRule.onAllNodesWithContentDescription("退出全屏")[0]
      .fetchSemanticsNode()
      .boundsInRoot
    val label = composeRule.onNodeWithText("别压着按钮").fetchSemanticsNode().boundsInRoot

    assertTrue(
      "the label ends at ${label.bottom}, the strip starts at ${exit.top}",
      label.bottom <= exit.top,
    )
  }

  /**
   * A landscape window the *user* turned offers no exit control at all.
   *
   * Leaving full screen there would restore exactly the arrangement `playerFullscreen` exists to
   * avoid, so 退出全屏 is not drawn — and with the back arrow gone (round 144) neither is a 返回. The
   * way out is the back gesture, which `LiveHubRoot` turns into 缩小.
   */
  @Test
  fun `a landscape window the user turned offers no exit control`() {
    val actions = mutableListOf<UiAction>()
    render(
      fullscreen = true,
      exitReturnsToPortrait = false,
      onAction = { actions += it },
    )

    assertEquals("no 返回 arrow is drawn any more", 0, count("返回"))
    assertEquals("and 退出全屏 has nowhere to land", 0, count("退出全屏"))
    assertTrue("the strip is still there", chromeVisible())
    assertTrue("nothing was asked of the ViewModel: $actions", actions.isEmpty())
  }

  /**
   * A landscape window the 全屏 *button* asked for keeps exactly one exit, and it releases the lock.
   *
   * This is the state the two controls once disappeared from: the window really was sideways, so the
   * `landscapeWindow`-keyed branch treated it as "the user rotated" and hid 退出全屏 — with no arrow
   * left either, the only way back would have been the thumbnail. Round 133 keyed the decision on the
   * button's own flag; round 144 left one exit in the strip.
   */
  @Test
  fun `a landscape window the button asked for keeps one exit`() {
    val actions = mutableListOf<UiAction>()
    val fullscreenChanges = mutableListOf<Boolean>()
    render(
      fullscreen = true,
      exitReturnsToPortrait = true,
      onAction = { actions += it },
      onFullscreenChange = { fullscreenChanges += it },
    )

    assertEquals("no 返回 arrow", 0, count("返回"))
    assertEquals("the strip offers exactly one 退出全屏", 1, count("退出全屏"))

    composeRule.onAllNodesWithContentDescription("退出全屏")[0].performClick()
    composeRule.waitForIdle()

    assertEquals("nothing should leave the room here", 0, actions.size)
    assertEquals("the exit has to release the orientation lock", listOf(false), fullscreenChanges)
  }

  /**
   * A vertical stream keeps 关注 in the strip, because nothing else on screen can follow it.
   *
   * The old top bar carried a star here — and in a 16:9 picture too, where it duplicated the action
   * row. The strip carries the name plus, while the room is unfollowed, one 关注 pill; unfollowing
   * from the player is not offered.
   */
  @Test
  fun `a vertical stream offers 关注 in the strip`() {
    val actions = mutableListOf<UiAction>()
    render(videoAspect = 9f / 16f, onAction = { actions += it })

    assertEquals(
      "exactly one 关注 — the strip pill, since a full-bleed picture has no action row",
      1,
      composeRule.onAllNodesWithText("关注").fetchSemanticsNodes().size,
    )
    composeRule.onNodeWithText("一条小团团").assertIsDisplayed()

    composeRule.onNodeWithText("关注").performClick()
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
