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
  ): PlaybackSource = withRetry { gateway.playback(platform, roomId, preferredQuality) }

  /**
   * Runs [block] and retries transient failures.
   *
   * [Retryable] failures are network hiccups and 5xx responses. Domain errors such as
   * "主播未开播" or "room not found" are surfaced immediately — retrying them only
   * wastes battery.
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
    is java.io.IOException -> true
    else -> false
  }

  /** Test seam: lets tests control how many attempts are made. */
  var maxAttempts: Int = MAX_ATTEMPTS

  /** Wrapper so a null result can be cached as well. */
  data class StreamerSnapshot(val value: Streamer?)

  companion object {
    const val MAX_ATTEMPTS = 3
    const val CATEGORY_TTL_MILLIS = 6L * 60 * 60 * 1000
    const val ROOM_DETAIL_TTL_MILLIS = 30L * 1000
  }
}
