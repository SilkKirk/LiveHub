package com.livehub.app.ui.screens

import android.content.Intent
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.SubtitlesOff
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.BackSwipeContainer
import com.livehub.app.ui.DanmakuStatus
import com.livehub.app.ui.components.LiveHubFilterChip
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.app.ui.player.DanmakuOverlay
import com.livehub.app.ui.player.LivePlayer
import com.livehub.app.ui.player.PlayerOrientation
import com.livehub.app.ui.player.findActivity
import com.livehub.app.ui.player.fullscreenExitReturnsToPortrait
import com.livehub.app.ui.player.playerOrientation
import com.livehub.app.ui.player.toActivityInfo
import com.livehub.app.ui.player.withoutBlockedKeywords
import com.livehub.core.common.ViewerCountFormatter
import com.livehub.core.designsystem.LiveBadgeColor
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import kotlinx.coroutines.delay

/**
 * Whether the player belongs in full screen, and why rotation counts as a request.
 *
 * A landscape window on a phone is 360dp tall while the 16:9 picture alone is 360dp of it, so
 * the portrait arrangement has nothing left for `RoomActionRow` or the danmaku list: they are
 * pushed off the bottom. The overlay would not step in either — it is drawn only in full screen
 * or over a full-bleed vertical stream — so a rotated phone showed a picture, three round
 * buttons, and no danmaku at all, while `fullscreen` stayed false because nobody had pressed a
 * button that is 3 seconds deep in an auto-hiding chrome.
 *
 * Deriving it from the window instead of a `LaunchedEffect` that flips the flag is what keeps
 * the two in sync in both directions: rotate back and the portrait layout returns on its own,
 * with no state to reset.
 *
 * Extracted rather than inlined so the rule can be asserted without composing a player: the
 * composable that computes it (`PlayerScreen`) also builds an ExoPlayer, which no JVM test can
 * do.
 */
internal fun playerFullscreen(landscapeWindow: Boolean, manualFullscreen: Boolean): Boolean =
  landscapeWindow || manualFullscreen

/**
 * Live room screen.
 *
 * Mirrors the reference player's shape:
 *
 *  * a 16:9 video surface with the way out floating over its top edge,
 *  * a vertical column of round controls (settings / reload / fullscreen) on the right,
 *  * the danmaku list filling the rest of the screen,
 *  * a settings sheet holding quality, line and every danmaku option, because those do
 *    not fit in a chip row without crowding the picture,
 *  * full screen keeping only video plus the floating overlay — entered by the button *or* by
 *    rotating the phone, see [playerFullscreen].
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerScreen(
  state: UiState,
  onAction: (UiAction) -> Unit,
  /**
   * True when this is not the whole screen: the mini window on another screen, or the system's
   * picture-in-picture window.
   *
   * Both are the size of a thumbnail, so only the picture is drawn — the side controls, the action
   * row and the danmaku list would be unreadable, and in picture-in-picture the system puts its own
   * expand/close affordances over the window.
   *
   * The compact player is *the same composition* as the full one, only smaller: `LiveHubRoot`
   * changes the size of the box around it, never the call. A different call site would dispose the
   * `AndroidView` holding the video surface and build a new decoder, which on a live stream is a
   * second of black plus a fresh resolve every time the user leaves the player.
   */
  compact: Boolean = false,
  /**
   * Where the 全屏 button's orientation request goes.
   *
   * Defaulted (null) to "ask the activity", which is what the app needs; a test passes a recorder
   * instead, because the rule — *when* the window should turn, and when it must stop being turned —
   * is the part that gets this wrong, and asserting it does not need a real window. See
   * [PlayerOrientation].
   */
  orientationRequest: ((PlayerOrientation) -> Unit)? = null,
  /**
   * Where the picture sits in the window, reported for picture-in-picture's source rectangle.
   *
   * Defaulted to a no-op so screenshots and tests compose without an activity. The value is
   * measured rather than assumed: portrait, full screen and the floating window are three different
   * rectangles, and the shrink animation starts from whichever one is on screen.
   */
  onVideoBounds: (androidx.compose.ui.geometry.Rect?) -> Unit = {},
) {
  var manualFullscreen by remember { mutableStateOf(false) }
  var showSettings by remember { mutableStateOf(false) }
  // In landscape the bottom sheet has only the height of a landscape window to work with,
  // and the settings list is taller than that. A left-hand drawer uses the axis that has
  // room instead — the same reason the reference client switches to a drawer there.
  val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  val fullscreen = playerFullscreen(landscapeWindow = isLandscape, manualFullscreen = manualFullscreen)
  // A landscape window is full screen either because the user turned the phone or because the 全屏
  // button asked it to turn. Only the second one has a portrait player to go back to, and the two
  // are indistinguishable from the window itself — see `fullscreenExitReturnsToPortrait`.
  val exitReturnsToPortrait = fullscreenExitReturnsToPortrait(
    landscapeWindow = isLandscape,
    manualFullscreen = manualFullscreen,
  )

  // The picture stops existing when the player is disposed; a stale rectangle would make the next
  // shrink animate from wherever it used to be.
  DisposableEffect(Unit) { onDispose { onVideoBounds(null) } }

  // The one place that asks the window to turn, and the one place that stops asking. Both
  // directions are driven by the button (see `onFullscreenChange` below), never by the window:
  // a phone turned sideways is already full screen through `playerFullscreen`, and requesting a
  // rotation there would lock the device into the orientation the user was about to leave.
  //
  // Released when the player is no longer the full window — the mini window is a thumbnail inside
  // another screen and must not keep the whole app sideways — and released again on dispose.
  val context = LocalContext.current
  val requestOrientation: (PlayerOrientation) -> Unit = remember(orientationRequest, context) {
    orientationRequest ?: { orientation ->
      context.findActivity()?.requestedOrientation = orientation.toActivityInfo()
    }
  }
  LaunchedEffect(manualFullscreen, compact) {
    requestOrientation(playerOrientation(manualFullscreen = manualFullscreen, compact = compact))
  }
  DisposableEffect(requestOrientation) {
    onDispose { requestOrientation(PlayerOrientation.Unspecified) }
  }
  // A thumbnail is never "full screen by choice": coming back from the mini window lands in the
  // portrait player, not in a chrome-less portrait rectangle that re-locks landscape.
  //
  // The settings panel is closed with it. This composable is *not* disposed when the picture shrinks
  // into the corner — one call site, three sizes is the whole point — so `showSettings` survived the
  // trip and the panel re-opened by itself, unclicked, the next time the picture was expanded.
  LaunchedEffect(compact) {
    if (compact) {
      manualFullscreen = false
      showSettings = false
    }
  }

  // Back leaves full screen whenever there is a portrait player behind it, which includes the
  // landscape window the 全屏 button turned. In a landscape window the *user* turned there is
  // nowhere to land, so the same gesture is "step off the player" and `LiveHubRoot`'s handler does it.
  //
  // Not while the settings panel is open: that panel is drawn in this window rather than as a
  // dialog, so it answers for the gesture itself (see the drawer below), and one press must not both
  // close the panel and drop the room.
  BackHandler(enabled = fullscreen && !compact && exitReturnsToPortrait && !showSettings) {
    manualFullscreen = false
  }

  Box(modifier = Modifier.fillMaxSize()) {
    // Swipe from the left edge to step off the player. It used to close the room; now it minimises
    // it, which is the gesture's whole point — the picture keeps going in the corner.
    BackSwipeContainer(onClose = { onAction(UiAction.MinimizePlayer) }) {
      PlayerContent(
        state = state,
        onAction = onAction,
        fullscreen = fullscreen,
        onFullscreenChange = { manualFullscreen = it },
        onOpenSettings = { showSettings = true },
        compact = compact,
        settingsOpen = showSettings,
        exitReturnsToPortrait = exitReturnsToPortrait,
        onVideoBounds = onVideoBounds,
      )
    }

    // The drawer is drawn inside the activity's window rather than as a dialog, so no framework
    // hands it the back gesture: without this, back fell through to `LiveHubRoot` and became 缩小,
    // leaving the panel open over a room the user had just left.
    BackHandler(enabled = showSettings && isLandscape && !compact) { showSettings = false }

    if (showSettings && isLandscape && !compact) {
      PlayerSettingsDrawer(onDismiss = { showSettings = false }) {
        PlayerSettings(state = state, variant = state.selectedVariant, onAction = onAction)
      }
    }
  }

  if (showSettings && !isLandscape && !compact) {
    ModalBottomSheet(onDismissRequest = { showSettings = false }) {
      PlayerSettings(state = state, variant = state.selectedVariant, onAction = onAction)
    }
  }
}

