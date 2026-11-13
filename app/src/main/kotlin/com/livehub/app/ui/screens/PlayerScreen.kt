package com.livehub.app.ui.screens

import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.BackSwipeContainer
import com.livehub.app.ui.DanmakuStatus
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.app.ui.player.DanmakuOverlay
import com.livehub.app.ui.player.LivePlayer
import com.livehub.app.ui.player.withoutBlockedKeywords
import com.livehub.core.common.ViewerCountFormatter
import com.livehub.core.designsystem.LiveBadgeColor
import com.livehub.core.model.StreamVariant
import kotlinx.coroutines.delay

/**
 * Live room screen.
 *
 * Mirrors the reference player's shape:
 *
 *  * a pill-shaped glass header (back, identity, follow, close) above the video,
 *  * a 16:9 video surface,
 *  * a vertical column of round controls (settings / reload / fullscreen) on the right,
 *  * the danmaku list filling the rest of the screen,
 *  * a settings sheet holding quality, line and every danmaku option, because those do
 *    not fit in a chip row without crowding the picture,
 *  * full screen keeping only video plus the floating overlay.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
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
) {
  var fullscreen by remember { mutableStateOf(false) }
  var showSettings by remember { mutableStateOf(false) }
  // In landscape the bottom sheet has only the height of a landscape window to work with,
  // and the settings list is taller than that. A left-hand drawer uses the axis that has
  // room instead — the same reason the reference client switches to a drawer there.
  val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

  BackHandler(enabled = fullscreen && !compact) { fullscreen = false }

  Box(modifier = Modifier.fillMaxSize()) {
    // Swipe from the left edge to step off the player. It used to close the room; now it minimises
    // it, which is the gesture's whole point — the picture keeps going in the corner.
    BackSwipeContainer(onClose = { onAction(UiAction.MinimizePlayer) }) {
      PlayerContent(
        state = state,
        onAction = onAction,
        fullscreen = fullscreen,
        onFullscreenChange = { fullscreen = it },
        onOpenSettings = { showSettings = true },
        compact = compact,
      )
    }

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
) {
  val variant: StreamVariant? = state.selectedVariant
  var controlsVisible by remember { mutableStateOf(true) }
  // Pause lives in `UiState`, not in a `remember`.
  //
  // It used to be a local flag, which made it invisible to everything outside this composable —
  // including the activity, which now pauses the stream when the app is hidden without a
  // picture-in-picture window. See `LiveHubApp` and [UiAction.SetPlayerPaused].
  val paused = state.playerPaused
  // The gesture detector below is created once per composition and reads `state` from *that*
  // composition; `rememberUpdatedState` is what keeps a double tap from toggling a stale value.
  val current by rememberUpdatedState(state)
  // Learned from the decoder. Null until the first frame is sized, which keeps the
  // surface at 16:9 instead of collapsing to zero height on open.
  var videoAspect by remember(state.room?.key) { mutableStateOf<Float?>(null) }
  // Set when a CDN hostname cannot pass TLS; the same URL is retried over http.
  var httpFallbackUrl by remember(state.room?.key) { mutableStateOf<String?>(null) }

  val isPlaying = variant != null && !state.loading
  LaunchedEffect(controlsVisible, variant?.url, isPlaying, fullscreen) {
    if (controlsVisible && isPlaying) {
      delay(if (fullscreen) FULLSCREEN_HIDE_DELAY_MILLIS else PORTRAIT_HIDE_DELAY_MILLIS)
      controlsVisible = false
    }
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

      Box(modifier = videoModifier.background(Color.Black)) {
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
        // every tap meant for the gear, reload and fullscreen buttons — they looked live but
        // did nothing. Declared here, it sits under them and only sees taps that miss a
        // control.
        //
        // Full screen needs it just as much as portrait does: its bar hides itself after
        // [FULLSCREEN_HIDE_DELAY_MILLIS] and nothing else brought it back, so 设置, 重载 and
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

        if (!compact && verticalFullBleed) {
          // A vertical stream fills the screen, so there is no list underneath and the
          // streamer name would have nowhere else to go. This one line is all that is
          // left of the old header, and it carries no buttons.
          StreamerLine(
            state = state,
            modifier = Modifier
              .align(Alignment.TopCenter)
              .fillMaxWidth()
              .background(
                Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.6f), Color.Transparent)),
              )
              .padding(horizontal = 12.dp, vertical = 10.dp),
            onSurface = Color.White,
          )
        }

        // In full screen the bar floats over the picture instead of sitting above it.
        if (!compact && fullscreen && controlsVisible) {
          FullscreenBar(
            state = state,
            onExit = { onFullscreenChange(false) },
            onAction = onAction,
            modifier = Modifier.align(Alignment.TopCenter),
          )
        }

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
            modifier = Modifier.fillMaxSize().padding(top = 56.dp, bottom = 16.dp),
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

        // Round controls down the right edge, as in the reference player.
        if (!compact && controlsVisible) {
          SideControls(
            fullscreen = fullscreen,
            onToggleFullscreen = {
              onFullscreenChange(!fullscreen)
              controlsVisible = true
            },
            onOpenSettings = onOpenSettings,
            onReload = { state.room?.let { onAction(UiAction.OpenRoom(it)) } },
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 14.dp),
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

// ------------------------------------------------------------------- header ---

// The portrait header used to be a floating pill with a back arrow, a star and a close
// button. All three are gone on purpose:
//
//  * back and close both called `ClosePlayer`, so the screen carried two ways to do one
//    thing while a third — the system back gesture, and the left-edge swipe in
//    `LiveHubRoot` — already worked everywhere;
//  * the star duplicated the 关注 button in `RoomActionRow`, which is larger, labelled and
//    therefore the one a user can actually find.
//
// What the pill also showed (streamer, live dot, viewer count) was not decoration, so it
// moved into `RoomActionRow` rather than disappearing with the container.

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
) {
  val viewers = state.room?.viewerCount ?: 0
  Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
    if (state.room?.isLive == true) {
      Box(
        modifier = Modifier
          .size(5.dp)
          .clip(CircleShape)
          .background(LiveBadgeColor),
      )
      Spacer(Modifier.width(4.dp))
    }
    Text(
      text = state.room?.streamerName.orEmpty().ifBlank { "直播间" },
      style = MaterialTheme.typography.labelLarge,
      fontWeight = FontWeight.SemiBold,
      color = onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    Text(
      text = if (viewers > 0) {
        " · ${ViewerCountFormatter.format(viewers)}人在看"
      } else {
        state.room?.platform?.displayName?.let { " · $it" }.orEmpty()
      },
      style = MaterialTheme.typography.labelSmall,
      color = secondary,
      maxLines = 1,
    )
  }
}

@Composable
private fun FullscreenBar(
  state: UiState,
  onExit: () -> Unit,
  onAction: (UiAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  val followed = state.room?.let { room -> state.followed.any { it.key == room.key } } == true

  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)))
      .padding(horizontal = 6.dp, vertical = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    // Both glyphs keep their size and gain a 48dp target: these two are drawn over the picture
    // with no disc behind them, so a miss does not look like a miss — it looks like the button was
    // never there. `size(34.dp)` on the *clickable* node shrank the target along with the icon.
    Icon(
      imageVector = Icons.AutoMirrored.Filled.ArrowBack,
      contentDescription = "退出全屏",
      tint = Color.White,
      modifier = Modifier
        .size(48.dp)
        .clip(CircleShape)
        .clickable(onClick = onExit)
        .padding(12.dp),
    )
    Text(
      text = state.room?.streamerName.orEmpty().ifBlank { "直播间" },
      color = Color.White,
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Icon(
      imageVector = if (followed) Icons.Filled.Star else Icons.Filled.StarBorder,
      contentDescription = "关注",
      tint = Color.White,
      modifier = Modifier
        .size(48.dp)
        .clip(CircleShape)
        .clickable { state.room?.let { onAction(UiAction.ToggleFollow(it)) } }
        .padding(13.dp),
    )
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

@Composable
private fun SideControls(
  fullscreen: Boolean,
  onToggleFullscreen: () -> Unit,
  onOpenSettings: () -> Unit,
  onReload: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Column(
    modifier = modifier,
    verticalArrangement = Arrangement.spacedBy(14.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    ControlFab(icon = Icons.Filled.Settings, description = "播放设置", onClick = onOpenSettings)
    ControlFab(icon = Icons.Filled.Refresh, description = "刷新", onClick = onReload)
    ControlFab(
      icon = if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen,
      description = if (fullscreen) "退出全屏" else "全屏",
      onClick = onToggleFullscreen,
    )
  }
}

@Composable
private fun ControlFab(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  description: String,
  onClick: () -> Unit,
) {
  // 48dp touch target around a 38dp disc.
  //
  // The `Surface` used to be the clickable node at 38dp, which is below the 48dp minimum. These
  // float over the video, so a miss does not just fail to open the sheet — it toggles the chrome
  // (the tap-to-show-controls layer underneath) and the buttons disappear, which reads as a
  // broken control rather than as a missed tap.
  Box(
    modifier = Modifier.size(48.dp).clip(CircleShape).clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      modifier = Modifier.size(38.dp),
      shape = CircleShape,
      color = Color.White.copy(alpha = 0.10f),
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

// --------------------------------------------------------------- action row ---

@Composable
private fun RoomActionRow(state: UiState, onAction: (UiAction) -> Unit) {
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
          FilterChip(
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
          FilterChip(
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
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(
        selected = state.danmakuEnabled,
        onClick = { onAction(UiAction.ToggleDanmaku(true)) },
        label = { Text("开") },
      )
      FilterChip(
        selected = !state.danmakuEnabled,
        onClick = { onAction(UiAction.ToggleDanmaku(false)) },
        label = { Text("关") },
      )
    }

    SettingsLabel("弹幕显示区域")
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      DANMAKU_AREAS.forEach { (label, fraction) ->
        FilterChip(
          selected = state.danmakuAreaFraction == fraction,
          onClick = { onAction(UiAction.SetDanmakuAreaFraction(fraction)) },
          label = { Text(label) },
        )
      }
    }

    SettingsLabel("弹幕字号")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      DANMAKU_FONT_SCALES.forEach { (label, scale) ->
        FilterChip(
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
        FilterChip(
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
          FilterChip(
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

  LaunchedEffect(messages.size) {
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
      key = { index, message -> "${message.receivedAt}:$index" },
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

@Composable
private fun PillButton(label: String, filled: Boolean, onClick: () -> Unit) {
  // 48dp of touch target around the pill, which is about 34dp tall on its own.
  //
  // The `Surface` used to be the clickable node and its height came from the label plus 7dp of
  // vertical padding — well under the 48dp minimum. 关注, 重试 and 返回 are the most consequential
  // controls on the screen (a missed 重试 leaves a dead stream on screen). The pill itself is drawn
  // exactly as before; what grows is the ring around it, which is also where the press ripple now
  // reaches.
  Box(
    modifier = Modifier
      .heightIn(min = 48.dp)
      .clip(RoundedCornerShape(24.dp))
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    Surface(
      color = if (filled) {
        MaterialTheme.colorScheme.primary
      } else {
        MaterialTheme.colorScheme.surfaceVariant
      },
      shape = RoundedCornerShape(20.dp),
    ) {
      Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (filled) {
          MaterialTheme.colorScheme.onPrimary
        } else {
          MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.padding(horizontal = 18.dp, vertical = 7.dp),
      )
    }
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
