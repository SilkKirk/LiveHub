package com.livehub.platform.api

import com.livehub.core.model.Category
import com.livehub.core.model.LiveStatus
import com.livehub.core.model.Platform
import com.livehub.core.model.PlaybackSource
import com.livehub.core.model.RoomPage
import com.livehub.core.model.Streamer
import com.livehub.core.model.StreamVariant
import kotlinx.coroutines.flow.Flow

/**
 * The single integration point every streaming platform implements.
 *
 * Implementations are stateless with respect to the UI: they take plain parameters,
 * perform network work on [kotlinx.coroutines.Dispatchers.IO] and either return a
 * value or throw [PlatformException]. Caching and retry policies live *above* this
 * interface so every platform behaves identically.
 */
interface LivePlatformAdapter {
  /** The platform this adapter serves. Exactly one adapter per platform. */
  val platform: Platform

  /**
   * Categories available for browsing, ordered by [Category.sortOrder].
   * Implementations should cache aggressively — this is called on every home render.
   */
  suspend fun categories(): List<Category>

  /**
   * One page of live rooms for [categoryId].
   *
   * @param cursor opaque cursor previously returned by [RoomPage.nextCursor];
   *   null requests the first page.
   * @param slug the platform's own URL slug for the category, when it publishes one
   *   (Douyu `shortName`). Passed through so adapters never have to guess it from the
   *   numeric id — Douyu's `/directory/game/<cate2Id>` answers 404.
   */
  suspend fun rooms(categoryId: String, cursor: String? = null, slug: String? = null): RoomPage

  /** Searches live rooms by keyword. */
  suspend fun search(keyword: String, cursor: String? = null): RoomPage

  /** Fetches current details for a single room, used to refresh the follow list. */
  suspend fun roomDetail(roomId: String): Streamer?

  /** Lightweight live check used by the periodic follow-list refresh. */
  suspend fun liveStatus(roomId: String): LiveStatus?

  /**
   * Resolves playable stream URLs for [roomId].
   *
   * @param preferredQuality platform specific quality code, or null for "best".
   * @throws PlatformException when the room is offline or resolution fails.
   */
  suspend fun playback(roomId: String, preferredQuality: Int? = null): PlaybackSource

  /**
   * Opens a danmaku stream for [roomId].
   *
   * The flow is cold: collecting it connects, emits normalised messages and
   * disconnects when the collector is cancelled. Reconnection with backoff is the
   * responsibility of the adapter so platform specific handshakes stay encapsulated.
   */
  fun danmaku(roomId: String): Flow<DanmakuEvent>
}

/** Events emitted by a danmaku connection. */
sealed interface DanmakuEvent {
  data object Connected : DanmakuEvent

  data class Message(val message: com.livehub.core.model.DanmakuMessage) : DanmakuEvent

  data class Disconnected(val reason: String, val willRetry: Boolean) : DanmakuEvent

  data class Failed(val error: PlatformException) : DanmakuEvent
}

/** Typed failure raised by platform adapters. */
open class PlatformException(
  message: String,
  val platform: Platform? = null,
  cause: Throwable? = null,
) : Exception(message, cause) {
  val kind: Kind
    get() = when (this) {
      is PlatformOfflineException -> Kind.OFFLINE
      is PlatformNotFoundException -> Kind.NOT_FOUND
      is PlatformAuthRequiredException -> Kind.AUTH_REQUIRED
      is PlatformProtocolException -> Kind.PROTOCOL
      else -> Kind.UNKNOWN
    }

  enum class Kind { OFFLINE, NOT_FOUND, AUTH_REQUIRED, PROTOCOL, UNKNOWN }
}

/** The room exists but the broadcaster is not streaming. */
class PlatformOfflineException(
  message: String = "主播未开播",
  platform: Platform? = null,
  cause: Throwable? = null,
) : PlatformException(message, platform, cause)

/** The platform responded, but the room or resource does not exist. */
class PlatformNotFoundException(
  message: String,
  platform: Platform? = null,
  cause: Throwable? = null,
) : PlatformException(message, platform, cause)

/** The platform requires a logged-in session (for example Bilibili 原画). */
class PlatformAuthRequiredException(
  message: String,
  platform: Platform? = null,
  cause: Throwable? = null,
) : PlatformException(message, platform, cause)

/** The platform changed its protocol or returned an unusable payload. */
class PlatformProtocolException(
  message: String,
  platform: Platform? = null,
  cause: Throwable? = null,
) : PlatformException(message, platform, cause)

/** Quality option advertised by a platform before resolving URLs. */
data class QualityOption(
  val code: Int,
  val label: String,
  val rank: Int = code,
  val variants: List<StreamVariant> = emptyList(),
)
