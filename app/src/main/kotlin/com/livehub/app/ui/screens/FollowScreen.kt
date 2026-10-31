package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.app.ui.components.StreamerCard

/** Follow list, sorted with live rooms first. */
@Composable
fun FollowScreen(state: UiState, onAction: (UiAction) -> Unit) {
  val sorted = remember(state.followed) {
    state.followed.sortedWith(
      compareByDescending<com.livehub.core.model.Streamer> { it.isLive }
        .thenByDescending { it.updatedAt },
    )
  }
  val keys = remember(sorted) { sorted.map { it.key }.toSet() }

  Box(modifier = Modifier.fillMaxSize()) {
    if (sorted.isEmpty()) {
      Column(
        modifier = Modifier.align(Alignment.Center).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text("还没有关注的主播", style = MaterialTheme.typography.titleMedium)
        Text(
          text = "在首页点击卡片右下角的星标即可关注，关注会同步到你的其他设备。",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    } else {
      LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(12.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
      ) {
        items(items = sorted, key = { it.key }) { streamer ->
          StreamerCard(
            streamer = streamer,
            followed = keys.contains(streamer.key),
            onClick = { onAction(UiAction.OpenRoom(streamer)) },
            onToggleFollow = { onAction(UiAction.ToggleFollow(streamer)) },
          )
        }
      }
    }
    if (state.loading) {
      CircularProgressIndicator(modifier = Modifier.align(Alignment.TopCenter).padding(16.dp))
    }
  }
}