/**
 * Side drawer holding the player settings in landscape.
 *
 * A `ModalBottomSheet` in landscape is capped at a fraction of an already short window, so
 * most of the options (everything below 清晰度 once 线路 and the danmaku rows are counted)
 * were simply unreachable — there was no scrolling either, so they could not even be
 * dragged into view. The drawer takes height from the long axis instead.
 */
@Composable
private fun PlayerSettingsDrawer(
  onDismiss: () -> Unit,
  content: @Composable () -> Unit,
) {
  Box(modifier = Modifier.fillMaxSize()) {
    // Scrim: tapping anywhere outside the drawer closes it, as in the reference client.
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.45f))
        .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    )
    Surface(
      modifier = Modifier
        .fillMaxHeight()
        .fillMaxWidth(0.42f)
        .widthIn(max = 320.dp)
        .align(Alignment.CenterStart),
      shape = RoundedCornerShape(topEnd = 18.dp, bottomEnd = 18.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 2.dp,
      shadowElevation = 6.dp,
    ) {
      content()
    }
  }
}

/**
 * The player body, without the sheet that [PlayerScreen] owns.
 *
 * Split out so the layout can be composed on its own — the instrumented screenshots render
 * every screen without a real decoder, and the previous single composable could not be
 * rendered without going through the full-screen path.
 *
 * @param videoSurface replaces the real Media3 surface for a **playing** room. `captureToImage`
 *   cannot read a `SurfaceView`'s contents (they are composited by SurfaceFlinger, not drawn
 *   into the window), so a screenshot of a live `LivePlayer` is a black rectangle no matter
 *   what the surrounding layout does. The screenshot harness supplies a labelled placeholder
 *   so the render still proves the chrome around the video laid out correctly.
 *   Loading, offline and error states ignore it, because in those states there is no video
 *   and their own content is what the user must see.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlayerContent(
  state: UiState,
  onAction: (UiAction) -> Unit,
  fullscreen: Boolean,
  onFullscreenChange: (Boolean) -> Unit,
  onOpenSettings: () -> Unit,
  modifier: Modifier = Modifier,
  videoSurface: (@Composable (Modifier) -> Unit)? = null,
  /**
   * True for the mini window and for the system's picture-in-picture window.
   *
   * Both are thumbnail-sized, so everything except the picture is dropped: the chrome, the action
   * row, the danmaku list and the tap-to-pause layer all need a surface the user can aim at. The
   * mini window puts its own tap-to-expand and close affordances over this surface, and
   * picture-in-picture gets the system's.
   */
  compact: Boolean = false,
  /**
   * Whether the settings panel is open.
   *
   * The panel belongs to [PlayerScreen], one level up, while the strip that opens it lives in this
   * body — so without this flag the strip's countdown cannot know that the panel it just opened is
   * still on screen, and expires underneath it. See the `LaunchedEffect` below.
   */
  settingsOpen: Boolean = false,
  /**
   * Whether 退出全屏 has a portrait player to land in.
   *
   * The window being sideways does not answer this on its own — *why* it is sideways does, and the
   * two cases need opposite controls. A window the user turned has no portrait arrangement to return
   * to, so the bar's leading button leaves the room and the side column drops the fullscreen toggle
   * because there is nowhere for it to go. A window the 全屏 button turned is the mirror image: the
   * portrait player is exactly where the control should land, and hiding it there is what made the
   * button a one-way door. `PlayerScreen` derives the answer from `fullscreenExitReturnsToPortrait`
   * and the chrome tests pass it directly.
   */
  exitReturnsToPortrait: Boolean = true,
  /**
   * The aspect to assume before the decoder has reported one, or null in the app.
   *
   * The screenshot harness and the chrome tests replace [videoSurface], and the real surface is
   * what teaches this layout that a stream is vertical — so without a hint the full-bleed
   * branch could not be rendered or asserted at all. A real decoder's report overwrites it;
   * see the `onVideoAspectRatioChanged` call below.
   */
  videoAspectHint: Float? = null,
  /**
   * Where the picture sits in the window, reported for picture-in-picture's source rectangle.
   *
   * Defaulted to a no-op so the chrome tests and the screenshot harness compose without an
   * activity; [PlayerScreen] passes the activity's observer through.
   */
  onVideoBounds: (androidx.compose.ui.geometry.Rect?) -> Unit = {},
) {
  val variant: StreamVariant? = state.selectedVariant
  // Keyed on the room, because this composable is one call site for every room *and* for all three
  // window sizes. A bare `remember` carried "the strip has timed out" out of the room the user left
  // and into the room they just opened, so the second room of a session came up with no
  // 弹幕/清晰度/刷新/全屏 on it until the picture was tapped — and the same value survived the trip
  // through the thumbnail window, which is the path a user takes by pressing back and then tapping
  // the corner.
  var controlsVisible by remember(state.room?.key) { mutableStateOf(true) }
  // Pause lives in `UiState`, not in a `remember`.
  //
  // It used to be a local flag, which made it invisible to everything outside this composable —
  // including the activity, which now pauses the stream when the app is hidden without a
  // picture-in-picture window. See `LiveHubApp` and [UiAction.SetPlayerPaused].
  val paused = state.playerPaused
  // The gesture detector below is created once per composition and reads `state` from *that*
  // composition; `rememberUpdatedState` is what keeps a double tap from toggling a stale value.
  val current by rememberUpdatedState(state)
  // Learned from the decoder, or supplied by the caller. Null until the first frame is sized,
  // which keeps the surface at 16:9 instead of collapsing to zero height on open.
  var videoAspect by remember(state.room?.key) { mutableStateOf(videoAspectHint) }
  // Set when a CDN hostname cannot pass TLS; the same URL is retried over http.
  //
  // Keyed on the variant's URL as well as the room. Keyed on the room alone it survived a quality
  // or line switch, and `effectiveVariant` below then overwrote the *new* variant's URL with the
  // downgraded form of the old one — so on Huya, the one platform that both needs the downgrade and
  // offers a line axis, picking another line re-selected the chip while the picture kept playing the
  // previous line's URL. The fallback itself does not change `selectedVariant`, so applying it does
  // not clear the key; only a real switch does, which is exactly when the old URL stops being valid.
  var httpFallbackUrl by remember(state.room?.key, variant?.url) {
    mutableStateOf<String?>(null)
  }

  val isPlaying = variant != null && !state.loading
  // Not while the panel the strip opened is on screen. The countdown knew nothing about that panel,
  // so 清晰度 → pick a quality → the sheet closed on a picture whose strip had already expired: two
  // extra taps for every second comparison. In full screen it was worse than annoying — the strip
  // vanished from *behind* the open panel, which reads as the panel having eaten the controls.
  LaunchedEffect(controlsVisible, variant?.url, isPlaying, fullscreen, settingsOpen) {
    if (controlsVisible && isPlaying && !settingsOpen) {
      delay(if (fullscreen) FULLSCREEN_HIDE_DELAY_MILLIS else PORTRAIT_HIDE_DELAY_MILLIS)
      controlsVisible = false
    }
  }
  // Closing the panel starts the chrome over, with a fresh countdown: the control the user just used
  // is the one they are most likely to reach for again.
  LaunchedEffect(settingsOpen) {
    if (!settingsOpen) controlsVisible = true
  }
  // A thumbnail draws no chrome at all, so "hidden" means nothing while the picture is in the corner
  // — but the countdown ran there all the same, and expanding landed on a picture already past its
  // deadline. Expanding starts the chrome over.
  LaunchedEffect(compact) {
    if (!compact) controlsVisible = true
  }

  val effectiveVariant = variant?.let { current ->
    httpFallbackUrl?.let { fallback -> current.copy(url = fallback) } ?: current
  }
  val isVerticalVideo = videoAspect?.let { it in 0.2f..0.95f } == true
  // A vertical stream in portrait should fill the screen, not sit in a 16:9 letterbox
  // with the top and bottom of the picture outside the frame.
  //
  // Not in the thumbnail window: that one is 16:9 by construction, so "fill" there means cropping
  // the picture to a horizontal slice of a vertical stream. Letterboxing keeps whatever is framed
  // — which for a portrait stream is the part worth watching.
  val verticalFullBleed = !fullscreen && !compact && isVerticalVideo

  Box(
    modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      // No header in portrait: the picture now starts at the top edge, and closing the
      // player is the left-edge swipe or the system back gesture. A vertical stream in
      // portrait already used this layout, so this makes the two agree instead of
      // leaving one of them with a bar nobody needed.

      val videoModifier = when {
        // The picture-in-picture window *is* the picture: no status-bar inset and no aspect
        // ratio, because the system has already sized the window and the video has to fill it.
        compact -> Modifier.fillMaxSize()
        // Full screen and the full-bleed vertical layout are immersive on purpose: the
        // picture runs under the status bar, which is what "fills the screen" means.
        fullscreen || verticalFullBleed -> Modifier.fillMaxSize()
        else -> Modifier
          .fillMaxWidth()
          // The header that used to sit above the picture also kept it clear of the status
          // bar. With the header gone and the activity in edge-to-edge mode, the video would
          // start underneath the clock and the cutout, so the inset moves here.
          .windowInsetsPadding(WindowInsets.statusBars)
          .aspectRatio(videoAspect ?: (16f / 9f))
      }

      Box(
        modifier = videoModifier
          .background(Color.Black)
          // Reported on every layout pass: rotating, entering full screen and shrinking into the
          // floating window each move the picture, and the source rectangle has to follow.
          .onGloballyPositioned { coordinates -> onVideoBounds(coordinates.boundsInWindow()) },
      ) {
        if (videoSurface != null && isPlaying && state.playerError == null && effectiveVariant != null) {
          videoSurface(Modifier.fillMaxSize())
        } else {
          VideoSurface(
            state = state,
            variant = effectiveVariant,
            onAction = onAction,
            onVideoAspectRatioChanged = { aspect -> videoAspect = aspect.takeIf { it > 0f } },
            zoomToFill = verticalFullBleed,
            paused = paused,
            playerError = state.playerError,
            onHttpFallback = { httpUrl -> httpFallbackUrl = httpUrl },
            onFatalError = { message -> onAction(UiAction.PlayerFailed(message)) },
            modifier = Modifier.fillMaxSize(),
          )
        }

        // Tap toggles the chrome; double tap toggles pause.
        //
        // This layer has to come *before* the controls, not after. In a Box the last child is
        // drawn on top and receives pointer events first, so declaring it last made it swallow
        // every tap meant for the strip's buttons — they looked live but
        // did nothing. Declared here, it sits under them and only sees taps that miss a
        // control.
        //
        // Full screen needs it just as much as portrait does: the strip hides itself after
        // [FULLSCREEN_HIDE_DELAY_MILLIS] and nothing else brought it back, so 清晰度, 刷新 and
        // 退出全屏 stayed unreachable for the rest of the session. The picture still starts
        // clean — the chrome is visible on entry and only comes back when tapped.
        //
        // Only over a picture, though. The error, offline and loading states put their own
        // buttons (重试, 返回) inside this box, and a full-size gesture layer drawn on top of
        // them takes every one of those taps: pressing 重试 only toggled the chrome. That is
        // the same mistake as declaring the layer after the controls, one level down.
        //
        // Not in the picture-in-picture window either: there is no chrome to show there, and a
        // double tap there is the system's (expand / close), not ours.
        if (!compact && effectiveVariant != null && state.playerError == null) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .pointerInput(Unit) {
                detectTapGestures(
                  onTap = { controlsVisible = !controlsVisible },
                  onDoubleTap = { onAction(UiAction.SetPlayerPaused(!current.playerPaused)) },
                )
              },
          )
        }

        // Only over the picture and its two spinners: the offline and error states carry their own
        // 重试 and 返回 pills in the middle of the screen, and a strip of player controls over them
        // would offer a second way to do nothing.
        val showsPicture = state.playerError == null &&
          (
            effectiveVariant != null ||
              state.loading ||
              (state.error == null && state.room?.isLive != false)
            )

        // Overlay danmaku in full screen and over a full-bleed vertical picture; the
        // portrait layout uses the list below the video instead.
        if (!compact && (fullscreen || verticalFullBleed) && effectiveVariant != null) {
          DanmakuOverlay(
            messages = state.danmaku,
            enabled = state.danmakuEnabled,
            blockedKeywords = state.blockedKeywords,
            opacity = state.danmakuOpacity,
            // Landscape uses its own scale: the overlay is much wider than in portrait.
            fontScale = if (fullscreen) {
              state.danmakuFontScale * state.landscapeDanmakuFontScale
            } else {
              state.danmakuFontScale
            },
            areaFraction = state.danmakuAreaFraction,
            // Under the status bar and nothing else. It used to be inset by the full-screen bar's
            // height as well; with the bar gone (round 144) that inset would leave an empty band.
            modifier = Modifier
              .fillMaxSize()
              .windowInsetsPadding(WindowInsets.statusBars)
              .padding(top = 4.dp, bottom = 16.dp),
          )
        }

        // A paused live stream keeps showing the last frame, which is indistinguishable
        // from a frozen one; a small badge says the user paused it on purpose. The badge
        // explains a double tap, which the thumbnail window does not have.
        if (!compact && paused && effectiveVariant != null) {
          Surface(
            shape = RoundedCornerShape(12.dp),
            color = Color.Black.copy(alpha = 0.55f),
            modifier = Modifier.align(Alignment.Center),
          ) {
            Row(
              modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              Icon(
                imageVector = Icons.Filled.PlayArrow,
                contentDescription = null,
                tint = Color.White.copy(alpha = 0.9f),
                modifier = Modifier.size(16.dp),
              )
              Text(
                text = "已暂停 · 双击继续",
                color = Color.White.copy(alpha = 0.9f),
                style = MaterialTheme.typography.labelSmall,
              )
            }
          }
        }

        // The strip is the last child so it sits above the danmaku overlay and receives taps before
        // the tap-to-toggle-chrome layer underneath it.
        if (!compact && controlsVisible && showsPicture) {
          PlayerOverlayStrip(
            state = state,
            fullscreen = fullscreen,
            verticalFullBleed = verticalFullBleed,
            // A landscape window the *user* turned has no portrait arrangement to go back to, so
            // 退出全屏 is not offered there — the way out is the back gesture, as in portrait. A
            // window the 全屏 button turned is the opposite case; see [exitReturnsToPortrait].
            showFullscreenToggle = exitReturnsToPortrait,
            onToggleFullscreen = {
              onFullscreenChange(!fullscreen)
              controlsVisible = true
            },
            onOpenSettings = onOpenSettings,
            onReload = { state.room?.let { onAction(UiAction.OpenRoom(it)) } },
            onAction = onAction,
            modifier = Modifier.align(Alignment.BottomCenter),
          )
        }
      }

      // The action row and the danmaku list need full width and a readable text size; neither
      // exists in a thumbnail, and the picture gets the whole window instead.
      if (!compact && !fullscreen && !verticalFullBleed) {
        RoomActionRow(state = state, onAction = onAction)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        // Bottom inset for the same reason as the top one: with edge-to-edge on, the last
        // danmaku row was drawn under the navigation bar and could not be read.
        DanmakuPanel(
          state = state,
          modifier = Modifier
            .weight(1f)
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars),
        )
      }
    }
  }
}

