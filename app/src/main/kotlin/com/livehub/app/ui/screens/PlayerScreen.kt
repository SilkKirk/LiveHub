package com.livehub.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.app.ui.player.DanmakuOverlay
import com.livehub.app.ui.player.LivePlayer
import com.livehub.core.common.ViewerCountFormatter
import com.livehub.core.designsystem.LiveBadgeColor
import com.livehub.core.model.StreamVariant
import kotlinx.coroutines.delay

/**
 * Live room screen.
 *
 * Portrait follows the layout every live-streaming app uses: a 16:9 video surface with
 * the room header overlaid on it, then the room's action row, then a danmaku list that
 * fills the rest of the screen and scrolls newest-first. Full screen keeps only the
 * video plus the floating danmaku overlay.
 */
@Composable
fun PlayerScreen(state: UiState, onAction: (UiAction) -> Unit) {
  val variant: StreamVariant? = state.selectedVariant
  var fullscreen by remember { mutableStateOf(false) }
  var controlsVisible by remember { mutableStateOf(true) }

  // Back must leave full screen first, not the room.
  BackHandler(enabled = fullscreen) { fullscreen = false }

  val isPlaying = variant != null && !state.loading
  // Keep the chrome up until something is actually playing; the first seconds otherwise
  // look like a dead black screen with no way to change quality.
  LaunchedEffect(controlsVisible, variant?.url, isPlaying, fullscreen) {
    if (controlsVisible && isPlaying) {
      delay(if (fullscreen) FULLSCREEN_HIDE_DELAY_MILLIS else PORTRAIT_HIDE_DELAY_MILLIS)
      controlsVisible = false
    }
  }

  if (fullscreen) {
    FullscreenPlayer(
      state = state,
      variant = variant,
      controlsVisible = controlsVisible,
      onToggleControls = { controlsVisible = !controlsVisible },
      onExitFullscreen = { fullscreen = false },
      onAction = onAction,
    )
    return
  }

  Column(modifier = Modifier.fillMaxSize()) {
    VideoSection(
      state = state,
      variant = variant,
      onAction = onAction,
      onEnterFullscreen = {
        fullscreen = true
        controlsVisible = true
      },
    )

    RoomActionRow(state = state, onAction = onAction)

    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

    if (state.variants.isNotEmpty()) {
      QualityBar(state = state, variant = variant, onAction = onAction)
    }

    DanmakuPanel(state = state, modifier = Modifier.weight(1f).fillMaxWidth())
  }
}

// -------------------------------------------------------------- video section ----

@Composable
private fun VideoSection(
  state: UiState,
  variant: StreamVariant?,
  onAction: (UiAction) -> Unit,
  onEnterFullscreen: () -> Unit,
) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .aspectRatio(16f / 9f)
      .background(Color.Black),
  ) {
    VideoSurface(
      state = state,
      variant = variant,
      onAction = onAction,
      modifier = Modifier.fillMaxSize(),
    )

    // The header overlays the video so the picture keeps the full width.
    PlayerOverlayHeader(
      state = state,
      onAction = onAction,
      modifier = Modifier.align(Alignment.TopCenter),
    )

    Row(
      modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
      OverlayIconButton(
        icon = if (state.danmakuEnabled) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline,
        description = if (state.danmakuEnabled) "关闭弹幕" else "开启弹幕",
        tint = if (state.danmakuEnabled) MaterialTheme.colorScheme.primary else Color.White,
        onClick = { onAction(UiAction.ToggleDanmaku(!state.danmakuEnabled)) },
      )
      OverlayIconButton(
        icon = Icons.Filled.Fullscreen,
        description = "全屏",
        onClick = onEnterFullscreen,
      )
    }
  }
}

