package com.livehub.app.ui.screens

import android.content.Context
import android.util.Log
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import coil3.SingletonImageLoader
import com.livehub.app.BuildConfig
import com.livehub.app.sync.FollowRefreshWorker
import com.livehub.app.ui.components.SectionHeader
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings screen: theme, danmaku, blocked keywords, the image cache and accounts. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(state: UiState, onAction: (UiAction) -> Unit) {
  var keywordDraft by remember { mutableStateOf("") }
  // The sheet owns a WebView, so its visibility is page state, not ViewModel state: a
  // configuration change should close it rather than silently keep a logged-in browser alive.
  var showDouyinLogin by remember { mutableStateOf(false) }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      SectionHeader("外观")
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("主题", style = MaterialTheme.typography.bodyLarge)
          Text(
            text = "选择「跟随系统」时跟随系统的深浅色设置；选择深色或浅色则固定不变。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          // Three states, because "follow the system" is a real choice: a boolean switch
          // could only express two of them and made 浅色 unreachable.
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            THEME_OPTIONS.forEach { (label, mode) ->
              FilterChip(
                selected = state.themeMode == mode,
                onClick = { onAction(UiAction.SetThemeMode(mode)) },
                label = { Text(label) },
              )
            }
          }
        }
      }
    }

    item {
      SectionHeader("弹幕")
      Card(modifier = Modifier.fillMaxWidth()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("显示弹幕", style = MaterialTheme.typography.bodyLarge)
            Text(
              text = "关闭后仅播放画面",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = state.danmakuEnabled,
            onCheckedChange = { onAction(UiAction.ToggleDanmaku(it)) },
          )
        }
      }
    }

    item {
      SectionHeader("弹幕屏蔽词")
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
              value = keywordDraft,
              onValueChange = { keywordDraft = it },
              label = { Text("添加屏蔽词") },
              singleLine = true,
              modifier = Modifier.weight(1f),
            )
            TextButton(
              onClick = {
                if (keywordDraft.isNotBlank()) {
                  onAction(UiAction.SetBlockedKeywords(state.blockedKeywords + keywordDraft))
                  keywordDraft = ""
                }
              },
            ) { Text("添加") }
          }
          if (state.blockedKeywords.isEmpty()) {
            Text(
              text = "暂无屏蔽词",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            state.blockedKeywords.forEach { keyword ->
              Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(keyword, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(
                  onClick = {
                    onAction(UiAction.SetBlockedKeywords(state.blockedKeywords - keyword))
                  },
                ) { Text("移除") }
              }
            }
          }
        }
      }
    }

    item {
      SectionHeader("后台刷新")
      BackgroundRefreshCard(state = state, onAction = onAction)
    }

    item {
      SectionHeader("存储")
      ImageCacheCard(onAction = onAction)
    }

    item {
      SectionHeader("账号")
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        com.livehub.app.ui.components.BilibiliLoginCard(
          state = state.bilibiliLogin,
          onStartLogin = { onAction(UiAction.StartBilibiliLogin) },
          onCancel = { onAction(UiAction.CancelBilibiliLogin) },
          onLogout = { onAction(UiAction.LogoutBilibili) },
        )
        com.livehub.app.ui.components.DouyinLoginCard(
          loggedIn = state.douyinLoggedIn,
          onLogin = { showDouyinLogin = true },
          onLogout = { onAction(UiAction.LogoutDouyin) },
        )
      }
    }

    item {
      SectionHeader("关于")
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
          ) {
            Text(
              text = "LiveHub",
              style = MaterialTheme.typography.titleMedium,
              modifier = Modifier.weight(1f),
            )
            // The version is the first thing anyone reporting a bug is asked for, and the About
            // card used to carry only the product name — so "which build are you on?" could not be
            // answered from inside the app at all. `versionName` is what the release workflow
            // derives from the git tag, so it identifies the build exactly.
            Text(
              text = "v${BuildConfig.VERSION_NAME}",
              style = MaterialTheme.typography.labelMedium,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Text(
            text = "聚合直播客户端，支持斗鱼、虎牙、抖音与哔哩哔哩。仅用于学习交流。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }

  if (showDouyinLogin) {
    com.livehub.app.ui.components.DouyinWebLoginSheet(
      onDismiss = { showDouyinLogin = false },
      onLoggedIn = { cookie ->
        onAction(UiAction.DouyinLoggedIn(cookie))
        showDouyinLogin = false
      },
    )
  }
}

/**
 * The background refresh card: the switch that lets the app check the follow list on its own, and
 * the restriction that keeps those checks off mobile data.
 *
 * The interval in the subtitle comes from [FollowRefreshWorker.INTERVAL_MINUTES] rather than a
 * literal, so the sentence cannot promise a different cadence than the one actually scheduled.
 */
@Composable
private fun BackgroundRefreshCard(state: UiState, onAction: (UiAction) -> Unit) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("后台刷新开播状态", style = MaterialTheme.typography.bodyLarge)
          Text(
            text = "每 ${FollowRefreshWorker.INTERVAL_MINUTES} 分钟检查一次关注列表里谁开播了",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = state.backgroundRefreshEnabled,
          onCheckedChange = { onAction(UiAction.SetBackgroundRefresh(it)) },
          modifier = Modifier.testTag(BACKGROUND_REFRESH_SWITCH_TAG),
        )
      }
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("仅 Wi-Fi 下刷新", style = MaterialTheme.typography.bodyLarge)
          Text(
            text = "移动数据下不检查，避免在不知情时用掉流量。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
          checked = state.refreshOnWifiOnly,
          onCheckedChange = { onAction(UiAction.SetRefreshOnWifiOnly(it)) },
          // Disabled, not removed, while the switch above is off. A row that disappeared would
          // read as "this option never existed" and hide the fact that the choice is remembered
          // and will apply again the moment the first switch goes back on.
          enabled = state.backgroundRefreshEnabled,
          modifier = Modifier.testTag(WIFI_ONLY_SWITCH_TAG),
        )
      }
    }
  }
}