// ------------------------------------------------------------------- chrome ---

// The picture carries no top bar and no right-edge column any more (round 144); one strip along the
// bottom does everything they did. What changed, and why:
//
//  * **The follow star is gone.** Over a 16:9 picture the labelled 关注 pill in `RoomActionRow` sits
//    two lines below and is permanent, so the star was a second control for one action — and
//    取消关注 is the direction a viewer never needs mid-stream. Over a *vertical* picture, where no
//    action row exists, the strip offers 关注: once, and only while the room is not followed.
//  * **The back arrow is gone.** Every phone this app installs on has a back gesture, and
//    `LiveHubRoot` already turns it into 缩小; a 48dp arrow in the corner competed with the platform
//    instead of helping it. The error and offline states keep their own 返回 pill, because there the
//    gesture is the only other way out.
//  * **播放设置 / 刷新 / 全屏 moved from the right edge to the bottom.** A vertical column of discs
//    covered the middle of the picture — where the action is — and is not what any live app does:
//    斗鱼, 虎牙 and 哔哩哔哩 all put 弹幕 · 清晰度 · 全屏 in one bottom strip. 清晰度 is labelled with the
//    quality that is playing and opens the sheet the gear used to open, so four discs became four
//    controls that say what they do.

/**
 * Streamer name plus live dot and viewer count.
 *
 * Extracted so the full-bleed vertical layout (no list underneath) and the ordinary
 * portrait layout (list underneath) show the same thing the same way.
 */
