package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.sync.SyncScope
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Cross-device sync screen: scopes, paired devices and the sync log. */
@Composable
fun SyncScreen(state: UiState, onAction: (UiAction) -> Unit) {
  val timeFormat = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("本机", style = MaterialTheme.typography.titleMedium)
          Text(
            text = state.deviceName.ifBlank { "LiveHub 设备" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "配对后两台设备会在同一局域网内增量同步关注、分区订阅和播放设置。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }

    item {
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text("同步内容", style = MaterialTheme.typography.titleMedium)
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SyncScope.entries.forEach { scope ->
              FilterChip(
                selected = state.syncScopes.contains(scope),
                onClick = {
                  val next = state.syncScopes.toMutableSet()
                  if (!next.add(scope)) next.remove(scope)
                  onAction(UiAction.SetSyncScopes(next))
                },
                label = { Text(scope.displayName) },
              )
            }
          }
        }
      }
    }

    item {
      Text("已配对设备", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }

    if (state.peers.isEmpty()) {
      item {
        Text(
          text = "还没有配对设备。在另一台设备打开「同步」页面，用配对码连接即可。",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      items(state.peers, key = { it.deviceId }) { peer ->
        Card(modifier = Modifier.fillMaxWidth()) {
          Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(peer.deviceName, style = MaterialTheme.typography.bodyLarge)
              Text(
                text = "配对于 ${timeFormat.format(Date(peer.pairedAt))}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            IconButton(onClick = { onAction(UiAction.UnpairPeer(peer.deviceId)) }) {
              Icon(Icons.Filled.Delete, contentDescription = "解除配对")
            }
          }
        }
      }
    }

    item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

    item {
      Text("同步记录", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }

    if (state.syncLog.isEmpty()) {
      item {
        Text(
          text = "暂无同步记录",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      items(state.syncLog, key = { "${it.at}-${it.peerName}-${it.scope}" }) { entry ->
        Row(
          modifier = Modifier.fillMaxWidth(),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = if (entry.direction == com.livehub.core.sync.SyncLogEntry.Direction.PUSH) "↑" else "↓",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 8.dp),
          )
          Column(modifier = Modifier.weight(1f)) {
            Text("${entry.scope} · ${entry.peerName}", style = MaterialTheme.typography.bodyMedium)
            Text(
              text = "${timeFormat.format(Date(entry.at))}  ${entry.note.orEmpty()}",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}
