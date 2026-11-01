package com.livehub.platform.douyu

import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import com.livehub.core.model.StreamVariant
import com.livehub.core.network.decodeHtmlEntities
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
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

  /** `newRecList` uses an absolute offset cursor and reports a total. */
  fun recList(root: JsonObject, offset: Int, limit: Int): DouyuRoomPage {
    val data = root["data"] as? JsonObject
    val list = (data?.get("list") as? JsonArray) ?: JsonArray(emptyList())
    val total = data?.get("total")?.jsonPrimitive?.intOrNull ?: -1
    val rooms = list.mapNotNull { roomFromListElement(it) }
    val consumed = offset + limit
    val hasMore = when {
      rooms.isEmpty() -> false
      total < 0 -> rooms.size >= limit
      else -> consumed < total
    }
    return DouyuRoomPage(
      rooms = rooms,
      nextOffset = if (hasMore) (offset + limit).toString() else null,
    )
  }

  /** `mixListV1` is page based and returns no total, so an empty page ends it. */
  fun mixList(root: JsonObject, page: Int): DouyuRoomPage {
    val data = root["data"] as? JsonObject
    val list = (data?.get("rl") as? JsonArray) ?: JsonArray(emptyList())
    val rooms = list.mapNotNull { roomFromListElement(it) }
    return DouyuRoomPage(
      rooms = rooms,
      nextOffset = if (rooms.isNotEmpty()) (page + 1).toString() else null,
    )
  }

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

    val rooms = list.mapNotNull { element ->
      val obj = element as? JsonObject ?: return@mapNotNull null
      // Douyu mixes anchors and recorded videos in this response.
      val type = obj["type"]?.jsonPrimitive?.intOrNull
      if (type != null && type != 1) return@mapNotNull null

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

    return DouyuRoomPage(
      rooms = rooms,
      nextOffset = if (rooms.size >= pageSize) (page + 1).toString() else null,
    )
  }

  fun betard(root: JsonObject): DouyuRoomInfo {
    val room = root["room"] as? JsonObject
      ?: throw IllegalArgumentException("betard 响应缺少 room 字段")
    val roomId = room.string("room_id").orEmpty()
    val showStatus = room["show_status"]?.jsonPrimitive?.intOrNull ?: 0
    val videoLoop = room["videoLoop"]?.jsonPrimitive?.intOrNull ?: 0
    return DouyuRoomInfo(
      roomId = roomId,
      realRoomId = room.string("room_id").orEmpty(),
      title = room.string("room_name").orEmpty(),
      streamerName = room.string("owner_name").orEmpty(),
      coverUrl = room.string("room_pic")?.let(::normalizeUrl),
      avatarUrl = room.string("owner_avatar")?.let(::normalizeUrl),
      viewerCount = room["hotabit"]?.jsonPrimitive?.longOrNull
        ?: room.string("hot_num")?.toLongOrNull()
        ?: 0,
      isLive = showStatus == 1 && videoLoop != 1,
      categoryId = room.string("cate2_id"),
      categoryName = room.string("cate2_name"),
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

  private fun roomFromListElement(element: JsonElement): Streamer? {
    val obj = element as? JsonObject ?: return null
    val roomId = obj.string("rid") ?: obj.string("room_id") ?: return null
    if (roomId.isBlank()) return null
    return Streamer(
      platform = Platform.DOUYU,
      roomId = roomId,
      streamerName = obj.string("nn") ?: obj.string("nickname").orEmpty(),
      title = obj.string("rn") ?: obj.string("roomName").orEmpty(),
      coverUrl = (obj.string("rs16") ?: obj.string("roomSrc"))?.let(::normalizeUrl),
      avatarUrl = (obj.string("av") ?: obj.string("avatar"))?.let(::normalizeUrl),
      viewerCount = obj["ol"]?.jsonPrimitive?.longOrNull
        ?: obj.string("hn")?.toLongOrNull()
        ?: 0,
      isLive = true,
      updatedAt = System.currentTimeMillis(),
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
