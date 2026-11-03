package com.livehub.platform.douyu

import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import com.livehub.core.network.decodeHtmlEntities
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** Room metadata from `/betard/{roomId}`. */
data class DouyuRoomInfo(
  val roomId: String,
  val realRoomId: String,
  val title: String,
  val streamerName: String,
  val coverUrl: String?,
  val avatarUrl: String?,
  val viewerCount: Long,
  val isLive: Boolean,
  val categoryId: String?,
  val categoryName: String?,
) {
  fun toStreamer(): Streamer = Streamer(
    platform = Platform.DOUYU,
    roomId = roomId,
    streamerName = streamerName,
    title = title,
    coverUrl = coverUrl,
    avatarUrl = avatarUrl,
    viewerCount = viewerCount,
    isLive = isLive,
    categoryName = categoryName,
    updatedAt = System.currentTimeMillis(),
  )
}

/** One page from either Douyu list endpoint. */
data class DouyuRoomPage(
  val rooms: List<Streamer>,
  val nextOffset: String?,
)

/** One CDN + rate combination returned by `getH5Play`. */
data class DouyuRate(
  val name: String,
  val rate: Int,
  val bitRate: Int,
)

/** Parsed `getH5Play` payload. */
data class DyPlayResponse(
  val rtmpUrl: String,
  val rtmpLive: String,
  val cdns: List<String>,
  val rates: List<DouyuRate>,
) {
  /** Builds a full playable URL for one CDN. */
  fun urlFor(cdn: String): String {
    val base = rtmpUrl.trimEnd('/')
    val live = rtmpLive.trimStart('/')
    return "$base/$live"
  }
}

/**
 * Pure mapping functions for Douyu payloads.
 *
 * Every function takes already-parsed JSON so the mapping can be tested against
 * recorded fixtures without any network access.
 */
object DouyuParser {

  fun categories(cate1: JsonArray, cate2: JsonArray): List<Category> {
    val parents = cate1.mapNotNull { element ->
      val obj = element as? JsonObject ?: return@mapNotNull null
      val id = obj.string("cate1Id") ?: return@mapNotNull null
      val name = obj.string("cate1Name").orEmpty()
      id to name
    }.toMap()

    return cate2.mapNotNull { element ->
      val obj = element as? JsonObject ?: return@mapNotNull null
      val id = obj.string("cate2Id") ?: return@mapNotNull null
      val parentId = obj.string("cate1Id")
      Category(
        platform = Platform.DOUYU,
        id = id,
        name = obj.string("cate2Name").orEmpty(),
        parentId = parentId,
        parentName = parentId?.let { parents[it] },
        iconUrl = obj.string("icon"),
        sortOrder = id.toIntOrNull() ?: Int.MAX_VALUE,
        // `shortName` is the real directory slug; without it the list cannot load.
        slug = obj.string("shortName"),
      )
    }
  }

  /**
   * Maps one `mixListV1` entry (`data.rl[]`).
   *
   * Douyu abbreviates every field here:
   *  * `rid` room id, `rn` room title, `nn` streamer name,
   *  * `av` avatar, `ol` online viewers, `rs16` screenshot,
   *  * `c2url` is the *browsable* directory path and doubles as the category slug.
   */
  fun mixListRoom(element: JsonElement): Streamer? {
    val obj = element as? JsonObject ?: return null
    val roomId = obj.string("rid") ?: return null
    if (roomId.isBlank() || roomId == "0") return null

    val viewers = obj["ol"]?.jsonPrimitive?.longOrNull
      ?: obj.string("ol")?.toLongOrNull()
      ?: 0

    return Streamer(
      platform = Platform.DOUYU,
      roomId = roomId,
      streamerName = obj.string("nn").orEmpty().trim().ifBlank { "斗鱼主播" },
      title = obj.string("rn").orEmpty().trim().ifBlank { "直播中" },
      coverUrl = (obj.string("rs16") ?: obj.string("rs16_avif"))?.let(::normalizeUrl),
      avatarUrl = obj.string("av")?.let(::normalizeUrl),
      viewerCount = viewers,
      // mixListV1 only lists rooms that are on air.
      isLive = true,
      updatedAt = System.currentTimeMillis(),
    )
  }

