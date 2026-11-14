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
  /**
   * The name for controls, where [displayName] does not fit.
   *
   * The home screen's switcher gives each of the four platforms a quarter of the row — 66dp of a
   * 320dp phone, minus the tab's own padding — and "哔哩哔哩" is four characters of it. It
   * rendered as "哔哩": no ellipsis, no error, just a label that stops mid-word. Controls get the
   * short name; sentences keep the long one, because "在斗鱼、虎牙、抖音、哔哩哔哩里搜索" is prose
   * and prose has a whole line to wrap in.
   *
   * "B站" rather than "哔哩": it is what the platform is called in speech, and what the reference
   * client puts in its own navigation (`screenshots/douyu.jpeg`).
   */
  val shortName: String,
) {
  DOUYU("douyu", "斗鱼", "https://www.douyu.com", "斗鱼"),
  HUYA("huya", "虎牙", "https://www.huya.com", "虎牙"),
  DOUYIN("douyin", "抖音", "https://live.douyin.com", "抖音"),
  BILIBILI("bilibili", "哔哩哔哩", "https://live.bilibili.com", "B站"),
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
  /**
   * True for the "全部直播 / 热门" entry that leads the category row.
   *
   * It is a [Category] so it can travel the same path as a real one — selected by the row,
   * carried in `UiState`, requested through the adapter's `rooms` — but it is not a
   * partition: it belongs to no group (so the group picker must not list it), it publishes
   * no third level, and it is not a valid default landing category.
   */
  val isAggregate: Boolean = false,
) {
  val key: String get() = "${platform.id}:$id"

  companion object {
    /**
     * The id every adapter maps to its own aggregate feed.
     *
     * Real ids on all four platforms are numeric — Douyu `cate2Id`, Huya `iGid`, Bilibili
     * `"parent:area"`, Douyin `"partitionType:partition"` — so a literal cannot collide with
     * one. It is deliberately *not* Bilibili's `0:0`: that id already means "the hot tab" to
     * the Bilibili adapter, and the shared marker has to stay platform-neutral.
     */
    const val ALL_ID = "all"

    /** Builds the aggregate entry that leads the row for [platform]. */
    fun aggregate(platform: Platform, name: String): Category = Category(
      platform = platform,
      id = ALL_ID,
      name = name,
      sortOrder = Int.MIN_VALUE,
      isAggregate = true,
    )
  }
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

/**
 * A danmaku (bullet chat) message normalised across platforms.
 *
 * Two fields were removed: `userLevel` and `fansClubLevel`. Neither was ever written by any adapter
 * nor read by any surface — they existed because the reference client *decodes* them, but it does
 * not render them either (the same conclusion round 23 reached about Douyu's `col`). A field that
 * is always 0 on every message is not a capability, and it carried a per-message allocation on the
 * hottest path in the app: every danmaku in every room.
 *
 * [color] stays because it **is** read (`DanmakuOverlay` tints the label with it), even though no
 * adapter fills it yet — so today every danmaku draws white. Wiring it up needs each platform's
 * own encoding validated against a real capture first; see the note in `DanmakuOverlay`.
 */
data class DanmakuMessage(
  val roomKey: String,
  val userName: String,
  val text: String,
  val color: Int? = null,
  val receivedAt: Long = 0,
  /**
   * Position in the room's chat stream, assigned by the collector as messages arrive.
   *
   * A rolling buffer (`takeLast(cap)`) makes *positional* identity useless: once it is full every
   * arrival shifts every index by one, so a list keyed on the index considers **every** row
   * replaced on every message and rebuilds them all. A monotonic per-collector counter stays put
   * as the buffer slides, which is what a `LazyColumn` key needs.
   *
   * Zero means "not assigned": adapters never set it and a list built by hand (a test, a
   * screenshot fixture) keeps the default. Readers fall back to the buffer's own order there.
   */
  val seq: Long = 0,
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
