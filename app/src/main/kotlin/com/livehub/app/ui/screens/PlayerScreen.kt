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
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
fun PlayerScreen(state: UiState, onAction: (UiAction) -> Unit) {
  var fullscreen by remember { mutableStateOf(false) }
  var showSettings by remember { mutableStateOf(false) }
  // In landscape the bottom sheet has only the height of a landscape window to work with,
  // and the settings list is taller than that. A left-hand drawer uses the axis that has
  // room instead — the same reason the reference client switches to a drawer there.
  val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE

  BackHandler(enabled = fullscreen) { fullscreen = false }

  Box(modifier = Modifier.fillMaxSize()) {
    PlayerContent(
      state = state,
      onAction = onAction,
      fullscreen = fullscreen,
      onFullscreenChange = { fullscreen = it },
      onOpenSettings = { showSettings = true },
    )

    if (showSettings && isLandscape) {
      PlayerSettingsDrawer(onDismiss = { showSettings = false }) {
        PlayerSettings(state = state, variant = state.selectedVariant, onAction = onAction)
      }
    }
  }

  if (showSettings && !isLandscape) {
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
) {
  val variant: StreamVariant? = state.selectedVariant
  var controlsVisible by remember { mutableStateOf(true) }
  /** Pause is view-local: leaving the screen always resumes, so it never sticks. */
  var paused by remember(state.room?.key) { mutableStateOf(false) }
  // Learned from the decoder. Null until the first frame is sized, which keeps the
  // surface at 16:9 instead of collapsing to zero height on open.
  var videoAspect by remember(state.room?.key) { mutableStateOf<Float?>(null) }
  // Set when a CDN hostname cannot pass TLS; the same URL is retried over http.
  var httpFallbackUrl by remember(state.room?.key) { mutableStateOf<String?>(null) }
  // Set when Media3 gives up after its one automatic retry.
  var playerError by remember(state.room?.key) { mutableStateOf<String?>(null) }

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
  val verticalFullBleed = !fullscreen && isVerticalVideo

  Box(
    modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      if (!fullscreen && !verticalFullBleed) {
        PlayerHeader(state = state, onAction = onAction, modifier = Modifier.fillMaxWidth())
      }

      val videoModifier = when {
        fullscreen || verticalFullBleed -> Modifier.fillMaxSize()
        else -> Modifier.fillMaxWidth().aspectRatio(videoAspect ?: (16f / 9f))
      }

      Box(modifier = videoModifier.background(Color.Black)) {
        if (videoSurface != null && isPlaying && playerError == null && effectiveVariant != null) {
          videoSurface(Modifier.fillMaxSize())
        } else {
          VideoSurface(
            state = state,
            variant = effectiveVariant,
            onAction = onAction,
            onVideoAspectRatioChanged = { aspect -> videoAspect = aspect.takeIf { it > 0f } },
            zoomToFill = verticalFullBleed,
            paused = paused,
            playerError = playerError,
            onHttpFallback = { httpUrl -> httpFallbackUrl = httpUrl },
            onFatalError = { message ->
              // The ViewModel owns the retry path; re-opening the room is what the user's
              // "重试" button does, so a fatal player error just clears the variant and lets
              // the error state take over.
              playerError = message
            },
            modifier = Modifier.fillMaxSize(),
          )
        }

        // Tap toggles the chrome; double tap toggles pause.
        //
        // This layer has to come *before* the controls, not after. In a Box the last child is
        // drawn on top and receives pointer events first, so declaring it last made it swallow
        // every tap meant for the gear, reload and fullscreen buttons — they looked live but
        // did nothing. Declared here, it sits under them and only sees taps that miss a
        // control. Portrait only: full screen keeps the picture clean and relies on the bar.
        if (!fullscreen) {
          Box(
            modifier = Modifier
              .fillMaxSize()
              .pointerInput(Unit) {
                detectTapGestures(
                  onTap = { controlsVisible = !controlsVisible },
                  onDoubleTap = { paused = !paused },
                )
              },
          )
        }

        if (verticalFullBleed) {
          PlayerHeader(state = state, onAction = onAction, modifier = Modifier.align(Alignment.TopCenter))
        }

        // In full screen the bar floats over the picture instead of sitting above it.
        if (fullscreen && controlsVisible) {
          FullscreenBar(
            state = state,
            onExit = { onFullscreenChange(false) },
            onAction = onAction,
            modifier = Modifier.align(Alignment.TopCenter),
          )
        }

        // Overlay danmaku in full screen and over a full-bleed vertical picture; the
        // portrait layout uses the list below the video instead.
        if ((fullscreen || verticalFullBleed) && effectiveVariant != null) {
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
        // from a frozen one; a small badge says the user paused it on purpose.
        if (paused && effectiveVariant != null) {
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
        if (controlsVisible) {
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

      if (!fullscreen && !verticalFullBleed) {
        RoomActionRow(state = state, onAction = onAction)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        DanmakuPanel(state = state, modifier = Modifier.weight(1f).fillMaxWidth())
      }
    }
  }
}

// ------------------------------------------------------------------- header ---

@Composable
private fun PlayerHeader(
  state: UiState,
  onAction: (UiAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  val followed = remember(state.followed, state.room) {
    state.room?.let { room -> state.followed.any { it.key == room.key } } ?: false
  }

  Row(
    modifier = modifier.padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Surface(
      modifier = Modifier.weight(1f).height(40.dp),
      shape = RoundedCornerShape(20.dp),
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
      Row(
        modifier = Modifier.fillMaxSize().padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = "返回",
          tint = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable { onAction(UiAction.ClosePlayer) }
            .padding(4.dp),
        )

        Column(modifier = Modifier.weight(1f).padding(start = 2.dp)) {
          Text(
            text = state.room?.streamerName.orEmpty().ifBlank { "直播间" },
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
          val viewers = state.room?.viewerCount ?: 0
          Row(verticalAlignment = Alignment.CenterVertically) {
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
              text = if (viewers > 0) {
                "${ViewerCountFormatter.format(viewers)}人在看"
              } else {
                state.room?.platform?.displayName.orEmpty()
              },
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
              maxLines = 1,
            )
          }
        }

        Icon(
          imageVector = if (followed) Icons.Filled.Star else Icons.Filled.StarBorder,
          contentDescription = if (followed) "取消关注" else "关注",
          tint = if (followed) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
          modifier = Modifier
            .size(30.dp)
            .clip(CircleShape)
            .clickable(enabled = state.room != null) {
              state.room?.let { onAction(UiAction.ToggleFollow(it)) }
            }
            .padding(5.dp),
        )
      }
    }

    Surface(
      modifier = Modifier.size(40.dp),
      shape = CircleShape,
      color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(
          imageVector = Icons.Filled.Close,
          contentDescription = "关闭",
          tint = MaterialTheme.colorScheme.onSurface,
          modifier = Modifier
            .size(34.dp)
            .clip(CircleShape)
            .clickable { onAction(UiAction.ClosePlayer) }
            .padding(9.dp),
        )
      }
    }
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
    Icon(
      imageVector = Icons.AutoMirrored.Filled.ArrowBack,
      contentDescription = "退出全屏",
      tint = Color.White,
      modifier = Modifier
        .size(34.dp)
        .clip(CircleShape)
        .clickable(onClick = onExit)
        .padding(5.dp),
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
        .size(34.dp)
        .clip(CircleShape)
        .clickable { state.room?.let { onAction(UiAction.ToggleFollow(it)) } }
        .padding(6.dp),
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
  Surface(
    modifier = Modifier.size(38.dp).clip(CircleShape).clickable(onClick = onClick),
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

// --------------------------------------------------------------- action row ---

@Composable
private fun RoomActionRow(state: UiState, onAction: (UiAction) -> Unit) {
  val followed = remember(state.followed, state.room) {
    state.room?.let { room -> state.followed.any { it.key == room.key } } ?: false
  }

  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
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
      Text(
        text = "弹幕 ${state.danmaku.size}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
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

@Composable
private fun DanmakuPanel(state: UiState, modifier: Modifier = Modifier) {
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
    state.danmaku.isEmpty() -> CenteredNotice(
      modifier = modifier,
      text = "正在连接弹幕…",
      showSpinner = true,
    )

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
  // Newest first: chat flows downward from the top like every live app.
  val display = remember(messages) { messages.asReversed() }
  LaunchedEffect(display.size) {
    if (display.isNotEmpty()) listState.animateScrollToItem(0)
  }

  LazyColumn(
    modifier = modifier,
    state = listState,
    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(7.dp),
  ) {
    itemsIndexed(
      display,
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
  Surface(
    color = if (filled) {
      MaterialTheme.colorScheme.primary
    } else {
      MaterialTheme.colorScheme.surfaceVariant
    },
    shape = RoundedCornerShape(20.dp),
    modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onClick),
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
