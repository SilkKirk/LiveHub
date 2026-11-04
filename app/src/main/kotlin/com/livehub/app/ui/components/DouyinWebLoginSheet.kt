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

/** What the sheet should do next, given the cookies each host currently holds. */
sealed interface DouyinLoginProgress {
  data object Waiting : DouyinLoginProgress

  /** The passport host has a session; the site itself has not received it yet. */
  data class Handoff(val siteUrl: String) : DouyinLoginProgress

  data class Done(val siteCookie: String) : DouyinLoginProgress
}

/**
 * Decides the next step from both cookie jars.
 *
 * The login happens on `sso.douyin.com`, which sets its own host-scoped cookies; the REST calls
 * need the ones on `www.douyin.com`. Loading the site after the passport host reports a session
 * is what completes that handoff, and it has to be a real navigation — the cookies are set by
 * the redirect chain, not by the login page itself.
 */
fun douyinLoginProgress(siteCookie: String, ssoCookie: String): DouyinLoginProgress = when {
  hasDouyinSession(siteCookie) -> DouyinLoginProgress.Done(siteCookie)
  hasDouyinSession(ssoCookie) -> DouyinLoginProgress.Handoff(SITE_URL)
  else -> DouyinLoginProgress.Waiting
}

/**
 * Douyin's passport login page, in a `WebView`, with the session captured from `CookieManager`.
 *
 * Loading `www.douyin.com` and asking the user to find a login button was the first attempt and
 * it was unusable: the desktop page is laid out for ~980 CSS pixels, so on a phone it rendered
 * as giant, horizontally clipped text. `sso.douyin.com` is the real login page — its title is
 * 「登录抖音短视频」, it ships a `width=device-width` viewport and it calls `/get_qrcode` — so the
 * QR code is the first thing on screen and nothing needs hunting for.
 *
 * No QR endpoint is called directly: reverse-engineering the passport handshake would mean
 * guessing at device parameters, and the reference project takes the same WebView route for
 * Bilibili (`BilibiliWebLoginSheet`). What counts as "logged in" is only ever the session
 * cookie that [DouyinApi] will actually send.
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

  // Polled rather than injected: the page navigates across hosts during login and only the
  // final cookie jars are complete.
  LaunchedEffect(Unit) {
    var handoffDone = false
    while (true) {
      val site = CookieManager.getInstance().getCookie(SITE_URL).orEmpty()
      val sso = CookieManager.getInstance().getCookie(SSO_URL).orEmpty()
      when (val progress = douyinLoginProgress(site, sso)) {
        is DouyinLoginProgress.Done -> {
          onLoggedIn(progress.siteCookie)
          return@LaunchedEffect
        }
        is DouyinLoginProgress.Handoff -> if (!handoffDone) {
          handoffDone = true
          viewHolder.get()?.loadUrl(progress.siteUrl)
        }
        DouyinLoginProgress.Waiting -> Unit
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
              text = "用抖音 App 扫下方二维码；登录成功会自动返回。页面可双指缩放。",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          }
          TextButton(onClick = { viewHolder.get()?.reload() }) { Text("刷新") }
          TextButton(onClick = onDismiss) { Text("关闭") }
        }
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
        AndroidView(
          modifier = Modifier.fillMaxSize(),
          factory = { context ->
            val view = WebView(context)
            view.settings.apply {
              javaScriptEnabled = true
              domStorageEnabled = true
              // The UA has to match what the REST calls send, or Douyin treats the session as
              // belonging to a different client and the cookies do not travel with them.
              userAgentString = DouyinSigner.USER_AGENT
              // Without these a page that has no mobile viewport (the desktop fallback) is
              // rendered at ~980px and clipped, which is what made the first version unusable.
              useWideViewPort = true
              loadWithOverviewMode = true
              setSupportZoom(true)
              builtInZoomControls = true
              displayZoomControls = false
            }
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

private const val LOGIN_URL = "https://sso.douyin.com/"
private const val SSO_URL = "https://sso.douyin.com"
private const val SITE_URL = "https://www.douyin.com"
private const val POLL_INTERVAL_MILLIS = 800L
