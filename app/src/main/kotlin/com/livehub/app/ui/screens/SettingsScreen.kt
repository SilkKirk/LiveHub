package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Card
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
        Row(
          modifier = Modifier.fillMaxWidth().padding(16.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("深色主题", style = MaterialTheme.typography.bodyLarge)
            Text(
              text = "跟随系统或强制深色",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          Switch(
            checked = state.darkTheme,
            onCheckedChange = { onAction(UiAction.ToggleTheme) },
          )
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