@Composable
private fun StreamerLine(
  state: UiState,
  modifier: Modifier = Modifier,
  onSurface: Color = MaterialTheme.colorScheme.onSurface,
  secondary: Color = MaterialTheme.colorScheme.onSurfaceVariant,
  /**
   * Name on one line, the audience figure on the next.
   *
   * The strip's identity block is what four controls leave of a 320dp row — about 100dp — and that is
   * less than one line needs: the name is 70dp, " · 12.9万人在看" is another 83dp, so the second `Text`
   * was measured against the ~30dp that was left and, because a one-line `Text` clips by default, the
   * full-screen and full-bleed renders drew the name followed by a lone "·" (`17b` / `17c` / `17e`).
   * Stacked, each line gets the whole block. `RoomActionRow` keeps the inline form: it is full width,
   * and the length of the room title above it is what sets the height there.
   */
  stacked: Boolean = false,
) {
  val viewers = state.room?.viewerCount ?: 0
  val live = state.room?.isLive == true
  val name = state.room?.streamerName.orEmpty().ifBlank { "直播间" }
  // Inline keeps the " · " prefix because something precedes it on the line; the stacked form has no
  // preceding text, so the same figure without the separator reads as the second line of the block.
  val inlineMeta = if (viewers > 0) {
    " · ${ViewerCountFormatter.format(viewers)}人在看"
  } else {
    state.room?.platform?.displayName?.let { " · $it" }.orEmpty()
  }
  val stackedMeta = if (viewers > 0) {
    "${ViewerCountFormatter.format(viewers)}人在看"
  } else {
    state.room?.platform?.displayName.orEmpty()
  }

  if (stacked) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        LiveDot(live = live)
        StreamerName(name = name, color = onSurface)
      }
      if (stackedMeta.isNotBlank()) {
        Text(
          text = stackedMeta,
          style = MaterialTheme.typography.labelSmall,
          color = secondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  } else {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
      LiveDot(live = live)
      StreamerName(name = name, color = onSurface)
      Text(
        text = inlineMeta,
        style = MaterialTheme.typography.labelSmall,
        color = secondary,
        maxLines = 1,
      )
    }
  }
}

