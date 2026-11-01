package com.livehub.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChatBubble
import androidx.compose.material.icons.filled.HighQuality
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
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
 * Full screen player.
 *
 * The chrome auto-hides after a few seconds so the video is unobstructed, and a tap
 * toggles it back. Quality/line chips sit above a scrim so they stay readable over a
 * bright frame, and the danmaku overlay is layered between the video and the controls.
 */
@Composable
fun PlayerScreen(state: UiState, onAction: (UiAction) -> Unit) {
  val variant: StreamVariant? = state.selectedVariant
  var controlsVisible by remember { mutableStateOf(true) }
  var wasPlaying by remember { mutableStateOf(true) }

  // Auto-hide the chrome only once something is actually playing. While the stream is
  // still connecting the controls stay up, otherwise the player looks like a dead
  // black screen with no way to pick a quality.
  val isPlaying = variant != null && !state.loading
  LaunchedEffect(controlsVisible, variant?.url, isPlaying) {
    if (controlsVisible && isPlaying) {
      delay(CONTROLS_HIDE_DELAY_MILLIS)
      controlsVisible = false
    }
  }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black)
      .pointerInput(Unit) {
        detectTapGestures(onTap = { controlsVisible = !controlsVisible })
      },
  ) {
    // ---------------------------------------------------------------- video ----
    if (variant != null) {
      LivePlayer(
        url = variant.url,
        isHls = variant.isHls,
        modifier = Modifier.fillMaxSize(),
      )
      DanmakuOverlay(
        messages = state.danmaku,
        enabled = state.danmakuEnabled,
        blockedKeywords = state.blockedKeywords,
        opacity = state.danmakuOpacity,
        fontScale = state.danmakuFontScale,
        areaFraction = state.danmakuAreaFraction,
        modifier = Modifier.fillMaxSize().padding(top = 72.dp, bottom = 120.dp),
      )
    } else if (state.loading) {
      Column(
        modifier = Modifier.align(Alignment.Center),
        horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.height(12.dp))
        Text(
          text = "正在解析播放地址…",
          color = Color.White.copy(alpha = 0.75f),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    } else {
      PlayerErrorPanel(
        message = state.error ?: "无法播放该直播间",
        roomTitle = state.room?.title,
        onRetry = { state.room?.let { onAction(UiAction.OpenRoom(it)) } },
        onBack = { onAction(UiAction.ClosePlayer) },
      )
    }

    // --------------------------------------------------------------- chrome ----
    AnimatedVisibility(
      visible = controlsVisible,
      enter = fadeIn() + slideInVertically { -it / 2 },
      exit = fadeOut() + slideOutVertically { -it / 2 },
      modifier = Modifier.align(Alignment.TopCenter),
    ) {
      PlayerTopBar(state = state, onAction = onAction)
    }

    AnimatedVisibility(
      visible = controlsVisible && state.variants.isNotEmpty(),
      enter = fadeIn() + slideInVertically { it },
      exit = fadeOut() + slideOutVertically { it },
      modifier = Modifier.align(Alignment.BottomCenter),
    ) {
      PlayerBottomBar(state = state, variant = variant, onAction = onAction)
    }
  }
}

@Composable
private fun PlayerTopBar(state: UiState, onAction: (UiAction) -> Unit) {
  val followed = remember(state.followed, state.room) {
    state.room?.let { room -> state.followed.any { it.key == room.key } } ?: false
  }

  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(Brush.verticalGradient(listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)))
      .padding(horizontal = 4.dp, vertical = 6.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
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
        val subtitle = buildString {
          state.room?.title?.takeIf { it.isNotBlank() }?.let { append(it) }
          val viewers = state.room?.viewerCount ?: 0
          if (viewers > 0) {
            if (isNotEmpty()) append(" · ")
            append(ViewerCountFormatter.format(viewers))
            append("人在看")
          }
        }
        if (subtitle.isNotBlank()) {
          Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.7f),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
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
}