  /**
   * Maps `/japi/search/api/searchUser`.
   *
   * Every element is an **envelope**: `{"type": 1, "anchorInfo": {…}}`. The room id,
   * nickname and live flag all live inside `anchorInfo`, not at the element level, so a
   * mapper that reads them directly from the element finds nothing and drops the whole
   * page. Recorded rooms (`type != 1`) share the same envelope and must be filtered on
   * the *envelope's* `type`.
   *
   * `data.total` (the full match count) is present, so paging stops on it rather than on
   * a page-size guess.
   */
  fun search(root: JsonObject, page: Int, pageSize: Int): DouyuRoomPage {
    val container = root["data"] ?: root["relateUser"] ?: root["relate_user"]
    val list = when (container) {
      is JsonArray -> container
      is JsonObject ->
        (container["relateUser"] as? JsonArray)
          ?: (container["relate_user"] as? JsonArray)
          ?: (container["list"] as? JsonArray)
          ?: JsonArray(emptyList())
      else -> JsonArray(emptyList())
    }
    val total = (container as? JsonObject)?.get("total")?.jsonPrimitive?.intOrNull

    val rooms = list.mapNotNull { element ->
      val envelope = element as? JsonObject ?: return@mapNotNull null
      // Douyu mixes anchors and recorded videos in this response.
      val type = envelope["type"]?.jsonPrimitive?.intOrNull
      if (type != null && type != 1) return@mapNotNull null

      val obj = envelope.anchorObject()
      val roomId = obj.string("rid")
        ?: obj.string("room_id")
        ?: obj.string("roomId")
        ?: obj.string("bkUrl")?.substringAfterLast('/')
        ?: return@mapNotNull null
      if (roomId.isBlank() || roomId == "0") return@mapNotNull null

      val name = obj.string("nickName")
        ?: obj.string("nickname")
        ?: obj.string("user_name")
        ?: obj.string("userName")
        ?: return@mapNotNull null

      Streamer(
        platform = Platform.DOUYU,
        roomId = roomId,
        streamerName = name,
        title = obj.string("roomName")
          ?: obj.string("room_name")
          ?: obj.string("description")
          ?: "暂无标题",
        avatarUrl = obj.string("avatar")?.let(::normalizeUrl),
        coverUrl = null,
        viewerCount = 0,
        isLive = obj.liveFlag(),
        updatedAt = System.currentTimeMillis(),
      )
    }

    // `total` counts every match; a page that came back short (or whose offset already
    // covers the total) is the last one. Falling back to the raw page length matters
    // because filtered-out recordings would otherwise end the list early.
    val consumed = page * pageSize
    val hasMore = when {
      list.isEmpty() -> false
      total != null && total > 0 -> consumed < total
      else -> list.size >= pageSize
    }
    return DouyuRoomPage(
      rooms = rooms,
      nextOffset = if (hasMore) (page + 1).toString() else null,
    )
  }

  /**
   * The object carrying the anchor fields.
   *
   * Search results wrap everything in `anchorInfo`; a response that ever returns the
   * fields directly keeps working because the envelope itself is used as the fallback.
   */
  private fun JsonObject.anchorObject(): JsonObject =
    (this["anchorInfo"] as? JsonObject) ?: this