/** The 5dp "on air" dot that opens a name line. Renders nothing for an offline room. */
@Composable
private fun LiveDot(live: Boolean) {
  if (!live) return
  Box(
    modifier = Modifier
      .size(5.dp)
      .clip(CircleShape)
      .background(LiveBadgeColor),
  )
  Spacer(Modifier.width(4.dp))
}

/** The streamer's name: one line, ellipsised, and the boldest thing in the block. */
@Composable
private fun StreamerName(name: String, color: Color) {
  Text(
    text = name,
    style = MaterialTheme.typography.labelLarge,
    fontWeight = FontWeight.SemiBold,
    color = color,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
  )
}

/**
 * The player's only chrome: one strip along the bottom of the picture.
 *
 * Right-aligned controls with an identity block at the leading edge — the arrangement 斗鱼, 虎牙 and
 * 哔哩哔哩 use, and the reason the three round buttons no longer float down the right edge over the
 * middle of the picture.
 *
 * The identity block is drawn only where nothing else names the room: full screen, and a vertical
 * picture (neither composes `RoomActionRow`). Over a 16:9 portrait picture that row is two lines
 * below and already says all of it.
 */
@Composable
private fun PlayerOverlayStrip(
  state: UiState,
  fullscreen: Boolean,
  verticalFullBleed: Boolean,
  showFullscreenToggle: Boolean,
  onToggleFullscreen: () -> Unit,
  onOpenSettings: () -> Unit,
  onReload: () -> Unit,
  onAction: (UiAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  val followed = state.room?.let { room -> state.followed.any { it.key == room.key } } == true
  val quality = state.selectedVariant?.qualityLabel

  Row(
    modifier = modifier
      .fillMaxWidth()
      // A scrim so white text stays readable over a bright picture, and the navigation-bar inset so
      // the strip is not drawn under the gesture bar in full screen.
      .background(
        Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.62f))),
      )
      .windowInsetsPadding(WindowInsets.navigationBars)
      .padding(start = 12.dp, end = 6.dp, top = 12.dp, bottom = 6.dp),
    verticalAlignment = Alignment.Bottom,
  ) {
    if (fullscreen || verticalFullBleed) {
      Column(
        modifier = Modifier.weight(1f).padding(bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        StreamerLine(
          state = state,
          // Two lines: the four controls on the right leave about 100dp, which fits the name but not
          // the name and the audience figure; see the parameter's KDoc.
          stacked = true,
          onSurface = Color.White,
          secondary = Color.White.copy(alpha = 0.72f),
        )
        if (verticalFullBleed && !followed) {
          FollowPill { state.room?.let { onAction(UiAction.ToggleFollow(it)) } }
        }
      }
    } else {
      Spacer(Modifier.weight(1f))
    }

    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
      StripIcon(
        icon = if (state.danmakuEnabled) Icons.Filled.Subtitles else Icons.Filled.SubtitlesOff,
        description = if (state.danmakuEnabled) "关闭弹幕" else "显示弹幕",
        selected = state.danmakuEnabled,
        onClick = { onAction(UiAction.ToggleDanmaku(!state.danmakuEnabled)) },
      )
      StripQualityChip(label = quality ?: "清晰度", onClick = onOpenSettings)
      StripIcon(icon = Icons.Filled.Refresh, description = "刷新", onClick = onReload)
      if (showFullscreenToggle) {
        StripIcon(
          icon = if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
          description = if (fullscreen) "退出全屏" else "全屏",
          onClick = onToggleFullscreen,
        )
      }
    }
  }
}

/**
 * One icon in the strip: an official 48dp `IconButton` around a 38dp translucent disc.
 *
 * The disc keeps the glyph readable over a bright picture; the button is what makes the target big
 * enough for a thumb. A miss here does not merely fail to act — it falls through to the
 * tap-to-show-controls layer underneath, so the strip disappears, which reads as a broken control.
 */
@Composable
private fun StripIcon(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  description: String,
  onClick: () -> Unit,
  selected: Boolean = false,
) {
  IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
    Surface(
      modifier = Modifier.size(38.dp),
      shape = CircleShape,
      color = Color.White.copy(alpha = if (selected) 0.16f else 0.10f),
      border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = icon,
          contentDescription = description,
          tint = Color.White.copy(alpha = 0.92f),
          modifier = Modifier.size(20.dp),
        )
      }
    }
  }
}

/**
 * 清晰度, as a pill that shows the quality that is playing.
 *
 * A `Surface(onClick)` rather than an `IconButton`: the label is the point — 原画 is the value a
 * viewer looks for, and an icon for it would be one more thing to learn. `heightIn(min = 48.dp)`
 * keeps the touch target at Material's minimum while the pill itself stays 38dp like its neighbours.
 */
@Composable
private fun StripQualityChip(label: String, onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(19.dp),
    color = Color.White.copy(alpha = 0.10f),
    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.14f)),
    modifier = Modifier.heightIn(min = 48.dp),
  ) {
    Box(
      modifier = Modifier.padding(horizontal = 14.dp).heightIn(min = 38.dp),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = label,
        color = Color.White.copy(alpha = 0.92f),
        style = MaterialTheme.typography.labelMedium,
        maxLines = 1,
      )
    }
  }
}

/** 关注, for the vertical layout that has no action row. It only ever offers to follow. */
@Composable
private fun FollowPill(onClick: () -> Unit) {
  Surface(
    onClick = onClick,
    shape = RoundedCornerShape(16.dp),
    color = MaterialTheme.colorScheme.primary,
    modifier = Modifier.heightIn(min = 48.dp),
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp).heightIn(min = 32.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Icon(
        imageVector = Icons.Filled.StarBorder,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier.size(16.dp),
      )
      Text(
        text = "关注",
        color = Color.White,
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.SemiBold,
      )
    }
  }
}

// -------------------------------------------------------------------- video ---

