package com.livehub.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.app.ui.player.DanmakuOverlay
import com.livehub.app.ui.player.LivePlayer
import com.livehub.core.common.ViewerCountFormatter
import com.livehub.core.model.StreamVariant

/** Full screen player with the danmaku overlay and a quality / line selector. */
@Composable
fun PlayerScreen(state: UiState, onAction: (UiAction) -> Unit) {
  val variant: StreamVariant? = state.selectedVariant

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Color.Black),
  ) {
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
        modifier = Modifier.fillMaxSize().padding(top = 56.dp),
      )
    } else if (state.loading) {
      CircularProgressIndicator(
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.align(Alignment.Center),
      )
    } else {
      Text(
        text = state.error ?: "无法播放该直播间",
        color = Color.White,
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.align(Alignment.Center).padding(24.dp),
      )
    }

    Row(
      modifier = Modifier.fillMaxWidth().padding(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      IconButton(onClick = { onAction(UiAction.ClosePlayer) }) {
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = "返回",
          tint = Color.White,
        )
      }
      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = state.room?.streamerName.orEmpty(),
          color = Color.White,
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
        )
        val subtitle = buildString {
          append(state.room?.title.orEmpty())
          if ((state.room?.viewerCount ?: 0) > 0) {
            if (isNotEmpty()) append(" · ")
            append(ViewerCountFormatter.format(state.room?.viewerCount ?: 0))
            append("人观看")
          }
        }
        if (subtitle.isNotBlank()) {
          Text(
            text = subtitle,
            color = Color.White.copy(alpha = 0.75f),
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
          )
        }
      }
    }

    if (state.variants.isNotEmpty()) {
      LazyRow(
        modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(state.variants, key = { it.identity }) { item ->
          FilterChip(
            selected = item.identity == variant?.identity,
            onClick = { onAction(UiAction.SelectVariant(item)) },
            label = {
              Text(listOfNotNull(item.qualityLabel, item.lineName).distinct().joinToString(" · "))
            },
          )
        }
      }
    }
  }
}