@Composable
private fun VideoSurface(
  state: UiState,
  variant: StreamVariant?,
  onAction: (UiAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    when {
      variant != null -> LivePlayer(
        url = variant.url,
        isHls = variant.isHls,
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
          text = state.error ?: "无法播放该直播间",
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
private fun PlayerOverlayHeader(
  state: UiState,
  onAction: (UiAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  val followed = remember(state.followed, state.room) {
    state.room?.let { room -> state.followed.any { it.key == room.key } } ?: false
  }

  Row(
    modifier = modifier
      .fillMaxWidth()
      .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent)))
      .padding(horizontal = 2.dp, vertical = 2.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    IconButton(onClick = { onAction(UiAction.ClosePlayer) }) {
      Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.White)
    }
    Column(modifier = Modifier.weight(1f)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        if (state.room?.isLive == true) {
          Surface(color = LiveBadgeColor, shape = RoundedCornerShape(3.dp)) {
            Text(
              text = "LIVE",
              style = MaterialTheme.typography.labelSmall,
              color = Color.White,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
            )
          }
          Spacer(Modifier.width(6.dp))
        }
        Text(
          text = state.room?.streamerName.orEmpty().ifBlank { "直播间" },
          color = Color.White,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
      val viewers = state.room?.viewerCount ?: 0
      if (viewers > 0) {
        Text(
          text = "${ViewerCountFormatter.format(viewers)}人在看",
          color = Color.White.copy(alpha = 0.72f),
          style = MaterialTheme.typography.labelSmall,
        )
      }
    }
    IconButton(
      onClick = { state.room?.let { onAction(UiAction.ToggleFollow(it)) } },
      enabled = state.room != null,
    ) {
      Icon(
        imageVector = if (followed) Icons.Filled.Star else Icons.Filled.StarBorder,
        contentDescription = if (followed) "取消关注" else "关注",
        tint = if (followed) MaterialTheme.colorScheme.primary else Color.White,
      )
    }
  }
}

// --------------------------------------------------------------- action rows ----

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
      PillButton(
        label = "刷新",
        filled = false,
        onClick = { state.room?.let { onAction(UiAction.OpenRoom(it)) } },
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

@Composable
private fun QualityBar(
  state: UiState,
  variant: StreamVariant?,
  onAction: (UiAction) -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
    Row(
      modifier = Modifier.padding(horizontal = 14.dp, vertical = 2.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = Icons.Filled.HighQuality,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(15.dp),
      )
      Spacer(Modifier.width(5.dp))
      Text(
        text = "清晰度 / 线路",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    LazyRow(
      contentPadding = PaddingValues(horizontal = 14.dp, vertical = 2.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(state.variants, key = { it.identity }) { item ->
        FilterChip(
          selected = item.identity == variant?.identity,
          onClick = { onAction(UiAction.SelectVariant(item)) },
          label = {
            Text(
              text = listOfNotNull(item.qualityLabel, item.lineName).distinct().joinToString(" · "),
              style = MaterialTheme.typography.labelMedium,
              maxLines = 1,
            )
          },
        )
      }
    }
  }
}

// -------------------------------------------------------------- danmaku list ----

@Composable
private fun DanmakuPanel(state: UiState, modifier: Modifier = Modifier) {
  when {
    !state.danmakuEnabled -> CenteredNotice(
      modifier = modifier,
      icon = Icons.Outlined.ChatBubbleOutline,
      text = "弹幕已关闭",
    )

    state.danmaku.isEmpty() -> CenteredNotice(
      modifier = modifier,
      text = "正在连接弹幕…",
      showSpinner = true,
    )

    else -> DanmakuList(state = state, modifier = modifier)
  }
}

@Composable
private fun DanmakuList(state: UiState, modifier: Modifier = Modifier) {
  val listState = rememberLazyListState()
  // Newest first: chat flows downward from the top like every live app.
  val display = remember(state.danmaku) { state.danmaku.asReversed() }
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
      // No id on the model; the arrival time is unique per message in practice and the
      // index guards the (rare) same-millisecond case.
      key = { index, message -> "${message.receivedAt}:$index" },
    ) { _, message ->
      DanmakuRow(user = message.userName, content = message.text)
    }
  }
}

@Composable
private fun DanmakuRow(user: String, content: String) {
  Row {
    Text(
      text = "${user.ifBlank { "匿名" }}：",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
      maxLines = 1,
    )
    Text(
      text = content,
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurface,
    )
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

// ---------------------------------------------------------------- fullscreen ----

@Composable
private fun FullscreenPlayer(
  state: UiState,
  variant: StreamVariant?,
  controlsVisible: Boolean,
  onToggleControls: () -> Unit,
  onExitFullscreen: () -> Unit,
  onAction: (UiAction) -> Unit,
) {
  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black)
      .clickable(
        interactionSource = remember { MutableInteractionSource() },
        indication = null,
        onClick = onToggleControls,
      ),
  ) {
    if (variant != null) {
      LivePlayer(url = variant.url, isHls = variant.isHls, modifier = Modifier.fillMaxSize())
      DanmakuOverlay(
        messages = state.danmaku,
        enabled = state.danmakuEnabled,
        blockedKeywords = state.blockedKeywords,
        opacity = state.danmakuOpacity,
        fontScale = state.danmakuFontScale,
        areaFraction = state.danmakuAreaFraction,
        modifier = Modifier.fillMaxSize().padding(top = 56.dp, bottom = 72.dp),
      )
    } else {
      VideoSurface(
        state = state,
        variant = null,
        onAction = onAction,
        modifier = Modifier.fillMaxSize(),
      )
    }

    if (controlsVisible) {
      Row(
        modifier = Modifier
          .align(Alignment.TopCenter)
          .fillMaxWidth()
          .background(
            Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.78f), Color.Transparent)),
          )
          .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        IconButton(onClick = onExitFullscreen) {
          Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "退出全屏", tint = Color.White)
        }
        Text(
          text = state.room?.streamerName.orEmpty().ifBlank { "直播间" },
          color = Color.White,
          style = MaterialTheme.typography.titleSmall,
          fontWeight = FontWeight.SemiBold,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onExitFullscreen) {
          Icon(Icons.Filled.FullscreenExit, contentDescription = "退出全屏", tint = Color.White)
        }
      }
    }
  }
}

// ------------------------------------------------------------------ helpers ----

@Composable
private fun OverlayIconButton(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  description: String,
  onClick: () -> Unit,
  tint: Color = Color.White,
) {
  Surface(color = Color.Black.copy(alpha = 0.42f), shape = CircleShape, modifier = Modifier.clip(CircleShape)) {
    IconButton(onClick = onClick, modifier = Modifier.size(34.dp)) {
      Icon(icon, contentDescription = description, tint = tint, modifier = Modifier.size(18.dp))
    }
  }
}

@Composable
private fun PillButton(label: String, filled: Boolean, onClick: () -> Unit) {
  Surface(
    color = if (filled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant,
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

private const val PORTRAIT_HIDE_DELAY_MILLIS = 4_000L
private const val FULLSCREEN_HIDE_DELAY_MILLIS = 3_000L
