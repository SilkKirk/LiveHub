package com.livehub.app.ui.components

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.livehub.platform.douyin.DouyinSigner
import com.livehub.platform.douyin.hasDouyinSession
import kotlinx.coroutines.delay
import java.util.concurrent.atomic.AtomicReference

/**
 * Douyin's own login page, in a `WebView`, with the session captured from `CookieManager`.
 *
 * There is no QR endpoint to call: Douyin's web login is a page that shows a QR code (or an
 * SMS form) and sets `sessionid` once the app confirms the scan. Reverse-engineering the
 * passport flow would mean guessing at device parameters and signatures, so the sheet loads
 * the real site and watches for the cookie — the same approach the reference project uses for
 * Bilibili (`BilibiliWebLoginSheet`).
 *
 * What this sheet does **not** do is trust a page-level "logged in" signal: the only evidence
 * accepted is a session cookie, because that is exactly what [DouyinApi]'s keyword search
 * sends when it asks the server for results.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun DouyinWebLoginSheet(
  onDismiss: () -> Unit,
  onLoggedIn: (cookieHeader: String) -> Unit,
) {
  // A plain holder rather than Compose state: writing state from `AndroidView.factory` would
  // recompose during composition, and nothing in the layout depends on the instance.
  val viewHolder = remember { AtomicReference<WebView?>(null) }

  DisposableEffect(Unit) {
    onDispose {
      viewHolder.getAndSet(null)?.let { view ->
        runCatching { view.stopLoading() }
        runCatching { view.destroy() }
      }
    }
  }

  // The cookie is the credential, so it is polled rather than injected: the page navigates
  // across several hosts during login and only the final cookie header is complete.
  LaunchedEffect(Unit) {
    while (true) {
      val cookie = CookieManager.getInstance().getCookie(SITE_URL).orEmpty()
      if (hasDouyinSession(cookie)) {
        onLoggedIn(cookie)
        return@LaunchedEffect
      }
      delay(POLL_INTERVAL_MILLIS)
    }
  }

  Dialog(
    onDismissRequest = onDismiss,
    properties = DialogProperties(usePlatformDefaultWidth = false),
  ) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
      Column(modifier = Modifier.fillMaxSize()) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Column(modifier = Modifier.weight(1f)) {
            Text("抖音登录", style = MaterialTheme.typography.titleMedium)
            Text(
              text = "在页面右上角点「登录」，用抖音 App 扫码。登录成功会自动返回。",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          TextButton(onClick = onDismiss) { Text("关闭") }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        AndroidView(
          modifier = Modifier.fillMaxSize(),
          factory = { context ->
            val view = WebView(context)
            view.settings.javaScriptEnabled = true
            view.settings.domStorageEnabled = true
            // The UA has to match what the REST calls send, or Douyin treats the session as
            // belonging to a different client and the cookies do not travel with them.
            view.settings.userAgentString = DouyinSigner.USER_AGENT
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
            view.loadUrl(LOGIN_URL)
            viewHolder.set(view)
            view
          },
        )
      }
    }
  }
}

private const val LOGIN_URL = "https://www.douyin.com/"
private const val SITE_URL = "https://www.douyin.com"
private const val POLL_INTERVAL_MILLIS = 800L
