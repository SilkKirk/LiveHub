package com.livehub.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Douyin account card.
 *
 * The account is only needed for keyword search — room ids resolve without it — so the copy
 * says that instead of implying the whole platform needs a login.
 */
@Composable
fun DouyinLoginCard(
  loggedIn: Boolean,
  onLogin: () -> Unit,
  onLogout: () -> Unit,
) {
  Card(modifier = Modifier.fillMaxWidth()) {
    Column(
      modifier = Modifier.padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      Text("抖音账号", style = MaterialTheme.typography.titleMedium)
      Text(
        text = if (loggedIn) {
          "已登录。现在可以在搜索页按昵称/关键词搜抖音直播间；房间号搜索不需要登录。"
        } else {
          "抖音的关键词搜索要求登录（接口会拒绝匿名请求）。登录后即可按昵称搜索；只输入房间号时不用登录。"
        },
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (loggedIn) {
          TextButton(onClick = onLogout) { Text("退出登录") }
        } else {
          // Not 扫码登录: the sheet is a browser session, and it accepts either a QR code or a
          // verification code — see `DouyinWebLoginSheet`'s own copy. The button named one of the
          // two ways in, which made the other look unsupported.
          TextButton(onClick = onLogin) { Text("登录抖音") }
        }
      }
    }
  }
}
