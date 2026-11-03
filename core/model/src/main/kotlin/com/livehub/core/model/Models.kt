package com.livehub.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Every streaming platform LiveHub aggregates.
 *
 * The [id] is stable and persisted (follow lists, sync log, preferences) so it must
 * never change once released. New platforms append new entries.
 */
@Serializable
enum class Platform(
  val id: String,
  val displayName: String,
  val homePage: String,
) {
  DOUYU("douyu", "斗鱼", "https://www.douyu.com"),
  HUYA("huya", "虎牙", "https://www.huya.com"),
  DOUYIN("douyin", "抖音", "https://live.douyin.com"),
  BILIBILI("bilibili", "哔哩哔哩", "https://live.bilibili.com"),
  ;

  companion object {
    /** All platforms that currently ship an adapter, in display order. */
    val supported: List<Platform> get() = entries

    fun fromId(id: String): Platform? = entries.firstOrNull { it.id.equals(id, ignoreCase = true) }
  }
}

/**
 * A live room as shown in list, search and follow surfaces.
 *
 * Instances are persisted (follow list) and synchronised across devices, so every
 * field must stay backward compatible: add optional fields with defaults instead of
 * changing existing ones.
 */
@Serializable
data class Streamer(
  val platform: Platform,
  /** Platform native room id. Stable across sessions. */
  val roomId: String,
  val streamerName: String,
  val title: String = "",
  val coverUrl: String? = null,
  val avatarUrl: String? = null,
  /** Raw viewer count; format it with `ViewerCountFormatter` at the UI edge. */
  val viewerCount: Long = 0,
  val isLive: Boolean = false,
  val categoryName: String? = null,
  /** Last time this snapshot was refreshed, epoch millis. */
  val updatedAt: Long = 0,
) {
  /** Globally unique, stable identity used by the follow list and the sync log. */
  val key: String get() = keyOf(platform, roomId)

  companion object {
    fun keyOf(platform: Platform, roomId: String): String = "${platform.id}:$roomId"
  }
}

/** A browsable or searchable subset of a platform's live catalogue. */
@Serializable
data class Category(
  val platform: Platform,
  /**
   * Platform specific identifier, already encoded for the adapter:
   * Douyu `cate2Id`, Huya `iGid`, Bilibili `"parent:area"`, Douyin `partition id`.
   */
  val id: String,
  val name: String,
  /** Parent grouping used by the two level selector (for example 斗鱼 大区). */
  val parentId: String? = null,
  val parentName: String? = null,
  val iconUrl: String? = null,
  val sortOrder: Int = 0,
  /**
   * The platform's own URL slug for this category, when it publishes one.
   *
   * Douyu's directory pages are keyed by a slug (`LOL`, `wzry`, `yqk`) that is **not**
   * derivable from `cate2Id` — the numeric id 404s. The category endpoint returns it as
   * `shortName`, so it has to travel with the model instead of being guessed.
   */
  val slug: String? = null,
) {
  val key: String get() = "${platform.id}:$id"
}

/** One page of a paginated room list. */
@Serializable
data class RoomPage(
  val rooms: List<Streamer>,
  /**
   * Opaque cursor for the next page, or null when the list is exhausted.
   * Adapters use page numbers, offsets or continuation tokens internally.
   */
  @SerialName("next")
  val nextCursor: String? = null,
) {
  val hasMore: Boolean get() = nextCursor != null
  val isEmpty: Boolean get() = rooms.isEmpty()

  companion object {
    val Empty = RoomPage(rooms = emptyList(), nextCursor = null)
  }
}

/** A single playable stream variant returned by a platform resolver. */
@Serializable
data class StreamVariant(
  /** Human readable quality label, e.g. 原画 / 蓝光4M / 高清 / 标清. */
  val qualityLabel: String,
  /** Platform for the stream url. */
  val url: String,
  /** CDN / line identifier for the UI selector. */
  val lineName: String? = null,
  /** True when the container is HLS; Media3 picks the player module by mime type. */
  val isHls: Boolean = false,
  /** Relative cost used for ordering; lower is better. */
  val qualityRank: Int = Int.MAX_VALUE,
  /** Platform specific quality code (Douyu rate, Bilibili qn, ...). */
  val qualityCode: Int? = null,
) {
  val identity: String get() = "${qualityLabel}@${lineName.orEmpty()}@$url"
}

/** Everything required to start playback of a room. */
data class PlaybackSource(
  val room: Streamer,
  val variants: List<StreamVariant>,
  /** The variant the resolver recommends. */
  val defaultVariant: StreamVariant? = variants.firstOrNull(),
  /**
   * Every CDN / line the platform advertises for this room.
   *
   * Populated by every platform that has a CDN axis: Douyu's `cdnsWithName`, Huya's distinct
   * `gameStreamInfoList` hosts, Bilibili's candidate hosts. It used to be empty for "platforms
   * where a line *is* a variant", which is what made Huya list one variant per CDN entry and
   * the quality row draw six identical 原画 chips.
   */
  val lines: List<String> = emptyList(),
  /** The line the resolver actually used, so the UI can mark it as selected. */
  val selectedLine: String? = null,
) {
  val isEmpty: Boolean get() = variants.isEmpty()
}

/** A danmaku (bullet chat) message normalised across platforms. */
data class DanmakuMessage(
  val roomKey: String,
  val userName: String,
  val text: String,
  val color: Int? = null,
  val userLevel: Int = 0,
  val fansClubLevel: Int = 0,
  val receivedAt: Long = 0,
)

/** A live status snapshot for one followed streamer. */
@Serializable
data class LiveStatus(
  val platform: Platform,
  val roomId: String,
  val isLive: Boolean,
  val title: String? = null,
  val viewerCount: Long = 0,
  val coverUrl: String? = null,
  val checkedAt: Long = 0,
) {
  val key: String get() = Streamer.keyOf(platform, roomId)
}
