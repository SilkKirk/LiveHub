package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.sync.SyncLogEntry
import com.livehub.core.sync.SyncScope
import java.text.SimpleDateFormat
import java.util.Date

/**
 * Cross-device sync screen.
 *
 * Laid out like WeChat's input-method pairing: this device shows a six digit matching
 * code, the other device types it, and no address is ever read out or retyped. The
 * scopes and the exchange log sit below the device list.
 */
@Composable
fun SyncScreen(state: UiState, onAction: (UiAction) -> Unit) {
  val locale = LocalConfiguration.current.locales[0]
  val timeFormat = remember(locale) { SimpleDateFormat("MM-dd HH:mm", locale) }
  var showCodeDialog by remember { mutableStateOf(false) }

  // The countdown is derived here rather than read straight off the state: the state only
  // refreshes when something else changes, so a code issued on arrival would keep claiming
  // "4 分 28 秒内有效" long after it had actually expired and the other device started
  // getting "配对码已过期".
  var secondsLeft by remember { mutableStateOf(state.pairingCodeSecondsLeft) }
  LaunchedEffect(state.pairingCode, state.pairingCodeSecondsLeft) {
    secondsLeft = state.pairingCodeSecondsLeft
    while (secondsLeft > 0) {
      delay(1_000)
      secondsLeft -= 1
    }
  }

  val shownState = remember(state, secondsLeft) { state.copy(pairingCodeSecondsLeft = secondsLeft) }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item { ThisDeviceCard(shownState, onAction) }

    item {
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
          onClick = { onAction(UiAction.SyncNow) },
          enabled = state.peers.isNotEmpty() && !state.syncing,
        ) {
          Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
          Text(if (state.syncing) "同步中…" else "立即同步")
        }
        OutlinedButton(
          onClick = { showCodeDialog = true },
          enabled = !state.pairing,
        ) {
          Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
          Text(if (state.pairing) "匹配中…" else "关联设备")
        }
        IconButton(onClick = { onAction(UiAction.RefreshPeers) }) {
          Icon(Icons.Filled.Refresh, contentDescription = "刷新")
        }
      }
    }

    item { ScopeCard() }

    item { DeviceListCard(state, onAction) }

    item { DiscoveryCard(state, onAction) }

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

  if (showCodeDialog) {
    MatchingCodeDialog(
      state = state,
      onDismiss = { showCodeDialog = false },
      onConfirm = { code ->
        onAction(UiAction.PairWithCode(code))
        showCodeDialog = false
      },
      onRefreshCode = { onAction(UiAction.RefreshPairingCode) },
    )
  }
}

/**
 * This device: its name and the matching code it is showing.
 *
 * The code is the headline rather than a footnote. In the old layout it was buried in a
 * card above an IP address the user was expected to compare; here it is the one thing the
 * other device needs.
 */
