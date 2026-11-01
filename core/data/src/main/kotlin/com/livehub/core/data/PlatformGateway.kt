package com.livehub.core.data

import com.livehub.core.model.Category
import com.livehub.core.model.LiveStatus
import com.livehub.core.model.Platform
import com.livehub.core.model.PlaybackSource
import com.livehub.core.model.RoomPage
import com.livehub.core.model.Streamer
import com.livehub.platform.api.DanmakuEvent
import com.livehub.platform.api.LivePlatformAdapter
import kotlinx.coroutines.flow.Flow

/**
 * Resolves the adapter that serves a platform and gives the rest of the app a single
 * entry point for platform traffic.
 *
 * Keeping this behind an interface lets the UI and the data layer be unit tested with
 * a scripted fake, while the production implementation stays a thin lookup.
 */
interface PlatformRegistry {
  fun adapterFor(platform: Platform): LivePlatformAdapter?

  fun supportedPlatforms(): List<Platform>

  fun all(): List<LivePlatformAdapter>
}

/** Default registry backed by the adapters discovered at construction time. */
class MapPlatformRegistry(adapters: List<LivePlatformAdapter>) : PlatformRegistry {
  private val byPlatform = adapters.associateBy { it.platform }

  override fun adapterFor(platform: Platform): LivePlatformAdapter? = byPlatform[platform]

  override fun supportedPlatforms(): List<Platform> =
    Platform.supported.filter { byPlatform.containsKey(it) }

  override fun all(): List<LivePlatformAdapter> = byPlatform.values.toList()
}

/**
 * Facade over [PlatformRegistry] that guarantees a typed error instead of a null
 * check on every call site.
 */
class PlatformGateway(private val registry: PlatformRegistry) {

  fun requireAdapter(platform: Platform): LivePlatformAdapter =
    registry.adapterFor(platform)
      ?: throw NoAdapterException(platform)

  suspend fun categories(platform: Platform): List<Category> =
    requireAdapter(platform).categories()

  suspend fun rooms(
    platform: Platform,
    categoryId: String,
    cursor: String? = null,
    slug: String? = null,
  ): RoomPage = requireAdapter(platform).rooms(categoryId, cursor, slug)

  suspend fun search(platform: Platform, keyword: String, cursor: String? = null): RoomPage =
    requireAdapter(platform).search(keyword, cursor)

  suspend fun roomDetail(platform: Platform, roomId: String): Streamer? =
    requireAdapter(platform).roomDetail(roomId)

  suspend fun liveStatus(platform: Platform, roomId: String): LiveStatus? =
    requireAdapter(platform).liveStatus(roomId)

  suspend fun playback(
    platform: Platform,
    roomId: String,
    preferredQuality: Int? = null,
  ): PlaybackSource = requireAdapter(platform).playback(roomId, preferredQuality)

  fun danmaku(platform: Platform, roomId: String): Flow<DanmakuEvent> =
    requireAdapter(platform).danmaku(roomId)
}

/** Raised when a platform is requested but no adapter is installed. */
class NoAdapterException(val platform: Platform) :
  IllegalStateException("没有为 ${platform.displayName} 安装适配器")