  /**
   * Maps `/betard/{roomId}`.
   *
   * Three fields need the fallbacks below, all confirmed against a live payload:
   *
   *  * `cate2_name` is present but **empty** — the game label is `second_lvl_name`
   *    (with `game.tag_name` at the root as a second fallback), so reading only the
   *    documented key left every room without a category.
   *  * `hotabit`/`hot_num` do not exist; the viewer count is `room_biz_all.hot`
   *    (a string) with `iol` as the fallback.
   *  * `nickname` and `owner_name` both exist and agree; `owner_name` stays as the
   *    fallback because some rooms only send that one.
   */
  fun betard(root: JsonObject): DouyuRoomInfo {
    val room = root["room"] as? JsonObject
      ?: throw IllegalArgumentException("betard 响应缺少 room 字段")
    val roomId = room.string("room_id").orEmpty()
    val showStatus = room["show_status"]?.jsonPrimitive?.intOrNull ?: 0
    val videoLoop = room["videoLoop"]?.jsonPrimitive?.intOrNull ?: 0
    val hot = room["room_biz_all"]?.let { it as? JsonObject }?.string("hot")
    val gameTagName = root["game"]?.let { it as? JsonObject }?.string("tag_name")
    return DouyuRoomInfo(
      roomId = roomId,
      realRoomId = room.string("room_id").orEmpty(),
      title = room.string("room_name").orEmpty(),
      streamerName = room.string("nickname") ?: room.string("owner_name").orEmpty(),
      coverUrl = room.string("room_pic")?.let(::normalizeUrl),
      avatarUrl = room.string("owner_avatar")?.let(::normalizeUrl),
      viewerCount = hot?.toLongOrNull()
        ?: room["iol"]?.jsonPrimitive?.longOrNull
        ?: room.string("hot_num")?.toLongOrNull()
        ?: 0,
      isLive = showStatus == 1 && videoLoop != 1,
      categoryId = room.string("cate2_id"),
      categoryName = room.string("cate2_name") ?: room.string("second_lvl_name") ?: gameTagName,
    )
  }

  fun play(root: JsonObject): DyPlayResponse {
    val error = root["error"]?.jsonPrimitive?.intOrNull ?: 0
    if (error != 0) {
      val msg = root.string("msg").orEmpty()
      throw IllegalArgumentException("斗鱼播放地址获取失败: $error $msg")
    }
    val data = root["data"] as? JsonObject
      ?: throw IllegalArgumentException("斗鱼播放数据为空")
    val rtmpUrl = data.string("rtmp_url").orEmpty()
    val rtmpLive = decodeHtmlEntities(data.string("rtmp_live").orEmpty())
    val cdns = (data["cdnsWithName"] as? JsonArray)
      ?.mapNotNull { (it as? JsonObject)?.string("cdn") }
      ?.filter { it.isNotBlank() }
      .orEmpty()
    val rates = (data["multirates"] as? JsonArray)
      ?.mapNotNull { element ->
        val obj = element as? JsonObject ?: return@mapNotNull null
        val rate = obj["rate"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
        DouyuRate(
          name = obj.string("name").orEmpty(),
          rate = rate,
          bitRate = obj["bit"]?.jsonPrimitive?.intOrNull ?: 0,
        )
      }
      .orEmpty()
    return DyPlayResponse(
      rtmpUrl = rtmpUrl,
      rtmpLive = rtmpLive,
      cdns = cdns.ifEmpty { listOf(DEFAULT_DOUYU_CDN) },
      rates = rates,
    )
  }

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonElement)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

  private fun JsonObject.liveFlag(): Boolean {
    val isLive = this["isLive"]?.jsonPrimitive?.intOrNull
      ?: this["is_live"]?.jsonPrimitive?.intOrNull
    val loop = this["isLoop"]?.jsonPrimitive?.intOrNull
      ?: this["videoLoop"]?.jsonPrimitive?.intOrNull
    return when (isLive) {
      1 -> true
      2 -> false
      else -> loop != 1
    }
  }

  private fun normalizeUrl(raw: String): String = when {
    raw.isBlank() -> raw
    raw.startsWith("http://") || raw.startsWith("https://") -> raw
    raw.startsWith("//") -> "https:$raw"
    else -> "https://$raw"
  }

  const val DEFAULT_DOUYU_CDN = "ws-h5"
}