@Composable
private fun PlayerBottomBar(
  state: UiState,
  variant: StreamVariant?,
  onAction: (UiAction) -> Unit,
) {
  Box(
    modifier = Modifier
      .fillMaxWidth()
      .background(Brush.verticalGradient(listOf(Color.Transparent, Color.Black.copy(alpha = 0.8f))))
      .padding(horizontal = 12.dp, vertical = 10.dp),
  ) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
          imageVector = Icons.Filled.HighQuality,
          contentDescription = null,
          tint = Color.White.copy(alpha = 0.8f),
          modifier = Modifier.size(16.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
          text = "清晰度 / 线路",
          color = Color.White.copy(alpha = 0.8f),
          style = MaterialTheme.typography.labelMedium,
        )
        Spacer(Modifier.weight(1f))
        DanmakuToggle(
          enabled = state.danmakuEnabled,
          onToggle = { onAction(UiAction.ToggleDanmaku(it)) },
        )
      }

      LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(state.variants, key = { it.identity }) { item ->
          val selected = item.identity == variant?.identity
          FilterChip(
            selected = selected,
            onClick = { onAction(UiAction.SelectVariant(item)) },
            label = {
              Text(
                text = listOfNotNull(item.qualityLabel, item.lineName)
                  .distinct()
                  .joinToString(" · "),
                style = MaterialTheme.typography.labelMedium,
              )
            },
            colors = FilterChipDefaults.filterChipColors(
              containerColor = Color.White.copy(alpha = 0.14f),
              labelColor = Color.White.copy(alpha = 0.9f),
              selectedContainerColor = MaterialTheme.colorScheme.primary,
              selectedLabelColor = Color.White,
            ),
          )
        }
      }

      if (state.danmakuEnabled && state.danmaku.isNotEmpty()) {
        Text(
          text = "最近弹幕：${state.danmaku.last().userName}：${state.danmaku.last().text}",
          color = Color.White.copy(alpha = 0.65f),
          style = MaterialTheme.typography.labelSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

@Composable
private fun DanmakuToggle(enabled: Boolean, onToggle: (Boolean) -> Unit) {
  Surface(
    color = Color.White.copy(alpha = 0.14f),
    shape = CircleShape,
    modifier = Modifier.clip(CircleShape).clickable { onToggle(!enabled) },
  ) {
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
        imageVector = if (enabled) Icons.Filled.ChatBubble else Icons.Outlined.ChatBubbleOutline,
        contentDescription = null,
        tint = if (enabled) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.7f),
        modifier = Modifier.size(14.dp),
      )
      Spacer(Modifier.width(4.dp))
      Text(
        text = if (enabled) "弹幕开" else "弹幕关",
        color = Color.White.copy(alpha = 0.9f),
        style = MaterialTheme.typography.labelSmall,
      )
    }
  }
}

@Composable
private fun PlayerErrorPanel(
  message: String,
  roomTitle: String?,
  onRetry: () -> Unit,
  onBack: () -> Unit,
) {
  Column(
    modifier = Modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(
      imageVector = Icons.Outlined.Visibility,
      contentDescription = null,
      tint = Color.White.copy(alpha = 0.4f),
      modifier = Modifier.size(48.dp),
    )
    Spacer(Modifier.height(16.dp))
    Text(
      text = message,
      color = Color.White,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
    )
    if (!roomTitle.isNullOrBlank()) {
      Spacer(Modifier.height(6.dp))
      Text(
        text = roomTitle,
        color = Color.White.copy(alpha = 0.6f),
        style = MaterialTheme.typography.bodySmall,
      )
    }
    Spacer(Modifier.height(20.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
      Surface(
        color = MaterialTheme.colorScheme.primary,
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onRetry),
      ) {
        Text(
          text = "重试",
          color = Color.White,
          style = MaterialTheme.typography.labelLarge,
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
      }
      Surface(
        color = Color.White.copy(alpha = 0.14f),
        shape = RoundedCornerShape(20.dp),
        modifier = Modifier.clip(RoundedCornerShape(20.dp)).clickable(onClick = onBack),
      ) {
        Text(
          text = "返回",
          color = Color.White,
          style = MaterialTheme.typography.labelLarge,
          modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
      }
    }
  }
}

private const val CONTROLS_HIDE_DELAY_MILLIS = 4_000L
