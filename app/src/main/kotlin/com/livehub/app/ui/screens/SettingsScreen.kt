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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState

/** Settings screen: theme, danmaku and blocked keywords. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(state: UiState, onAction: (UiAction) -> Unit) {
  var keywordDraft by remember { mutableStateOf("") }

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(16.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
  ) {
    item {
      SectionTitle("外观")
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
          Text("主题", style = MaterialTheme.typography.bodyLarge)
          Text(
            text = "跟随系统时打开深浅色开关由系统决定，选择深色或浅色则固定不变",
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
      SectionTitle("弹幕")
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
      SectionTitle("弹幕屏蔽词")
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
      SectionTitle("账号")
      com.livehub.app.ui.components.BilibiliLoginCard(
        state = state.bilibiliLogin,
        onStartLogin = { onAction(UiAction.StartBilibiliLogin) },
        onCancel = { onAction(UiAction.CancelBilibiliLogin) },
        onLogout = { onAction(UiAction.LogoutBilibili) },
      )
    }

    item {
      SectionTitle("关于")
      Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          Text("LiveHub", style = MaterialTheme.typography.titleMedium)
          Text(
            text = "聚合直播客户端，支持斗鱼、虎牙、抖音与哔哩哔哩。仅用于学习交流。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

@Composable
private fun SectionTitle(text: String) {
  Text(
    text = text,
    style = MaterialTheme.typography.titleSmall,
    fontWeight = FontWeight.SemiBold,
    modifier = Modifier.padding(top = 4.dp),
  )
}

private val THEME_OPTIONS = listOf(
  "跟随系统" to com.livehub.core.designsystem.ThemeMode.System,
  "深色" to com.livehub.core.designsystem.ThemeMode.Dark,
  "浅色" to com.livehub.core.designsystem.ThemeMode.Light,
)
