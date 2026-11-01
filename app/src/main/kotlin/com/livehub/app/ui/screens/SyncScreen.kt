package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.material3.CircularProgressIndicator
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

    item { DiscoveryCard(state, onAction) }

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
      onConfirm = { name, address, code ->
        onAction(UiAction.PairPeer(name, address, code))
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScopeCard(state: UiState, onAction: (UiAction) -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Text("同步内容", style = MaterialTheme.typography.titleMedium)
      // FlowRow, not Row: the labels are 4, 4 and 7 characters wide, which does not fit one
      // 320dp line. A plain Row squeezed the last chip until its text wrapped to a single
      // character per line — "播放与弹幕设置" rendered as a vertical column of glyphs.
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        SyncScope.entries.forEach { scope ->
          FilterChip(
            selected = state.syncScopes.contains(scope),
            onClick = {
              val next = state.syncScopes.toMutableSet()
              if (!next.add(scope)) next.remove(scope)
              onAction(UiAction.SetSyncScopes(next))
            },
            label = { Text(scope.displayName, maxLines = 1, softWrap = false) },
          )
        }
      }
    }
  }
}

@Composable
private fun SyncLogRow(entry: SyncLogEntry, timeFormat: SimpleDateFormat) {
  // The count is the point of the row, so it is always rendered: before this, the row only
  // showed `note`, which is null for a successful push, so a transfer of twelve records
  // appeared as a bare timestamp with no indication that anything moved.
  val detail = buildString {
    append(if (entry.direction == SyncLogEntry.Direction.PUSH) "发送 " else "接收 ")
    append(entry.itemCount)
    append(" 条")
    entry.note?.takeIf { it.isNotBlank() && !it.contains("条") }?.let { append(" · ").append(it) }
  }

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
        text = "${timeFormat.format(Date(entry.at))}  $detail",
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
  onConfirm: (String, String, String) -> Unit,
) {
  var name by remember { mutableStateOf("") }
  var address by remember { mutableStateOf(state.localAddress) }
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
          value = address,
          onValueChange = { address = it },
          label = { Text("对方局域网地址 (IP:端口)") },
          placeholder = { Text("192.168.1.10:47653") },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
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
        onClick = { onConfirm(name.ifBlank { "新设备" }, address, code) },
        enabled = code.length == 6 && address.isNotBlank(),
      ) { Text("配对") }
    },
    dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
  )
}

/**
 * Devices found on the LAN.
 *
 * Only peers the user has not already paired with are offered, so the list stays
 * actionable instead of repeating the paired-device section below it.
 *
 * Each sighting gets a real 配对 button. The action and its handler already existed
 * (`UiAction.PairDiscovered`), but nothing emitted it, so the discovered list was a
 * dead end: the address was printed as plain text and had to be retyped by hand into
 * the dialog directly underneath.
 */
@Composable
private fun DiscoveryCard(state: UiState, onAction: (UiAction) -> Unit) {
  val pairedIds = remember(state.peers) { state.peers.map { it.deviceId }.toSet() }
  val candidates = remember(state.discoveredPeers, pairedIds) {
    state.discoveredPeers.filterNot { pairedIds.contains(it.deviceId) }
  }
  var pendingPeer by remember { mutableStateOf<com.livehub.core.sync.DiscoveredPeer?>(null) }
  var code by remember { mutableStateOf("") }

  if (pendingPeer != null) {
    val peer = pendingPeer!!
    AlertDialog(
      onDismissRequest = { pendingPeer = null; code = "" },
      title = { Text("与 ${peer.deviceName} 配对") },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(
            text = "${peer.host}:${peer.port}",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = "在对方设备上打开同步页并读出六位配对码，输入后两台设备即可互相同步。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
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
          enabled = code.length == 6,
          onClick = {
            onAction(UiAction.PairDiscovered(peer, code))
            pendingPeer = null
            code = ""
          },
        ) { Text("配对") }
      },
      dismissButton = {
        TextButton(onClick = { pendingPeer = null; code = "" }) { Text("取消") }
      },
    )
  }

  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "发现附近设备",
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.weight(1f),
        )
        if (state.discovering) {
          CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        }
        IconButton(onClick = { onAction(UiAction.StartDiscovery) }) {
          Icon(Icons.Filled.Refresh, contentDescription = "重新扫描")
        }
      }

      if (candidates.isEmpty()) {
        Text(
          text = if (state.discovering) {
            "正在扫描同一 Wi-Fi 下的设备…"
          } else {
            "未发现其他设备。请确认两台设备连接同一个 Wi-Fi，且路由器没有开启 AP 隔离。"
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        candidates.forEach { peer ->
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Column(modifier = Modifier.weight(1f)) {
              Text(peer.deviceName, style = MaterialTheme.typography.bodyMedium)
              Text(
                text = "${peer.host}:${peer.port}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            TextButton(onClick = { pendingPeer = peer; code = "" }) {
              Text("配对")
            }
          }
        }
        Text(
          text = "点击「配对」并输入对方设备上显示的六位配对码即可完成配对。",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
