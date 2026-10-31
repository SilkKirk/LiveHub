package com.livehub.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

/**
 * Media3 backed live player.
 *
 * `PlayerView` is hosted in an [AndroidView] so the controls and renderer stay the
 * battle-tested platform implementations while the rest of the screen stays Compose.
 */
@Composable
fun LivePlayer(
  url: String,
  isHls: Boolean,
  danmakuEnabled: Boolean,
  blockedKeywords: List<String>,
  roomKey: String,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current

  val player = remember {
    ExoPlayer.Builder(context).build().apply {
      playWhenReady = true
      repeatMode = Player.REPEAT_MODE_OFF
    }
  }

  DisposableEffect(Unit) {
    onDispose {
      player.release()
    }
  }

  LaunchedEffect(url, isHls) {
    if (url.isBlank()) return@LaunchedEffect
    val mediaSource = buildMediaSource(context, url, isHls)
    player.setMediaSource(mediaSource)
    player.prepare()
    player.play()
  }

  // Danmaku overlay is rendered by the caller; the player itself only owns video.
  LaunchedEffect(danmakuEnabled, blockedKeywords, roomKey) {
    // Reserved for the danmaku controller wiring (see DanmakuOverlay).
  }

  Box(modifier = modifier) {
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { viewContext ->
        PlayerView(viewContext).apply {
          this.player = player
          useController = true
          setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
          resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
          keepScreenOn = true
        }
      },
      update = { view -> view.player = player },
    )
  }
}

private fun buildMediaSource(
  context: android.content.Context,
  url: String,
  isHls: Boolean,
): MediaSource {
  val httpFactory = DefaultHttpDataSource.Factory()
    .setUserAgent(
      "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
    )
    .setAllowCrossProtocolRedirects(true)
    .setConnectTimeoutMs(15_000)
    .setReadTimeoutMs(30_000)

  val mediaItem = MediaItem.fromUri(url)
  return if (isHls || url.contains(".m3u8", ignoreCase = true)) {
    HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
  } else {
    ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
  }
}