@Composable
private fun VideoSurface(
  state: UiState,
  variant: StreamVariant?,
  onAction: (UiAction) -> Unit,
  onVideoAspectRatioChanged: (Float) -> Unit,
  zoomToFill: Boolean,
  paused: Boolean,
  playerError: String?,
  onHttpFallback: (String) -> Unit,
  onFatalError: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    when {
      // A fatal player error wins over a still-present variant: the URL is dead.
      variant != null && playerError == null -> LivePlayer(
        url = variant.url,
        isHls = variant.isHls,
        zoomToFill = zoomToFill,
        paused = paused,
        onVideoAspectRatioChanged = onVideoAspectRatioChanged,
        onFatalError = { error ->
          if (error.startsWith("http://") && !variant.url.startsWith("http://")) {
            onHttpFallback(error)
          } else {
            onFatalError(error)
          }
        },
        modifier = Modifier.fillMaxSize(),
      )

      state.loading -> Column(horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        Spacer(Modifier.height(10.dp))
        Text(
          text = "正在解析播放地址…",
          color = Color.White.copy(alpha = 0.75f),
          style = MaterialTheme.typography.labelMedium,
        )
      }

      // Offline and failure look identical to a user unless they are told apart: an
      // offline room is not a network problem and retrying will not help.
      playerError == null && state.room?.isLive == false -> Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Icon(
          imageVector = Icons.Outlined.Visibility,
          contentDescription = null,
          tint = Color.White.copy(alpha = 0.4f),
          modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
          text = "主播未开播",
          color = Color.White.copy(alpha = 0.92f),
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.Bold,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(4.dp))
        Text(
          text = "当前直播间没有在直播",
          color = Color.White.copy(alpha = 0.7f),
          style = MaterialTheme.typography.bodySmall,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        PillButton(label = "返回", filled = false) { onAction(UiAction.ClosePlayer) }
      }

      // Nothing to play and nothing to report: the resolve has not finished yet. Falling
      // through to the error branch here made a live room render as "无法播放该直播间" for the
      // moment between opening it and the first variant arriving — and left it there when
      // the resolve reported progress through `loading` only.
      playerError == null && state.error == null -> Column(
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 2.dp)
        Spacer(Modifier.height(10.dp))
        Text(
          text = "正在解析播放地址…",
          color = Color.White.copy(alpha = 0.75f),
          style = MaterialTheme.typography.labelMedium,
        )
      }

      else -> Column(
        modifier = Modifier.padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Icon(
          imageVector = Icons.Outlined.Visibility,
          contentDescription = null,
          tint = Color.White.copy(alpha = 0.4f),
          modifier = Modifier.size(32.dp),
        )
        Spacer(Modifier.height(8.dp))
        Text(
          text = playerError ?: state.error ?: "无法播放该直播间",
          color = Color.White.copy(alpha = 0.9f),
          style = MaterialTheme.typography.bodySmall,
          textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
          PillButton(
            label = "重试",
            filled = true,
            onClick = { state.room?.let { onAction(UiAction.OpenRoom(it)) } },
          )
          PillButton(label = "返回", filled = false) { onAction(UiAction.ClosePlayer) }
        }
      }
    }
  }
}

// --------------------------------------------------------------- action row ---

/**
 * The public link to a room, spelled from the platform's own home page.
 *
 * Nothing has to be resolved for it: the room id the app already holds *is* the last path
 * segment of the platform's live page on all four platforms, so sharing is a string operation
 * rather than a network round trip.
 *
 * Extracted from the chooser because the host/id pairing is the part that can be wrong
 * silently: a link to the wrong host still looks like a link, and the only symptom is that the
 * person who receives it lands on a 404.
 */
internal fun shareUrl(room: Streamer): String = "${room.platform.homePage}/${room.roomId}"

/**
 * The chooser that hands a room to whichever app the user shares with.
 *
 * `text/plain` rather than a link type: the payload is a line of prose followed by a URL, and
 * `text/plain` is what chat, notes and mail apps register for — a `text/x-uri` intent would only
 * reach the few that claim links specifically.
 *
 * The subject carries the title so a mail arrives titled instead of blank, and the body repeats
 * it because most share targets display the body alone.
 */
private fun shareChooser(room: Streamer): Intent {
  val title = room.title.ifBlank { "直播中" }
  return Intent.createChooser(
    Intent(Intent.ACTION_SEND)
      .setType("text/plain")
      .putExtra(Intent.EXTRA_SUBJECT, title)
      .putExtra(
        Intent.EXTRA_TEXT,
        "${room.streamerName} 正在直播：${title}\n${shareUrl(room)}",
      ),
    "分享直播间",
  )
}

