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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
import com.livehub.app.ui.components.SectionHeader
import com.livehub.app.ui.components.listRowColors
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

    item { SyncActionsRow(state, onAction, onOpenMatchingCode = { showCodeDialog = true }) }

    item { ScopeCard() }

    item { AutoSyncCard(state, onAction) }

    item { DeviceListCard(state, onAction) }

    item { DiscoveryCard(state, onAction) }

    item { HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp)) }

    item {
      SectionHeader("同步记录")
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
 * The actions at the top of the page: 立即同步 as the one primary action, 关联设备 and the peer-list
 * refresh beside it as secondary ones.
 *
 * **Why not three buttons in a row.** By Material's own tokens three of them need about
 * 134 + 8 + 134 + 8 + 48 ≈ 332dp while the 320dp render window leaves 288dp, so at 1.0× the refresh
 * icon was squeezed to a sliver — it was in the tree and simply not on screen, which is why `13-sync`
 * never showed it. Wrapping them (`FlowRow`) fixed that but produced a 48dp icon alone on a second
 * line, which reads as a leftover rather than as a control.
 *
 * The row is now a hierarchy instead of a queue: the primary action takes the full width (it is the
 * only one that changes data), and the two setup actions share the line below, one of them weighted
 * so the icon keeps its 48dp and nothing can be squeezed to zero. At 1.5× the secondary row still
 * holds both, which is what `31-sync-large-font` checks.
 */
@Composable
private fun SyncActionsRow(
  state: UiState,
  onAction: (UiAction) -> Unit,
  onOpenMatchingCode: () -> Unit,
) {
  // One primary action, then one row of secondary ones — not three buttons of equal weight.
  //
  // 立即同步 is what this page exists for and it is the only one that changes data; 关联设备 and the
  // refresh are setup and housekeeping. Three same-sized buttons in a row said the opposite, and at
  // 320dp they did not fit anyway (the refresh icon was squeezed out of the row entirely at 1.0×,
  // and the whole block fell apart at 1.5× — `13-sync` / `31-sync-large-font`). A full-width primary
  // also gives the page an anchor: the eye lands on the one thing that does something before it
  // reads the card of numbers above it.
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Button(
      onClick = { onAction(UiAction.SyncNow) },
      enabled = state.peers.isNotEmpty() && !state.syncing,
      modifier = Modifier.fillMaxWidth(),
    ) {
      Icon(Icons.Filled.Sync, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
      Text(if (state.syncing) "同步中…" else "立即同步")
    }
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      OutlinedButton(
        onClick = onOpenMatchingCode,
        enabled = !state.pairing,
        modifier = Modifier.weight(1f),
      ) {
        Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.padding(end = 6.dp))
        Text(if (state.pairing) "匹配中…" else "关联设备")
      }
      // The peer list's own refresh sits at the end of the row rather than in a line by itself: it
      // is the smallest action here, and an orphaned 48dp icon under two buttons reads as a
      // leftover. A `Row` is safe now — the two items above it are weighted and full-width, so
      // nothing can be squeezed to zero the way three fixed-size buttons were.
      IconButton(onClick = { onAction(UiAction.RefreshPeers) }) {
        Icon(Icons.Filled.Refresh, contentDescription = "刷新")
      }
    }
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
        text = "关注列表与登录状态会在同一 Wi-Fi 下增量同步；删除也会同步，不会把旧数据带回来。",
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
 */
@Composable
private fun MatchingCodeDialog(
  state: UiState,
  onDismiss: () -> Unit,
  onConfirm: (String) -> Unit,
  onRefreshCode: () -> Unit,
) {
  var code by remember { mutableStateOf("") }

  // A fixed-width `Dialog`, not `AlertDialog`.
  //
  // `AlertDialog` measures its content with `Modifier.width(IntrinsicSize.Max)`; bisecting the
  // content one component at a time (640×360dp, a landscape phone) showed that a text field
  // inside a dialog stops Compose from ever idling there — `AppNotIdleException` after 60s and
  // ~190k attempts — while the same field outside a dialog, and the same dialog with a
  // fixed-width field, both idle. The intrinsic-width path is the difference: the content asks
  // for the intrinsic width of a field whose intrinsic width depends on the width it is given.
  // With an explicitly sized dialog there is no intrinsic measurement at all, and the field no
  // longer needs (or may have) `fillMaxWidth`.
  Dialog(onDismissRequest = onDismiss) {
    Surface(
      modifier = Modifier.width(280.dp),
      shape = RoundedCornerShape(28.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 6.dp,
    ) {
      Column(modifier = Modifier.padding(24.dp)) {
        Text("关联设备", style = MaterialTheme.typography.titleLarge)
        Spacer(modifier = Modifier.height(12.dp))

        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
          modifier = Modifier.padding(top = 8.dp),
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
 * Two scopes, and both are shown rather than offered as switches: they are the two things
 * owning a second device would otherwise make you repeat. The old shape (one chip per scope,
 * three of them, two dead) is what round 32 removed; this card is the honest list, including
 * the fact that the login state travels unencrypted to paired devices only.
 */
@Composable
private fun ScopeCard() {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text("同步内容", style = MaterialTheme.typography.titleMedium)
      Text(
        text = SyncScope.entries.joinToString(" · ") { it.displayName },
        style = MaterialTheme.typography.bodyMedium,
      )
      Text(
        text = "关注列表与 B 站/抖音登录状态会同步到已配对的设备；播放设置与搜索历史留在本机。" +
          "登录状态只在局域网内、只发给已配对的设备（明文传输，解除关联后不再发送）。",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

/**
 * The 自动同步 switch: one decision, two consequences.
 *
 * On, the LAN server stays up for as long as the app is on screen (so the other device can start
 * an exchange whenever it likes, instead of both users having to be parked on this page), and each
 * arrival in the foreground runs one exchange by itself. Off, the page behaves the way it did
 * before: the server listens only while this screen is open — pairing needs a socket before any
 * device is paired — and every exchange is a tap on 立即同步.
 *
 * The subtitle states the trade rather than the mechanic: "只在前台运行" is the reassurance that
 * matters here, because the alternative a user would fear is a socket that stays open with the app
 * in their pocket.
 *
 * The row itself is the official `ListItem` (round 103). "One title, one explanation, one control at
 * the end" is exactly the shape the component exists for, and it brings the two-line height, the
 * 16dp insets and the merged semantics for a screen reader with it. Deliberately no padding of our
 * own: `ListItem` already carries its own, and wrapping it would double the inset.
 */
@Composable
private fun AutoSyncCard(state: UiState, onAction: (UiAction) -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    ListItem(
      headlineContent = { Text("自动同步") },
      // `style = labelSmall` on every supporting line on this page — here and in the three rows
      // below. The project's type scale is a pinned decision (`TypographyFloorTest` names the sync
      // page's notes as what labelSmall carries) and these explanations were labelSmall before the
      // rows became list items, so keeping the style makes this a structure change rather than a
      // type-scale change: `ListItem` would otherwise draw them a step larger, at bodyMedium.
      supportingContent = {
        Text(
          text = "应用打开时与已配对设备同步一次；服务端只在前台运行。",
          style = MaterialTheme.typography.labelSmall,
        )
      },
      // The switch stays the control — the row is not clickable and does not pretend to be. Its
      // tag has to stay on the switch itself: `SyncAutoCardTest` finds the control by that tag and
      // asserts the checked state of what it finds.
      trailingContent = {
        Switch(
          checked = state.autoSyncEnabled,
          onCheckedChange = { onAction(UiAction.SetAutoSync(it)) },
          modifier = Modifier.testTag(AUTO_SYNC_SWITCH_TAG),
        )
      },
      colors = listRowColors(),
    )
  }
}

@Composable
private fun DeviceListCard(state: UiState, onAction: (UiAction) -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    // The title keeps the card's own 16dp inset; the rows below are `ListItem`s, which bring that
    // inset with them. Padding the column instead would put the title at 16dp and the device names
    // at 32dp, and the eye reads that as a nested list rather than as one list under a heading.
    Column(modifier = Modifier.fillMaxWidth()) {
      Text(
        text = "我的设备",
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, end = 16.dp, bottom = 4.dp),
      )

      // This device is listed first, the way WeChat's page shows 本机 above the others.
      ListItem(
        headlineContent = { Text(state.deviceName.ifBlank { "LiveHub 设备" }) },
        supportingContent = { Text("本机", style = MaterialTheme.typography.labelSmall) },
        colors = listRowColors(),
      )

      if (state.peers.isEmpty()) {
        Text(
          text = "还没有关联设备。点「关联设备」输入对方屏幕上的六位匹配码即可。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        )
      } else {
        state.peers.forEach { peer ->
          // The divider is kept rather than replaced by a list-item colour: these rows share a
          // card with 本机, and a line is what separates rows inside one surface.
          HorizontalDivider()
          ListItem(
            headlineContent = { Text(peer.deviceName.ifBlank { "未知设备" }) },
            supportingContent = {
              Text(
                text = "关联于 ${timeFormatOrDash(peer.pairedAt)}" +
                  if (peer.lastSeenAt > 0) " · 最近 ${timeFormatOrDash(peer.lastSeenAt)}" else "",
                style = MaterialTheme.typography.labelSmall,
              )
            },
            trailingContent = {
              IconButton(onClick = { onAction(UiAction.UnpairPeer(peer.deviceId)) }) {
                Icon(Icons.Filled.Delete, contentDescription = "解除关联")
              }
            },
            colors = listRowColors(),
          )
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
  val push = entry.direction == SyncLogEntry.Direction.PUSH

  // The direction used to be a `↑` / `↓` character whose only state cue was its tint. The direction
  // itself was already in the row's text (`detail` starts with 发送/接收), so what the glyph hid was
  // **failure**: a failed exchange was a red tint and nothing a screen reader could hear, and the
  // note beside it may be absent.
  //
  // The icon now carries both — which way the records went, and whether it worked.
  ListItem(
    leadingContent = {
      Icon(
        imageVector = if (push) Icons.Filled.ArrowUpward else Icons.Filled.ArrowDownward,
        contentDescription = when {
          !entry.success -> if (push) "发送，失败" else "接收，失败"
          push -> "发送"
          else -> "接收"
        },
        modifier = Modifier.size(20.dp),
        tint = if (entry.success) {
          MaterialTheme.colorScheme.primary
        } else {
          MaterialTheme.colorScheme.error
        },
      )
    },
    headlineContent = { Text("${entry.scope} · ${entry.peerName}") },
    supportingContent = {
      Text(
        text = "${timeFormat.format(Date(entry.at))}  $detail",
        style = MaterialTheme.typography.labelSmall,
      )
    },
    colors = listRowColors(),
  )
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
    Column(modifier = Modifier.fillMaxWidth()) {
      // Same split as 我的设备: the heading and its two controls keep the card's 16dp inset, the
      // rows below are `ListItem`s and bring their own.
      Row(
        modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
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
          modifier = Modifier.padding(start = 16.dp, top = 8.dp, end = 16.dp, bottom = 16.dp),
        )
      } else {
        candidates.forEach { peer ->
          ListItem(
            headlineContent = { Text(peer.deviceName.ifBlank { "未命名设备" }) },
            supportingContent = {
              Text(
                text = "${peer.host}:${peer.port}",
                style = MaterialTheme.typography.labelSmall,
              )
            },
            colors = listRowColors(),
          )
        }
        Text(
          text = "在对方设备上打开本页读出六位匹配码，点「关联设备」输入即可。",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 16.dp, top = 4.dp, end = 16.dp, bottom = 16.dp),
        )
      }
    }
  }
}

/**
 * Tag for the 自动同步 switch, for `SyncAutoCardTest`.
 *
 * The card exists since round 87 with no test at all, and it cannot be found by text alone on the
 * smallest phone: the page is a `LazyColumn` and an item below the fold is not composed, so the
 * switch has to be scrolled to before it can be matched. Same shape as the settings screen's switch
 * tags (`BACKGROUND_REFRESH_SWITCH_TAG`), which exist for the same reason.
 */
internal const val AUTO_SYNC_SWITCH_TAG = "autoSyncSwitch"