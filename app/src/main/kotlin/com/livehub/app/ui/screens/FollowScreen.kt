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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.app.ui.components.ReorderableFollowList

/**
 * Follow list.
 *
 * Ordered by the user's manual arrangement (long press + drag), which is itself a
 * synchronised value, so reordering on one device carries over to the others.
 */
@Composable
fun FollowScreen(state: UiState, onAction: (UiAction) -> Unit) {
  Box(modifier = Modifier.fillMaxSize()) {
    if (state.followed.isEmpty()) {
      Column(
        modifier = Modifier.align(Alignment.Center).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        Text("还没有关注的主播", style = MaterialTheme.typography.titleMedium)
        Text(
          text = "在首页点击卡片右下角的星标即可关注。关注列表会通过局域网同步到你的其他设备，" +
            "长按列表项可以拖动排序。",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          textAlign = TextAlign.Center,
        )
      }
    } else {
      ReorderableFollowList(
        streamers = state.followed,
        onClick = { streamer -> onAction(UiAction.OpenRoom(streamer)) },
        onReorder = { streamer, index -> onAction(UiAction.MoveFollow(streamer, index)) },
        modifier = Modifier.fillMaxSize(),
      )
    }

    if (state.loading) {
      CircularProgressIndicator(modifier = Modifier.align(Alignment.TopCenter).padding(16.dp))
    }
  }
}
