package com.livehub.app.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.livehub.platform.bilibili.BilibiliAuth

/**
 * Bilibili QR login card.
 *
 * The QR bitmap is produced from the URL the platform returns; the caller polls
 * [BilibiliAuth.poll] until the state leaves [BilibiliAuth.PollResult.Waiting] /
 * [BilibiliAuth.PollResult.Scanned].
 */
@Composable
fun BilibiliLoginCard(
  state: BiliLoginUi,
  onStartLogin: () -> Unit,
  onCancel: () -> Unit,
  onLogout: () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text("哔哩哔哩账号", style = MaterialTheme.typography.titleMedium)
      Text(
        text = if (state.loggedIn) {
          "已登录。登录后可以获取原画清晰度，并在弹幕中显示你的身份。"
        } else {
          "扫码登录后可以观看原画清晰度。未登录时部分直播间只能观看低清晰度。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      when {
        state.loading && state.qr == null -> {
          Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            Text(
              text = "正在获取二维码…",
              style = MaterialTheme.typography.bodySmall,
              modifier = Modifier.padding(start = 8.dp),
            )
          }
        }
        state.qr != null -> {
          Box(
            modifier = Modifier
              .align(Alignment.CenterHorizontally)
              .size(180.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(Color.White)
              .padding(8.dp),
          ) {
            Image(
              bitmap = state.qr,
              contentDescription = "哔哩哔哩登录二维码",
              modifier = Modifier.fillMaxWidth(),
            )
          }
          Text(
            text = state.status.ifBlank { "请使用哔哩哔哩客户端扫码" },
            style = MaterialTheme.typography.bodySmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
          )
          TextButton(onClick = onCancel, modifier = Modifier.align(Alignment.CenterHorizontally)) {
            Text("取消")
          }
        }
        else -> {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (state.loggedIn) {
              TextButton(onClick = onLogout) { Text("退出登录") }
            } else {
              TextButton(onClick = onStartLogin) { Text("扫码登录") }
            }
          }
          if (state.status.isNotBlank()) {
            Text(
              text = state.status,
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
        }
      }
    }
  }
}
