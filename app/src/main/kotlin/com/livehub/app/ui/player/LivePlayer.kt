package com.livehub.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.hls.HlsMediaSource
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Media3 backed live player.
 *
 * `PlayerView` is hosted in an [AndroidView] so the controls and the renderer stay the
 * battle-tested platform implementations while everything else stays Compose.
 */
@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun LivePlayer(
  url: String,
  isHls: Boolean,
  modifier: Modifier = Modifier,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  val player = remember {
    ExoPlayer.Builder(context).build().apply {
      playWhenReady = true
      repeatMode = Player.REPEAT_MODE_OFF
    }
  }

  DisposableEffect(Unit) {
    onDispose { player.release() }
  }

  LaunchedEffect(url, isHls) {
    if (url.isBlank()) return@LaunchedEffect
    player.setMediaSource(buildMediaSource(url, isHls))
    player.prepare()
    player.play()
  }

  LaunchedEffect(player) {
    val listener = object : Player.Listener {
      override fun onPlayerError(error: PlaybackException) {
        // A live CDN dropping the stream is routine; retry once after a short pause.
        scope.launch {
          delay(RETRY_DELAY_MILLIS)
          player.prepare()
          player.play()
        }
      }
    }
    player.addListener(listener)
    try {
      awaitCancellation()
    } finally {
      player.removeListener(listener)
    }
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

/** Builds a media source with the headers the streaming CDNs require. */
@androidx.annotation.OptIn(UnstableApi::class)
fun buildMediaSource(
  url: String,
  isHls: Boolean,
  userAgent: String =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
  referer: String? = null,
): MediaSource {
  val httpFactory = DefaultHttpDataSource.Factory()
    .setUserAgent(userAgent)
    .setAllowCrossProtocolRedirects(true)
    .setConnectTimeoutMs(15_000)
    .setReadTimeoutMs(30_000)
    .apply { if (referer != null) setDefaultRequestProperties(mapOf("Referer" to referer)) }

  val mediaItem = MediaItem.fromUri(url)
  return if (isHls || url.contains(".m3u8", ignoreCase = true)) {
    HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
  } else {
    ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
  }
}

private const val RETRY_DELAY_MILLIS = 1_500L
