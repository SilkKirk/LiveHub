package com.livehub.core.data

import com.livehub.core.common.BackoffPolicy
import com.livehub.core.common.TtlCache
import com.livehub.core.model.Category
import com.livehub.core.model.LiveStatus
import com.livehub.core.model.Platform
import com.livehub.core.model.PlaybackSource
import com.livehub.core.model.RoomPage
import com.livehub.core.model.Streamer
import com.livehub.platform.api.PlatformException
import com.livehub.platform.api.PlatformNetworkException
import com.livehub.platform.api.PlatformProtocolException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Cross-cutting policy applied to *every* platform call: timeout, bounded retry with
 * backoff and a short-lived cache for the read-heavy endpoints.
 *
 * Concentrating this here keeps platform adapters free of retry logic and guarantees
 * that a flaky CDN behaves the same on Douyu, Huya, Douyin and Bilibili.
 */
class ResilientPlatformRepository(
  private val gateway: PlatformGateway,
  private val retryPolicy: BackoffPolicy = BackoffPolicy(300.milliseconds, 2_000.milliseconds),
  private val categoryCache: TtlCache<String, List<Category>> = TtlCache(ttlMillis = CATEGORY_TTL_MILLIS),
  private val roomDetailCache: TtlCache<String, StreamerSnapshot> = TtlCache(ttlMillis = ROOM_DETAIL_TTL_MILLIS),
) {
  suspend fun categories(platform: Platform, forceRefresh: Boolean = false): List<Category> {
    val key = platform.id
    if (forceRefresh) categoryCache.invalidate(key)
    return categoryCache.getOrPut(key) { gateway.categories(platform) }
  }

  suspend fun rooms(
    platform: Platform,
    categoryId: String,
    cursor: String? = null,
    slug: String? = null,
  ): RoomPage = withRetry { gateway.rooms(platform, categoryId, cursor, slug) }

  /** Third level of browsing, cached alongside the list it belongs to. */
  suspend fun subCategories(platform: Platform, categoryId: String): List<Category> =
    withRetry { gateway.subCategories(platform, categoryId) }

  suspend fun search(platform: Platform, keyword: String, cursor: String? = null): RoomPage =
    withRetry { gateway.search(platform, keyword, cursor) }

  suspend fun roomDetail(platform: Platform, roomId: String, forceRefresh: Boolean = false): Streamer? {
    val key = Streamer.keyOf(platform, roomId)
    if (forceRefresh) roomDetailCache.invalidate(key)
    return roomDetailCache.getOrPut(key) { StreamerSnapshot(gateway.roomDetail(platform, roomId)) }.value
  }

  suspend fun liveStatus(platform: Platform, roomId: String): LiveStatus? =
    withRetry { gateway.liveStatus(platform, roomId) }

  suspend fun playback(
    platform: Platform,
    roomId: String,
    preferredQuality: Int? = null,
    preferredLine: String? = null,
  ): PlaybackSource = withRetry { gateway.playback(platform, roomId, preferredQuality, preferredLine) }

  /**
   * Runs [block] and retries transient failures.
   *
   * Retryable means "the same request might work in a moment": a dropped connection, a timeout, a
   * 5xx or a 429. Everything else is answered immediately, because retrying it spends battery on a
   * request whose answer cannot change:
   *
   *  * domain errors ("主播未开播", "room not found", "log in first") are decisions, not accidents;
   *  * **4xx responses are decisions too** — a signed URL whose signature expired, a 404, a rejected
   *    parameter. `NetworkException` extends `IOException`, so an earlier rule treated every one of
   *    them as transient and re-fetched a whole room page three times with backoff.
   *
   * Whatever finally escapes is passed through [classified], so the screen never receives a raw
   * `IOException` or an adapter's stray `IllegalArgumentException`. That matters because those
   * carry library text: `Failed to connect to /151.101.1.6:443` and
   * `URLDecoder: Illegal hex characters …` were both rendered verbatim on a Chinese screen.
   *
   * Cancellation is rethrown untouched — see [classified].
   */
  suspend fun <T> withRetry(block: suspend () -> T): T {
    val backoff = BackoffPolicy(retryPolicy.initialDelay, retryPolicy.maxDelay, retryPolicy.multiplier)
    var lastError: Throwable? = null
    repeat(MAX_ATTEMPTS) { attempt ->
      try {
        val result = block()
        backoff.reset()
        return result
      } catch (cancelled: CancellationException) {
        // Not a failure, and not something to classify: the caller went away. Converting it would
        // turn every superseded request into a reportable error.
        throw cancelled
      } catch (error: Throwable) {
        lastError = error
        if (!isRetryable(error) || attempt == MAX_ATTEMPTS - 1) throw classified(error)
        backoff.sleepNext()
      }
    }
    throw classified(lastError ?: IllegalStateException("retry loop finished without a result"))
  }

  /**
   * Decides retry from [PlatformException.kind] rather than from the class of the throw.
   *
   * Two exceptions to the kind rule, both because they are the retry policy's own vocabulary:
   * [com.livehub.core.network.NetworkException] is an HTTP status (retry only 5xx/429, and it must
   * be checked before `IOException` because it *is* one), and a bare `IOException` is a transport
   * failure the adapters have not wrapped.
   */
  private fun isRetryable(error: Throwable): Boolean = when (error) {
    is com.livehub.core.network.NetworkException -> error.code >= 500 || error.code == 429
    is PlatformException -> error.kind == PlatformException.Kind.NETWORK
    is java.io.IOException -> true
    else -> false
  }

  /**
   * Puts whatever [error] is into the app's own vocabulary before it reaches a screen.
   *
   *  * a [PlatformException] already is: adapters raise these on purpose, in Chinese, with the right
   *    [PlatformException.kind];
   *  * cancellation is rethrown as-is, so a superseded request stays a cancellation;
   *  * an `IOException` becomes [PlatformNetworkException] — the transport failed, which is a
   *    different thing to tell the user than "this room is not on air";
   *  * **an HTTP status is not that.** `NetworkException` extends `IOException`, and calling a 403
   *    "网络连接失败" would send the user to check their Wi-Fi over an expired signature;
   *  * anything else becomes [PlatformProtocolException]: the platform answered something this build
   *    cannot read. Its own message is deliberately *not* reused — a library's exception text is
   *    English and describes the library, not the user's situation.
   */
  private fun classified(error: Throwable): Throwable = when {
    error is PlatformException -> error
    error is CancellationException -> error
    // An `Error` is not a request outcome (OOM, StackOverflow, a failed assertion). Wrapping it in
    // an `Exception` would make a fatal condition look like something the UI can retry.
    error is Error -> error
    error is com.livehub.core.network.NetworkException ->
      PlatformProtocolException("平台返回了 HTTP ${error.code}，请稍后重试", platform = null, cause = error)
    error is java.io.IOException -> PlatformNetworkException(cause = error)
    else -> PlatformProtocolException(
      "平台返回了无法处理的数据，请稍后重试",
      platform = null,
      cause = error,
    )
  }

  /** Wrapper so a null result can be cached as well. */
  data class StreamerSnapshot(val value: Streamer?)

  companion object {
    const val MAX_ATTEMPTS = 3
    const val CATEGORY_TTL_MILLIS = 6L * 60 * 60 * 1000
    const val ROOM_DETAIL_TTL_MILLIS = 30L * 1000
  }
}
