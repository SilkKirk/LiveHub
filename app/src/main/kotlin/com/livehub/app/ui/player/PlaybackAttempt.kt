package com.livehub.app.ui.player

/**
 * How many times one stream URL may be retried automatically before the failure is fatal.
 *
 * One. A live CDN dropping the connection is routine and a single transparent retry is what every
 * client does; a URL that fails twice in a row is not going to start working, and retrying it
 * forever would hide the 重试 button the user needs.
 */
internal const val MAX_AUTO_RETRIES = 1

/**
 * The playback state that must survive a recomposition but must **not** survive a URL change.
 *
 * Both fields used to be `remember { mutableStateOf(...) }` with no key, which is the same defect
 * as the paging bug two rounds ago: the value is captured on first composition and never resets.
 * Here the consequence is worse than a stale flag —
 *
 *  * [retriesUsed] not resetting means that after **one** automatic retry on the first stream the
 *    budget was gone for the rest of the session, so switching quality or line produced a URL that
 *    was never retried at all, and a CDN blip became a permanent 无法播放;
 *  * [sawFirstFrame] not resetting means the loading scrim could never come back, so a re-resolve
 *    to a *new* URL showed a black rectangle with no "正在连接直播流…" while the new stream was
 *    still connecting.
 *
 * Kept as a plain data class with pure transitions rather than as two `remember`s so the rules can
 * be unit tested: the failures above are invisible to a screenshot (they need two URLs in sequence)
 * and would need a real decoder to reproduce on an emulator.
 *
 * @property url the URL these flags belong to. A different URL means a different stream.
 * @property retriesUsed automatic retries spent on [url].
 * @property sawFirstFrame true once the decoder has produced a frame for [url].
 */
internal data class PlaybackAttempt(
  val url: String,
  val retriesUsed: Int = 0,
  val sawFirstFrame: Boolean = false,
) {
  /** True while the automatic retry budget for [url] is not exhausted. */
  val canRetry: Boolean get() = retriesUsed < MAX_AUTO_RETRIES

  /** True when the loading scrim should cover the surface. */
  val showLoadingScrim: Boolean get() = !sawFirstFrame

  /**
   * The state for [nextUrl], reusing this one only when it describes the same stream.
   *
   * This is the whole fix: switching quality or line changes `url`, so the retry budget and the
   * first-frame flag have to start over.
   */
  fun forUrl(nextUrl: String): PlaybackAttempt =
    if (nextUrl == url) this else PlaybackAttempt(url = nextUrl)

  /** Spends one automatic retry. Callers must check [canRetry] first. */
  fun afterRetry(): PlaybackAttempt = copy(retriesUsed = retriesUsed + 1)

  /** Records that the decoder produced a frame for this URL. */
  fun afterFirstFrame(): PlaybackAttempt = copy(sawFirstFrame = true)
}

/**
 * What the player should do about [error].
 *
 * Extracted from the `Player.Listener` so the decision is testable and so the **current** URL is
 * the one consulted. The listener used to close over the `url` parameter of the composition that
 * registered it — `LaunchedEffect(player)` never re-runs on a URL change — so the IDN downgrade and
 * the retry decision were both made against the URL the screen opened with, not the one playing.
 */
internal sealed interface PlaybackRecovery {
  /**
   * Retry the current URL after a pause.
   *
   * @property attempt the state to adopt, with one retry spent.
   */
  data class Retry(val attempt: PlaybackAttempt) : PlaybackRecovery

  /**
   * Re-open the same stream over plain http.
   *
   * A CDN hostname containing `_` cannot pass Android's IDN/SNI check however often it is retried,
   * and the same host works over cleartext — which is why Huya's manifest allows it.
   */
  data class DowngradeToHttp(val url: String) : PlaybackRecovery

  /** Give up and tell the user, carrying the message to show. */
  data class Fatal(val message: String) : PlaybackRecovery
}

/**
 * Decides what to do when [url] failed with [error].
 *
 * @param attempt the flags for [url]; [PlaybackAttempt.forUrl] must have been applied first.
 * @param isIdnFailure whether the failure chain mentions the IDN/SNI check.
 * @param fallbackMessage what to show when the error carries no message of its own.
 */
internal fun decideRecovery(
  attempt: PlaybackAttempt,
  error: PlaybackError,
  isIdnFailure: Boolean,
  fallbackMessage: String,
): PlaybackRecovery {
  // The IDN downgrade comes first and costs no retry: retrying an unreachable-because-of-its-name
  // host is guaranteed to fail again, while the http form of the same URL works.
  if (isIdnFailure && error.url.startsWith("https://")) {
    return PlaybackRecovery.DowngradeToHttp(error.url.replaceFirst("https://", "http://"))
  }
  if (attempt.canRetry) {
    return PlaybackRecovery.Retry(attempt.afterRetry())
  }
  return PlaybackRecovery.Fatal(error.message?.takeIf { it.isNotBlank() } ?: fallbackMessage)
}

/**
 * The parts of a Media3 failure the decision needs.
 *
 * A tiny value type rather than `PlaybackException` so [decideRecovery] can be exercised on the
 * JVM: `PlaybackException` is a final Android class with a constructor that needs a real error
 * code, and building one in a unit test would couple these assertions to Media3's internals.
 */
internal data class PlaybackError(
  /** The URL that was playing when this error arrived. */
  val url: String,
  val message: String?,
)
