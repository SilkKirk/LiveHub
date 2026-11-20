package com.livehub.app.ui.player

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
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
  /** Fills the surface instead of letterboxing; used for vertical full-bleed rooms. */
  zoomToFill: Boolean = false,
  /**
   * Paused by the user via a double tap.
   *
   * A live stream cannot be seeked back, so pausing is a "hold the picture" control rather
   * than real playback control: ExoPlayer keeps its buffer while paused and resumes at the
   * live edge, which is what a double-tap-to-pause is expected to do here.
   */
  paused: Boolean = false,
  /**
   * Reports the decoded picture's aspect ratio.
   *
   * The screen gates its danmaku overlay on this: without it a vertical stream sat in a
   * hardcoded 16:9 band with ~70% of the phone's picture area black. The value comes from
   * Media3's `VideoSize` (including rotation and pixel ratio), not from the container.
   */
  onVideoAspectRatioChanged: ((Float) -> Unit)? = null,
  /**
   * Emitted when the player gives up on [url].
   *
   * A CDN hostname containing `_` fails Android's IDN/SNI check with
   * "Invalid input to toASCII" no matter how often it is retried; the same host works over
   * plain http, which is what Huya's `*.msstatic.com`-style hosts need. The screen
   * downgrades the URL and re-opens it rather than showing a dead end.
   */
  onFatalError: ((String) -> Unit)? = null,
) {
  val context = LocalContext.current
  val scope = rememberCoroutineScope()

  val player = remember {
    ExoPlayer.Builder(context).build().apply {
      // Audio focus, asked for explicitly.
      //
      // Without this the player never requests focus: a music app that is already playing keeps
      // playing underneath the stream, and nothing ducks or pauses when a call comes in. The second
      // argument is the whole point — ExoPlayer then follows the platform's focus contract instead
      // of mixing with whatever else is running.
      setAudioAttributes(
        AudioAttributes.Builder()
          .setUsage(C.USAGE_MEDIA)
          .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
          .build(),
        /* handleAudioFocus = */ true,
      )
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
    // A new URL must adopt the caller's paused flag rather than resume.
    //
    // `paused` is `state.playerPaused`, which survives a quality or line switch — only `url` changes.
    // This effect re-runs on that change but the `LaunchedEffect(paused)` below does not (its key did
    // not move), so an unconditional `play()` restarted a stream the user had paused while the
    // badge at `PlayerScreen` still said 「已暂停 · 双击继续」. Pause instead of play when asked.
    if (paused) player.pause() else player.play()
  }

  // The caller owns the paused flag; keep the player in step with it.
  LaunchedEffect(paused) {
    if (paused) player.pause() else player.play()
  }

  // Aspect + first-frame detection share one listener so the overlay cannot turn on
  // before the decoder has actually sized the picture.
  //
  // Keyed on `url`: switching quality or line is a new stream, so the retry budget and the
  // first-frame flag have to start over. With no key they were captured on first composition and
  // never reset — after one automatic retry on the opening stream, no later URL was ever retried,
  // and the loading scrim could never come back. See [PlaybackAttempt].
  var attempt by remember(url) { mutableStateOf(PlaybackAttempt(url = url)) }

  // The listener below is registered once (`LaunchedEffect(player)`) and must judge failures
  // against the URL that is *currently* playing, not the one the composition started with.
  // `rememberUpdatedState` is what makes that possible without re-registering the listener.
  val currentAttempt by rememberUpdatedState(attempt)

  /**
   * The caller's paused flag, read at the moment a failure arrives.
   *
   * The listener below is registered once, so a plain `paused` reference there would be the value
   * from the first composition — "not paused", because that is how every room opens. A retry would
   * then resume a stream the user had paused a minute earlier, which is exactly the defect
   * `PlaybackRecovery.Retry.resume` exists to prevent.
   */
  val currentPaused by rememberUpdatedState(paused)

  /**
   * The caller's callbacks, read through [rememberUpdatedState] for the same reason.
   *
   * `LaunchedEffect(player)` never re-runs — `player` is `remember`ed with no keys — so the listener
   * closes over whatever lambdas the *first* composition passed. `onFatalError` from the call site is
   * not a stable reference: it captures the `variant` being played
   * (`PlayerScreen`'s `if (error.startsWith("http://") && !variant.url.startsWith("http://"))`), so a
   * listener holding the first one judged a failure against the variant the screen opened with. After
   * switching quality once, the http-downgrade branch could therefore be skipped for a URL that
   * needed it — the exact confusion `PlaybackAttempt` was introduced to end, one layer out.
   */
  val currentOnFatalError by rememberUpdatedState(onFatalError)
  val currentOnAspectRatioChanged by rememberUpdatedState(onVideoAspectRatioChanged)

  LaunchedEffect(player) {
    val listener = object : Player.Listener {
      override fun onPlayerError(error: PlaybackException) {
        // A host with an underscore in it can never pass Android's IDN/SNI check, so
        // retrying is pointless: hand the caller the http form of the same URL instead.
        val idnFailure = generateSequence(error.cause) { it.cause }
          .mapNotNull { it.message }
          .any { it.contains("toASCII", ignoreCase = true) }

        val decision = decideRecovery(
          attempt = currentAttempt,
          error = PlaybackError(url = currentAttempt.url, message = error.message),
          isIdnFailure = idnFailure,
          // The listener never re-runs (see the note on `currentPaused`), so the flag has to be read
          // through `rememberUpdatedState` rather than captured.
          paused = currentPaused,
          fallbackMessage = "播放器错误: ${error.errorCodeName}",
        )
        when (decision) {
          is PlaybackRecovery.DowngradeToHttp -> currentOnFatalError?.invoke(decision.url)
          is PlaybackRecovery.Retry -> {
            attempt = decision.attempt
            scope.launch {
              delay(RETRY_DELAY_MILLIS)
              player.prepare()
              // The two effects above follow "the caller owns whether the stream is playing"; this
              // third path is the one that used to call `play()` and overrule a pause.
              if (decision.resume) player.play() else player.pause()
            }
          }
          is PlaybackRecovery.Fatal -> currentOnFatalError?.invoke(decision.message)
        }
      }

      override fun onVideoSizeChanged(videoSize: androidx.media3.common.VideoSize) {
        val rawW = videoSize.width
        val rawH = videoSize.height
        val rotation = videoSize.unappliedRotationDegrees
        val pixelRatio = videoSize.pixelWidthHeightRatio
        val (w, h) = if (rotation == 90 || rotation == 270) rawH to rawW else rawW to rawH
        val aspect = if (w > 0 && h > 0) (w.toFloat() * pixelRatio) / h.toFloat() else null
        if (aspect != null) {
          currentOnAspectRatioChanged?.invoke(aspect)
        }
      }

      override fun onRenderedFirstFrame() {
        attempt = currentAttempt.afterFirstFrame()
      }

      override fun onPlaybackStateChanged(playbackState: Int) {
        if (playbackState == Player.STATE_READY) {
          attempt = currentAttempt.afterFirstFrame()
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

  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    AndroidView(
      modifier = Modifier.fillMaxSize(),
      factory = { viewContext ->
        PlayerView(viewContext).apply {
          this.player = player
          // The screen draws its own controls (round side buttons + settings sheet); the
          // Media3 controller would stack a second, differently styled set on top.
          useController = false
          setShowBuffering(PlayerView.SHOW_BUFFERING_WHEN_PLAYING)
          resizeMode = if (zoomToFill) {
            AspectRatioFrameLayout.RESIZE_MODE_ZOOM
          } else {
            AspectRatioFrameLayout.RESIZE_MODE_FIT
          }
          keepScreenOn = true
        }
      },
      update = { view ->
        view.player = player
        view.resizeMode = if (zoomToFill) {
          AspectRatioFrameLayout.RESIZE_MODE_ZOOM
        } else {
          AspectRatioFrameLayout.RESIZE_MODE_FIT
        }
        view.post {
          view.requestLayout()
          view.invalidate()
        }
      },
    )

    // Until the first frame arrives PlayerView draws solid black, which reads as a
    // broken screen. Show the room's identity instead.
    if (attempt.showLoadingScrim) {
      LoadingScrim(modifier = Modifier.fillMaxSize())
    }
  }
}

/**
 * Builds a media source with the headers the streaming CDNs require.
 *
 * Every platform CDN rejects requests without the right `Referer` — Douyu answers 403,
 * Bilibili and Douyin behave the same way. The origin is derived from the stream URL
 * itself rather than passed in, so a new platform cannot forget it.
 */
@androidx.annotation.OptIn(UnstableApi::class)
fun buildMediaSource(
  url: String,
  isHls: Boolean,
  userAgent: String =
    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
  referer: String? = null,
): MediaSource {
  val headers = buildMap {
    put("User-Agent", userAgent)
    val resolved = referer ?: refererFor(url)
    if (resolved != null) put("Referer", resolved)
  }

  val httpFactory = DefaultHttpDataSource.Factory()
    .setAllowCrossProtocolRedirects(true)
    .setConnectTimeoutMs(15_000)
    .setReadTimeoutMs(30_000)
    .setDefaultRequestProperties(headers)

  val mediaItem = MediaItem.fromUri(url)
  return if (isHls || url.contains(".m3u8", ignoreCase = true)) {
    HlsMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
  } else {
    ProgressiveMediaSource.Factory(httpFactory).createMediaSource(mediaItem)
  }
}

/**
 * The `Referer` each CDN demands, matched on the stream host.
 *
 * Huya is matched first because its CDN hostnames (`*.msstatic.com`, `ws-*`) carry no
 * platform marker; the play URLs still contain `huya` in practice, and a wrong Referer
 * only costs a retry.
 */
internal fun refererFor(url: String): String? {
  val lower = url.lowercase()
  return when {
    "huya" in lower -> "https://www.huya.com/"
    "bilibili" in lower || "bilivideo" in lower -> "https://live.bilibili.com/"
    "douyin" in lower || "douyincdn" in lower -> "https://live.douyin.com/"
    "douyu" in lower || "douyucdn" in lower -> "https://www.douyu.com/"
    else -> null
  }
}

private const val RETRY_DELAY_MILLIS = 1_500L

/** Neutral placeholder shown until the decoder produces its first frame. */
@Composable
private fun LoadingScrim(modifier: Modifier = Modifier) {
  Box(modifier = modifier, contentAlignment = Alignment.Center) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
      CircularProgressIndicator(
        color = MaterialTheme.colorScheme.primary,
        strokeWidth = 2.dp,
        modifier = Modifier.size(28.dp),
      )
      Text(
        text = "正在连接直播流…",
        color = Color.White.copy(alpha = 0.7f),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.padding(top = 10.dp),
      )
    }
  }
}