@Composable
private fun ThisDeviceCard(state: UiState, onAction: (UiAction) -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text("本机", style = MaterialTheme.typography.titleMedium)
          Text(
            text = state.deviceName.ifBlank { "LiveHub 设备" },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        TextButton(onClick = { onAction(UiAction.RefreshPairingCode) }) {
          Text("刷新匹配码")
        }
      }

      if (state.pairingCode.isBlank()) {
        Text(
          text = "点「刷新匹配码」生成一个六位匹配码。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        Text(
          text = formatCode(state.pairingCode),
          style = MaterialTheme.typography.displaySmall,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.fillMaxWidth(),
          textAlign = TextAlign.Center,
        )
        Text(
          text = if (state.pairingCodeSecondsLeft > 0) {
            "在另一台设备的「关联设备」里输入这串数字，${state.pairingCodeSecondsLeft / 60} 分 " +
              "${state.pairingCodeSecondsLeft % 60} 秒内有效"
          } else {
            "匹配码已过期，请点「刷新匹配码」重新生成"
          },
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.fillMaxWidth(),
          textAlign = TextAlign.Center,
        )
      }

      if (state.localAddress.isNotBlank()) {
        Text(
          text = "局域网 ${state.localAddress}",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Text(
        text = "关注和播放设置会在同一 Wi-Fi 下增量同步；删除也会同步，不会把旧数据带回来。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * `482913` as `482 913`.
 *
 * Only the six digit shape is split. A blank or partial code is passed through untouched
 * rather than chunked blindly, because `"12".chunked(3)` produces `"12"` and the screen
 * would show a plausible-looking but meaningless half code.
 */
private fun formatCode(code: String): String =
  if (code.length == 6) code.chunked(3).joinToString(" ") else code

/**
 * The dialog the other device drives: type the six digits, nothing else.
 *
 * Deliberately has no address field. Reading an IP off another screen is the step this
 * whole change removes, and an optional field would quietly reintroduce it.
 *
 * **A plain [Dialog] rather than `AlertDialog`.** Material3's `AlertDialog` measures its
 * content column with `Modifier.width(IntrinsicSize.Max)` and scrolls it when it does not fit
 * the window; on a wide *and* short window (640×360dp, i.e. a landscape phone) the two
 * together never settle — the composition spins without becoming idle, which on a device is a
 * frozen UI. It reproduced in Robolectric as `AppNotIdleException: Compose did not get idle
 * after 125826 attempts in 60 SECONDS`, and narrowing the variables showed it needs width and
 * height to be tight at the same time (600×800 and 320×360 both settle).
 *
 * Here the width is bounded explicitly and only the body scrolls, so there is no intrinsic
 * measurement anywhere and nothing to fight over.
 */
@Composable
private fun MatchingCodeDialog(
  state: UiState,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit,
  onRefreshCode: () -> Unit,
) {
  var code by remember { mutableStateOf("") }

  Dialog(onDismissRequest = onDismiss) {
    Surface(
      modifier = Modifier.fillMaxWidth().widthIn(max = 420.dp),
      shape = RoundedCornerShape(28.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 6.dp,
    ) {
      Column(modifier = Modifier.padding(24.dp)) {
        Text("关联设备", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(12.dp))

        // `weight(fill = false)` so the body takes what it needs and gives the rest back to
        // the window; `verticalScroll` then makes the overflow reachable instead of clipped.
        Column(
          modifier = Modifier
            .weight(1f, fill = false)
            .verticalScroll(rememberScrollState()),
          verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
          Text(
            text = "请输入另一台手机或电脑的匹配码。在对方设备打开本页，即可看到六位匹配码。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          OutlinedTextField(
            value = code,
            onValueChange = { code = it.filter(Char::isDigit).take(6) },
            label = { Text("六位匹配码") },
            placeholder = { Text("123456") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
          )
          // This device's own code is shown for comparison, with its remaining life: the
          // common mistake is to type your own code into your own device, which looks like
          // nothing happening.
          Text(
            text = if (state.pairingCodeSecondsLeft > 0) {
              "本机匹配码 ${formatCode(state.pairingCode)}（请勿输入本机码）"
            } else {
              "本机匹配码已过期"
            },
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          TextButton(onClick = onRefreshCode) { Text("重新生成本机匹配码") }
          if (state.pairing) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
              Spacer(modifier = Modifier.size(8.dp))
              Text(
                text = "正在附近设备中查找…",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }

        Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.End,
        ) {
          TextButton(onClick = onDismiss) { Text("取消") }
          Spacer(modifier = Modifier.size(8.dp))
          TextButton(
            onClick = { onConfirm(code) },
            enabled = code.length == 6 && !state.pairing,
          ) { Text("关联") }
        }
      }
    }
  }
}

/**
 * What this page actually exchanges.
 *
 * It used to be one chip per scope, and two of the three were decoration: one had no target at
 * all, the other had a target nothing ever wrote to. The follow list is the only thing whose
 * value is destroyed by owning two devices, so the card states it instead of offering a switch
 * that can only have one useful position.
 */
@Composable
private fun ScopeCard() {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text("同步内容", style = MaterialTheme.typography.titleMedium)
      Text(SyncScope.FOLLOWS.displayName, style = MaterialTheme.typography.bodyMedium)
      Text(
        text = "只同步关注列表。播放设置与搜索历史留在本机，不会上传到另一台设备。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun DeviceListCard(state: UiState, onAction: (UiAction) -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
      Text("我的设备", style = MaterialTheme.typography.titleMedium)

      // This device is listed first, the way WeChat's page shows 本机 above the others.
      Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
          Text(
            text = state.deviceName.ifBlank { "LiveHub 设备" },
            style = MaterialTheme.typography.bodyLarge,
          )
          Text(
            text = "本机",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }

      if (state.peers.isEmpty()) {
        Text(
          text = "还没有关联设备。点「关联设备」输入对方屏幕上的六位匹配码即可。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        state.peers.forEach { peer ->
          HorizontalDivider()
          Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
              Text(
                text = peer.deviceName.ifBlank { "未知设备" },
                style = MaterialTheme.typography.bodyLarge,
              )
              Text(
                text = "关联于 ${timeFormatOrDash(peer.pairedAt)}" +
                  if (peer.lastSeenAt > 0) " · 最近 ${timeFormatOrDash(peer.lastSeenAt)}" else "",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
            IconButton(onClick = { onAction(UiAction.UnpairPeer(peer.deviceId)) }) {
              Icon(Icons.Filled.Delete, contentDescription = "解除关联")
            }
          }
        }
      }
    }
  }
}

@Composable
private fun timeFormatOrDash(at: Long): String {
  if (at <= 0) return "—"
  val locale = LocalConfiguration.current.locales[0]
  return remember(at, locale) { SimpleDateFormat("MM-dd HH:mm", locale).format(Date(at)) }
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

/**
 * Devices discovery has seen but that are not linked yet.
 *
 * Shown as information, not as a way to pair: with a matching code in hand the address is
 * irrelevant, and offering a per-device 配对 button re-introduced the very step (read the
 * other screen, copy an address) this screen is meant to remove.
 */
@Composable
private fun DiscoveryCard(state: UiState, onAction: (UiAction) -> Unit) {
  val pairedIds = remember(state.peers) { state.peers.map { it.deviceId }.toSet() }
  val candidates = remember(state.discoveredPeers, pairedIds) {
    state.discoveredPeers.filterNot { pairedIds.contains(it.deviceId) }
  }

  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = "附近设备",
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
            "未发现其他设备。关联时也会按匹配码自动搜索本网段，通常不必等这里出现设备。"
          },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      } else {
        candidates.forEach { peer ->
          Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
              Text(peer.deviceName.ifBlank { "未命名设备" }, style = MaterialTheme.typography.bodyMedium)
              Text(
                text = "${peer.host}:${peer.port}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          }
        }
        Spacer(modifier = Modifier.height(2.dp))
        Text(
          text = "在对方设备上打开本页读出六位匹配码，点「关联设备」输入即可。",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    }
  }
}