@Composable
private fun RoomActionRow(state: UiState, onAction: (UiAction) -> Unit) {
  // The composition's own context, so the chooser opens *over* the player rather than as a new
  // task: `PlayerScreen` is only ever composed inside the activity.
  val context = LocalContext.current
  val followed = remember(state.followed, state.room) {
    state.room?.let { room -> state.followed.any { it.key == room.key } } ?: false
  }

  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
    // The streamer line leads, because the header that used to show it is gone. Title and
    // platform follow, so the order still reads "who is streaming, what about, where".
    StreamerLine(state = state)
    Spacer(Modifier.height(4.dp))
    Text(
      text = state.room?.title.orEmpty().ifBlank { "直播中" },
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
    val subtitle = buildString {
      state.room?.platform?.displayName?.let { append(it) }
      state.selectedSubCategory?.name?.let {
        if (isNotEmpty()) append(" · ")
        append(it)
      }
      state.room?.categoryName?.takeIf { it.isNotBlank() }?.let {
        if (isNotEmpty()) append(" · ")
        append(it)
      }
    }
    if (subtitle.isNotBlank()) {
      Spacer(Modifier.height(2.dp))
      Text(
        text = subtitle,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Spacer(Modifier.height(8.dp))
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      PillButton(
        label = if (followed) "已关注" else "关注",
        filled = !followed,
        onClick = { state.room?.let { onAction(UiAction.ToggleFollow(it)) } },
      )
      // A room a viewer cannot hand to anyone else is a room only they will ever watch, and the
      // app had no way out of itself at all — no share, no clipboard.
      //
      // The same pill as 关注 instead of a third style in this row, which also gives it the 48dp
      // target and the ripple for free. Only drawn when there is a room to link to; the thumbnail
      // window and full screen never compose this row at all (see the guard around
      // `RoomActionRow`), so the pill is never on a surface a finger cannot aim at.
      state.room?.let { room ->
        PillButton(
          label = "分享",
          filled = false,
          onClick = { context.startActivity(shareChooser(room)) },
        )
      }
      Spacer(Modifier.weight(1f))
      // A section label, not a count.
      //
      // This read "弹幕 37" and the number was `state.danmaku.size` — the *render buffer*, capped at
      // 200 (`MAX_DANMAKU_BUFFER`). So it climbed while a room was watched, pinned at 200, and
      // reset whenever the player closed: a live number that answered no question anyone asks
      // (it is not "messages so far", not "viewers", not "unread"). The panel underneath already
      // says whether the socket is connecting or failed, which is the only thing worth stating
      // here.
      if (state.danmakuEnabled) {
        Text(
          text = "弹幕",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Text(
          text = "弹幕已关闭",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}

// ---------------------------------------------------------------- settings ---

/**
 * Quality, line and danmaku settings.
 *
 * Internal rather than private so a test can compose it at a landscape size and prove the
 * lower sections are reachable; a private composable can only be reached through the full
 * screen, which on a device needs a real rotation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun PlayerSettings(
  state: UiState,
  variant: StreamVariant?,
  onAction: (UiAction) -> Unit,
) {
  // The settings sheet is the same composable in portrait and landscape, so it asks the
  // window itself rather than taking a parameter the caller could get wrong.
  val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
  Column(
    modifier = Modifier
      .fillMaxWidth()
      // Scrollable because the content is taller than the space a bottom sheet gets on a
      // phone in landscape: quality, line, on/off, area, size, opacity and the landscape
      // size row together do not fit, and without this they were unreachable rather than
      // merely off-screen.
      .verticalScroll(rememberScrollState())
      .padding(horizontal = 16.dp)
      .padding(bottom = 24.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text("播放设置", style = MaterialTheme.typography.titleMedium)

    if (state.variants.isNotEmpty()) {
      SettingsLabel("清晰度")
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        state.variants.forEach { item ->
          LiveHubFilterChip(
            selected = item.qualityCode == variant?.qualityCode,
            onClick = {
              // A quality code means "re-resolve for this rate"; a variant selected by
              // identity is the one already carrying a URL.
              val code = item.qualityCode
              if (code != null && state.lines.isNotEmpty()) {
                onAction(UiAction.SelectQuality(code))
              } else {
                onAction(UiAction.SelectVariant(item))
              }
            },
            label = { Text(item.qualityLabel, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
          )
        }
      }
    }

    // Lines are a separate axis only where the platform resolves one URL per quality
    // against a chosen CDN (Douyu). For the others every line already produced its own
    // variant, so a second row would duplicate the chips above.
    if (state.lines.size > 1) {
      SettingsLabel("线路")
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        state.lines.forEach { line ->
          LiveHubFilterChip(
            selected = line == state.selectedLine,
            onClick = { onAction(UiAction.SelectLine(line)) },
            label = { Text(line, style = MaterialTheme.typography.labelMedium, maxLines = 1) },
          )
        }
      }
    }

    if (state.switchingQuality) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
        Spacer(Modifier.width(8.dp))
        Text("正在切换清晰度…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }

    SettingsLabel("弹幕")
    // One switch, the same official control the 设置 page uses for this very setting. The two
    // chips labelled 开 and 关 carried the state in their selection and the action in their click,
    // so neither read as "this is how it is now" — and the same stored value had two different
    // control shapes depending on which screen the user was on.
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("显示弹幕", style = MaterialTheme.typography.bodyMedium)
      Spacer(Modifier.weight(1f))
      Switch(
        checked = state.danmakuEnabled,
        onCheckedChange = { onAction(UiAction.ToggleDanmaku(it)) },
        modifier = Modifier.testTag(DANMAKU_SWITCH_TAG),
      )
    }

    SettingsLabel("弹幕显示区域")
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      DANMAKU_AREAS.forEach { (label, fraction) ->
        LiveHubFilterChip(
          selected = state.danmakuAreaFraction == fraction,
          onClick = { onAction(UiAction.SetDanmakuAreaFraction(fraction)) },
          label = { Text(label) },
        )
      }
    }

    SettingsLabel("弹幕字号")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      DANMAKU_FONT_SCALES.forEach { (label, scale) ->
        LiveHubFilterChip(
          selected = state.danmakuFontScale == scale,
          onClick = { onAction(UiAction.SetDanmakuFontScale(scale)) },
          label = { Text(label) },
        )
      }
    }

    // Opacity was persisted but never editable: the value existed in preferences with no
    // control writing it, so a user could not dim the danmaku over a bright picture.
    SettingsLabel("弹幕透明度")
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      DANMAKU_OPACITIES.forEach { (label, value) ->
        LiveHubFilterChip(
          selected = kotlin.math.abs(state.danmakuOpacity - value) < 0.02f,
          onClick = { onAction(UiAction.SetDanmakuOpacity(value)) },
          label = { Text(label) },
        )
      }
    }

    // The overlay is much wider in landscape, so the reference client scales the text up
    // separately there; sharing one scale made full-screen danmaku tiny.
    if (isLandscape) {
      SettingsLabel("横屏弹幕字号")
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        LANDSCAPE_DANMAKU_FONT_SCALES.forEach { (label, scale) ->
          LiveHubFilterChip(
            selected = state.landscapeDanmakuFontScale == scale,
            onClick = { onAction(UiAction.SetLandscapeDanmakuFontScale(scale)) },
            label = { Text(label) },
          )
        }
      }
    }
  }
}

@Composable
private fun SettingsLabel(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.labelLarge,
    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.75f),
  )
}

// ----------------------------------------------------------------- danmaku ---

/**
 * The portrait danmaku list.
 *
 * Internal rather than private so the ordering can be asserted without composing the whole
 * player — the bug it guards against is an ordering one, and a screenshot cannot tell
 * "newest at the bottom" from "newest at the top" when both look like chat.
 */
@Composable
internal fun DanmakuPanel(state: UiState, modifier: Modifier = Modifier) {
  // Blocked keywords used to be applied only inside the overlay, so the portrait list
  // showed every word the user had explicitly filtered out.
  val visible = remember(state.danmaku, state.blockedKeywords) {
    state.danmaku.withoutBlockedKeywords(state.blockedKeywords)
  }
  when {
    !state.danmakuEnabled -> CenteredNotice(
      modifier = modifier,
      icon = Icons.Outlined.ChatBubbleOutline,
      text = "弹幕已关闭",
    )

    // An empty list after filtering means the stream is alive but everything in it was
    // blocked; saying so is clearer than an endless "connecting" spinner.
    state.danmaku.isEmpty() -> {
      // An empty list is ambiguous on its own — "nothing said yet" and "never connected" look
      // identical — so the status decides which of the two the user is looking at. Before this
      // every failure rendered as an endless "正在连接弹幕…", with the platform's own reason
      // discarded in the ViewModel.
      when (val status = state.danmakuStatus) {
        is DanmakuStatus.Failed -> CenteredNotice(
          modifier = modifier,
          icon = Icons.Outlined.ChatBubbleOutline,
          text = "弹幕连接失败：${status.reason}",
        )

        // Quiet on purpose: the adapter is already retrying, and a red line over a stream that
        // reconnects by itself is worse than no line at all.
        is DanmakuStatus.Reconnecting -> CenteredNotice(
          modifier = modifier,
          text = "弹幕正在重连…",
          showSpinner = true,
        )

        DanmakuStatus.Connected -> CenteredNotice(
          modifier = modifier,
          icon = Icons.Outlined.ChatBubbleOutline,
          text = "还没有人说话",
        )

        DanmakuStatus.Connecting -> CenteredNotice(
          modifier = modifier,
          text = "正在连接弹幕…",
          showSpinner = true,
        )
      }
    }

    visible.isEmpty() -> CenteredNotice(
      modifier = modifier,
      icon = Icons.Outlined.ChatBubbleOutline,
      text = "已屏蔽全部弹幕（${state.blockedKeywords.size} 个屏蔽词）",
    )

    else -> DanmakuList(messages = visible, modifier = modifier)
  }
}

@Composable
private fun DanmakuList(
  messages: List<com.livehub.core.model.DanmakuMessage>,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  // Oldest at the top, newest appended at the bottom — the order every live chat uses.
  //
  // This used to be `asReversed()` plus `animateScrollToItem(0)`: newest at the top, and
  // the list yanked back to position 0 on every message. The two cancelled out, so a new
  // message pushed every existing row down for one frame and was then pulled back up with
  // the rest. On screen that reads as chat scrolling *upwards*, which is why it looked
  // reversed even though the ordering was documented as "newest first".

  /**
   * Whether the viewport is close enough to the tail to follow it.
   *
   * Two details, both of which broke the naive version:
   *
   *  * `layoutInfo` has to be observed through `derivedStateOf`. A plain read during
   *    composition sees the *previous* frame's layout.
   *  * The check is tolerant by one row. When a message arrives the list is already one item
   *    longer than what was last laid out, so an exact `lastVisible == lastIndex` test
   *    reports "not at the bottom" for the very message that should scroll it — and the
   *    tail is then never followed at all.
   */
  val atBottom by remember {
    derivedStateOf {
      val info = listState.layoutInfo
      if (info.totalItemsCount == 0) return@derivedStateOf true
      val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: -1
      lastVisible >= info.totalItemsCount - 2
    }
  }

  /**
   * The newest message, which is what "follow the tail" has to react to.
   *
   * Keyed on the message rather than on `messages.size`: the ViewModel's buffer is a rolling window
   * capped at `MAX_DANMAKU_BUFFER`, so once a busy room fills it the length stops changing and a
   * size-keyed effect would never run again — the list would sit frozen on old rows while new
   * danmaku kept arriving. `lastOrNull()` keeps changing for every arrival, cap or not.
   */
  val newest = messages.lastOrNull()

  LaunchedEffect(newest) {
    // Only follow the tail when the user is already there, so scrolling back through older
    // messages is not interrupted by every new danmaku. `atBottom` is read here, not
    // captured: `derivedStateOf` recomputes from the live layout, so this sees the position
    // as of the new message rather than the one from when the effect was created.
    if (messages.isNotEmpty() && atBottom) {
      listState.animateScrollToItem(messages.lastIndex)
    }
  }

  LazyColumn(
    modifier = modifier,
    state = listState,
    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    itemsIndexed(
      messages,
      // Identity, not position. The key used to be `"$receivedAt:$index"`, and once the rolling
      // buffer is full every arrival shifts **every** index by one — so every key changed on every
      // message and the whole visible list was rebuilt instead of one row entering at the bottom
      // and one leaving at the top. `seq` stays put as the buffer slides; the negative-index
      // fallback keeps hand-built lists (tests, screenshot fixtures, `seq` 0) unique. Both branches
      // are `Long` so a list that mixes stamped and unstamped entries still has one key type.
      key = { index, message -> if (message.seq != 0L) message.seq else -(index.toLong() + 1L) },
    ) { _, message ->
      DanmakuRow(user = message.userName, content = message.text)
    }
  }
}

@Composable
private fun DanmakuRow(user: String, content: String) {
  BoxWithConstraints {
    val nameWidth = (maxWidth * 0.32f).coerceAtMost(120.dp)
    Row {
      Text(
        text = "${user.ifBlank { "匿名" }}：",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = nameWidth),
      )
      Text(
        text = content,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurface,
      )
    }
  }
}

@Composable
private fun CenteredNotice(
  modifier: Modifier = Modifier,
  icon: androidx.compose.ui.graphics.vector.ImageVector? = null,
  text: String,
  showSpinner: Boolean = false,
) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      if (showSpinner) {
        CircularProgressIndicator(
          color = MaterialTheme.colorScheme.primary,
          strokeWidth = 2.dp,
          modifier = Modifier.size(20.dp),
        )
      } else if (icon != null) {
        Icon(
          imageVector = icon,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
          modifier = Modifier.size(28.dp),
        )
      }
      if (showSpinner || icon != null) Spacer(Modifier.height(8.dp))
      Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

// ----------------------------------------------------------------- helpers ---

/**
 * A pill-shaped action: the official `Button`, recoloured for the two emphases this screen uses.
 *
 * What is left here is only the look — a 20dp corner, a filled/tonal pair of colours and tighter
 * padding. Everything the hand-rolled version used to spell out (a 48dp ring around a `Surface`
 * with a `Modifier.clickable` on it, the `Role.Button` semantics a test and TalkBack depend on, the
 * press ripple, `labelLarge`) is what `Button` is, so none of it is written twice.
 */
@Composable
private fun PillButton(label: String, filled: Boolean, onClick: () -> Unit) {
  Button(
    onClick = onClick,
    shape = RoundedCornerShape(20.dp),
    colors = ButtonDefaults.buttonColors(
      containerColor = if (filled) {
        MaterialTheme.colorScheme.primary
      } else {
        MaterialTheme.colorScheme.surfaceVariant
      },
      contentColor = if (filled) {
        MaterialTheme.colorScheme.onPrimary
      } else {
        MaterialTheme.colorScheme.onSurfaceVariant
      },
    ),
    contentPadding = PaddingValues(horizontal = 18.dp, vertical = 7.dp),
  ) {
    Text(text = label, style = MaterialTheme.typography.labelLarge)
  }
}

private val DANMAKU_AREAS = listOf(
  "上1/4" to 0.25f,
  "上1/2" to 0.5f,
  "上3/4" to 0.75f,
  "全屏" to 1.0f,
)

private val DANMAKU_FONT_SCALES = listOf(
  "小" to 0.9f,
  "中" to 1.0f,
  "大" to 1.15f,
)

/** Values match the reference client's four presets. */
private val DANMAKU_OPACITIES = listOf(
  "100%" to 1.0f,
  "80%" to 0.85f,
  "60%" to 0.70f,
  "40%" to 0.55f,
)

private val LANDSCAPE_DANMAKU_FONT_SCALES = listOf(
  "小" to 1.0f,
  "中" to 1.15f,
  "大" to 1.30f,
  "特大" to 1.45f,
)

private const val PORTRAIT_HIDE_DELAY_MILLIS = 4_000L
private const val FULLSCREEN_HIDE_DELAY_MILLIS = 3_000L

/**
 * Tag for the 显示弹幕 switch, for `PlayerControlsTest`.
 *
 * The switch carries no text of its own — 「显示弹幕」 is the node beside it — so a tag is the only
 * way to address the control rather than its label, and "is it on, and does a tap ask for the
 * opposite?" is an assertion about the control. Same shape and same reason as the settings
 * screen's switch tags (`BACKGROUND_REFRESH_SWITCH_TAG`).
 */
internal const val DANMAKU_SWITCH_TAG = "danmakuSwitch"
