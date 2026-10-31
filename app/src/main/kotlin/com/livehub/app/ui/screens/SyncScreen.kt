package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.sync.SyncLogEntry
import com.livehub.core.sync.SyncScope
import java.text.SimpleDateFormat
import java.util.Date

/** Cross-device sync screen: pairing, scopes, peers and the exchange log. */
@Composable
fun SyncScreen(state: UiState, onAction: (UiAction) -> Unit) {
  val locale = LocalConfiguration.current.locales[0]
  val timeFormat = remember(locale) { SimpleDateFormat("MM-dd HH:mm", locale) }
  var showPairDialog by remember { mutableStateOf(false) }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item { ThisDeviceCard(state) }

    item {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = { onAction(UiAction.SyncNow) },
          enabled = state.peers.isNotEmpty() && !state.syncing,
        ) {
          Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
          Text(if (state.syncing) "同步中…" else "立即同步")
        }
        OutlinedButton(onClick = { showPairDialog = true }) {
          Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
          Text("配对设备")
        }
        IconButton(onClick = { onAction(UiAction.RefreshPeers) }) {
          Icon(Icons.Filled.Refresh, contentDescription = "刷新")
        }
      }
    }

    item { ScopeCard(state, onAction) }

    item {
      Text("已配对设备", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
    }

    if (state.peers.isEmpty()) {
      item {
        Text(
          text = "还没有配对设备。在另一台设备打开本页面并生成配对码，然后在两台设备上各输入一次即可。",
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
              Text(
                text = peer.deviceName.ifBlank { "未知设备" },
                style = MaterialTheme.typography.bodyLarge,
              )
              Text(
                text = "配对于 ${timeFormat.format(Date(peer.pairedAt))}" +
                  if (peer.lastSeenAt > 0) " · 最近 ${timeFormat.format(Date(peer.lastSeenAt))}" else "",
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
      items(state.syncLog, key = { "${it.at}-${it.direction}-${it.scope}-${it.peerName}" }) { entry ->
        SyncLogRow(entry = entry, timeFormat = timeFormat)
      }
    }
  }

  if (showPairDialog) {
    PairDialog(
      state = state,
      onDismiss = { showPairDialog = false },
      onConfirm = { name, code ->
        onAction(UiAction.PairPeer(name, code))
        showPairDialog = false
      },
    )
  }
}

@Composable
private fun ThisDeviceCard(state: UiState) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("本机", style = MaterialTheme.typography.titleMedium)
      Text(
        text = state.deviceName.ifBlank { "LiveHub 设备" },
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      if (state.localAddress.isNotBlank()) {
        Text(
          text = "局域网地址 ${state.localAddress}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      if (state.pairingCode.isNotBlank()) {
        Text(
          text = "配对码 ${state.pairingCode}",
          style = MaterialTheme.typography.titleLarge,
          color = MaterialTheme.colorScheme.primary,
        )
        Text(
          text = "在另一台设备输入该配对码，5 分钟内有效",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
        text = "关注、分区订阅和播放设置会在同一局域网内增量同步；删除操作也会同步，不会把旧数据带回来。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun ScopeCard(state: UiState, onAction: (UiAction) -> Unit) {
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

@Composable
private fun SyncLogRow(entry: SyncLogEntry, timeFormat: SimpleDateFormat) {
  Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
    Text(
      text = if (entry.direction == SyncLogEntry.Direction.PUSH) "↑" else "↓",
      style = MaterialTheme.typography.titleMedium,
      color = if (entry.success) {
        MaterialTheme.colorScheme.primary
      } else {
        MaterialTheme.colorScheme.error
      },
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

@Composable
private fun PairDialog(
  state: UiState,
  onDismiss: () -> Unit,
  onConfirm: (String, String) -> Unit,
) {
  var name by remember { mutableStateOf("") }
  var code by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("配对设备") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.pairingCode.isNotBlank()) {
          Text(
            text = "本机配对码：${state.pairingCode}",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
          )
        } else {
          Text(
            text = "先在另一台设备生成配对码，再填入下方。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("设备名称") },
          singleLine = true,
        )
        OutlinedTextField(
          value = code,
          onValueChange = { code = it.filter(Char::isDigit).take(6) },
          label = { Text("六位配对码") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onConfirm(name.ifBlank { "新设备" }, code) },
        enabled = code.length == 6,
      ) { Text("配对") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}
