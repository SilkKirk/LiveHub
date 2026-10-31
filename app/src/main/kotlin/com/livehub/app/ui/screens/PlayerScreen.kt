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
import com.livehub.app.ui.player.LivePlayer
import com.livehub.core.model.StreamVariant

/** Full screen player with a quality / line selector. */
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
        danmakuEnabled = state.danmakuEnabled,
        blockedKeywords = state.blockedKeywords,
        roomKey = state.room?.key.orEmpty(),
        modifier = Modifier.fillMaxSize(),
      )
    } else if (state.loading) {
      CircularProgressIndicator(
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.align(Alignment.Center),
      )
    } else {
      Column(
        modifier = Modifier.align(Alignment.Center).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text(
          text = state.error ?: "无法播放该直播间",
          color = Color.White,
          style = MaterialTheme.typography.bodyLarge,
        )
      }
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
        Text(
          text = state.room?.title.orEmpty(),
          color = Color.White.copy(alpha = 0.75f),
          style = MaterialTheme.typography.labelMedium,
          maxLines = 1,
        )
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
              Text(
                text = listOfNotNull(item.qualityLabel, item.lineName).distinct().joinToString(" · "),
              )
            },
          )
        }
      }
    }
  }
}
