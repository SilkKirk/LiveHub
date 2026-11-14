package com.livehub.core.data

import com.livehub.core.common.BackoffPolicy
import com.livehub.core.common.TtlCache
import com.livehub.core.model.Category
import com.livehub.core.model.LiveStatus
import com.livehub.core.model.Platform
import com.livehub.core.model.PlaybackSource
import com.livehub.core.model.RoomPage
import com.livehub.core.model.Streamer
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
   * [Retryable] means "the same request might work in a moment": a dropped connection, a timeout,
   * a 5xx or a 429. Everything else is answered immediately, because retrying it spends battery on
   * a request whose answer cannot change:
   *
   *  * domain errors ("主播未开播", "room not found", "log in first") are decisions, not accidents;
   *  * **4xx responses are decisions too** — a signed URL whose signature expired, a 404, a rejected
   *    parameter. `NetworkException` extends `IOException`, so the old rule treated every one of
   *    them as transient and re-fetched a whole room page three times with backoff, adding a second
   *    of delay to a failure that was already final.
   */
  suspend fun <T> withRetry(block: suspend () -> T): T {
    val backoff = BackoffPolicy(retryPolicy.initialDelay, retryPolicy.maxDelay, retryPolicy.multiplier)
    var lastError: Throwable? = null
    repeat(MAX_ATTEMPTS) { attempt ->
      try {
        val result = block()
        backoff.reset()
        return result
      } catch (error: Throwable) {
        lastError = error
        if (!isRetryable(error) || attempt == MAX_ATTEMPTS - 1) throw error
        backoff.sleepNext()
      }
    }
    throw lastError ?: IllegalStateException("retry loop finished without a result")
  }

  private fun isRetryable(error: Throwable): Boolean = when (error) {
    is com.livehub.platform.api.PlatformOfflineException -> false
    is com.livehub.platform.api.PlatformNotFoundException -> false
    is com.livehub.platform.api.PlatformAuthRequiredException -> false
    is com.livehub.platform.api.PlatformProtocolException -> false
    // An HTTP status is the server's final answer for this request. Only the two that mean "try
    // again" are retried; 408 is the third, and it arrives as a socket timeout rather than a
    // response on this stack.
    is com.livehub.core.network.NetworkException -> error.code >= 500 || error.code == 429
    is java.io.IOException -> true
    else -> false
  }

  /** Wrapper so a null result can be cached as well. */
  data class StreamerSnapshot(val value: Streamer?)

  companion object {
    const val MAX_ATTEMPTS = 3
    const val CATEGORY_TTL_MILLIS = 6L * 60 * 60 * 1000
    const val ROOM_DETAIL_TTL_MILLIS = 30L * 1000
  }
}