/**
 * The image cache card: what Coil is holding on disk and in memory, and the one control that
 * frees it.
 *
 * Coil 3 defaults to a disk cache of 2% of the free space and nothing in the app showed it or
 * offered a way to drop it, so an install only ever grew. The number is read off the composition
 * thread and the card says "—" until (or unless) the read succeeds: a cache that has never been
 * written is `0 B`, so printing `0 B` after a failed read would hide the failure behind a number
 * that looks like success.
 */
@Composable
private fun ImageCacheCard(onAction: (UiAction) -> Unit) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()
  var cacheBytes by remember { mutableStateOf<Long?>(null) }

  LaunchedEffect(context) { cacheBytes = readImageCacheBytes(context) }

  // null means "could not be read", which is not the same thing as an empty cache.
  val sizeText = cacheBytes?.let { formatCacheSize(it) } ?: "—"

  Card(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(16.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Column(modifier = Modifier.weight(1f)) {
        Text("图片缓存", style = MaterialTheme.typography.bodyLarge)
        Text(
          text = "当前占用 ${sizeText}，清除后封面需要重新加载。",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      // `heightIn(min = 48.dp)` is a floor on top of Material's own minimum interactive size, so
      // the target stays 48dp even if that enforcement is switched off through a CompositionLocal.
      // It also cannot shrink the way `size(48.dp)` around smaller content can — sizing the
      // caller-side modifier is exactly how this project broke the 48dp minimum twice.
      TextButton(
        onClick = {
          // The clear walks Coil's disk directory, so it runs off the main thread, and the
          // snackbar is sent from the same coroutine so "已清除图片缓存" is not a claim about a
          // clear that has not run yet. The size is read again rather than assumed to be zero:
          // what clearing actually left behind is the honest thing to show.
          scope.launch {
            clearImageCaches(context)
            cacheBytes = readImageCacheBytes(context)
            onAction(UiAction.ShowMessage("已清除图片缓存"))
          }
        },
        modifier = Modifier.heightIn(min = 48.dp),
      ) {
        Text("清除缓存")
      }
    }
  }
}

/**
 * Formats a cache size for the card: `0 B`, `999 B`, `1.5 KB`, `12.4 MB`, `3.0 GB`.
 *
 * Whole bytes stay whole — a byte count is exact, and `1.0 B` would read like a rounded
 * measurement — while every larger unit keeps one decimal so the number does not change width as
 * the cache grows past a unit boundary.
 */
internal fun formatCacheSize(bytes: Long): String {
  if (bytes < CACHE_KB) return "${bytes.coerceAtLeast(0L)} B"
  val (unit, divisor) = when {
    bytes < CACHE_MB -> "KB" to CACHE_KB.toDouble()
    bytes < CACHE_GB -> "MB" to CACHE_MB.toDouble()
    else -> "GB" to CACHE_GB.toDouble()
  }
  // Locale.US on purpose: the separator has to be a decimal point. The default locale would write
  // "12,4 MB" on a device set to a comma-decimal language, where the comma reads as a thousands
  // separator and the size looks sixteen times larger than it is.
  return String.format(Locale.US, "%.1f %s", bytes / divisor, unit)
}

// Binary multiples, which is what the sizes shown to a user are built from (1024 bytes per KB).
private const val CACHE_KB = 1024L
private const val CACHE_MB = 1024L * CACHE_KB
private const val CACHE_GB = 1024L * CACHE_MB

/**
 * Identifiers for the two background-refresh switches.
 *
 * The label next to each switch is a separate semantics node, so `onNodeWithText` finds the text
 * and never the control — and "is the Wi-Fi switch disabled while the feature is off?" is exactly
 * an assertion about the control. The tags are `internal` so the test refers to the same constant
 * the screen uses instead of a string that could drift from it.
 */
internal const val BACKGROUND_REFRESH_SWITCH_TAG = "backgroundRefreshSwitch"
internal const val WIFI_ONLY_SWITCH_TAG = "wifiOnlySwitch"

/** Log tag for the two cache calls; a failed read or clear should not disappear silently. */
private const val CACHE_TAG = "SettingsScreen"

/**
 * Bytes Coil currently holds, or null when the caches cannot be read.
 *
 * Both caches are nullable in Coil 3 (`ImageLoader.diskCache` and `memoryCache`) and a null one
 * simply holds nothing, so only a read that throws counts as unknown. Every call into Coil's cache
 * API this screen makes is in this function or in [clearImageCaches]: if an upgrade renames one of
 * them there are two places to fix, not a scattering.
 */
private suspend fun readImageCacheBytes(context: Context): Long? = withContext(Dispatchers.IO) {
  // `runCatching` and not `try`/`catch (Exception)`: a cache that cannot even be created is
  // already the "read failed" case, whatever the platform throws for it (a missing directory, an
  // unreadable journal, a class the release build stripped), and none of those may take the
  // settings page down. The block has no suspension point, so nothing can be cancelled inside it.
  runCatching {
    val loader = SingletonImageLoader.get(context)
    (loader.diskCache?.size ?: 0L) + (loader.memoryCache?.size ?: 0L)
  }.getOrElse { error ->
    // null keeps the failure visible as "—" instead of pretending the cache is empty.
    Log.w(CACHE_TAG, "读取图片缓存占用失败", error)
    null
  }
}

/**
 * Empties both of Coil's caches.
 *
 * Best effort: an entry that will not be deleted is no reason to leave the button dead, so a
 * failure is logged and the caller re-reads the size anyway.
 */
private suspend fun clearImageCaches(context: Context) {
  withContext(Dispatchers.IO) {
    runCatching {
      val loader = SingletonImageLoader.get(context)
      loader.diskCache?.clear()
      loader.memoryCache?.clear()
    }.onFailure { error ->
      Log.w(CACHE_TAG, "清除图片缓存失败", error)
    }
  }
}

private val THEME_OPTIONS = listOf(
  "跟随系统" to com.livehub.core.designsystem.ThemeMode.System,
  "深色" to com.livehub.core.designsystem.ThemeMode.Dark,
  "浅色" to com.livehub.core.designsystem.ThemeMode.Light,
)
