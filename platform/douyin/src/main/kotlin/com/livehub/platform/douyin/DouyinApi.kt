package com.livehub.platform.douyin

import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import com.livehub.core.network.HttpClient
import com.livehub.core.network.Texts
import com.livehub.platform.api.PlatformOfflineException
import com.livehub.platform.api.PlatformProtocolException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One HLS rendition advertised by Douyin's `room/web/enter` payload. */
data class DouyinVariant(
  val name: String,
  val url: String,
  val rank: Int,
)

/** Parsed `room/web/enter` payload. */
data class DouyinRoomInfo(
  val roomId: String,
  val title: String,
  val streamerName: String,
  val avatarUrl: String?,
  val coverUrl: String?,
  val viewerCount: Long,
  val isLive: Boolean,
  val variants: List<DouyinVariant>,
) {
  fun toStreamer(): Streamer = Streamer(
    platform = Platform.DOUYIN,
    roomId = roomId,
    streamerName = streamerName,
    title = title,
    coverUrl = coverUrl,
    avatarUrl = avatarUrl,
    viewerCount = viewerCount,
    isLive = isLive,
    updatedAt = System.currentTimeMillis(),
  )
}

/** HTTP + parsing for Douyin live. */
class DouyinApi(private val http: HttpClient) {

  val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
  }

  suspend fun categories(): List<Category> = DouyinCategories.all()

  suspend fun rooms(categoryId: String, page: Int): Pair<List<Streamer>, Boolean> {
    val params = listOf(
      "aid" to "6383",
      "app_name" to "douyin_web",
      "live_id" to "1",
      "device_platform" to "web",
      "language" to "zh-CN",
      "enter_from" to "web_live",
      "cookie_enabled" to "true",
      "screen_width" to "1920",
      "screen_height" to "1080",
      "browser_language" to "zh-CN",
      "browser_platform" to "Win32",
      "browser_name" to "Chrome",
      "browser_version" to "122.0.0.0",
      "partition" to categoryId,
      "partition_type" to "1",
      "req_from" to "2",
      "offset" to ((page - 1) * PAGE_SIZE).toString(),
      "count" to PAGE_SIZE.toString(),
    )
    val signed = DouyinSigner.sign(params, DouyinSigner.USER_AGENT)
    val url = "$URL_PARTITION?${signed.queryString()}"
    val response = http.get(url, headers = headers())
    val root = response.requireSuccess().let { parseObject(it.body, "partition") }
    val list = root["data"]?.jsonObject?.get("data")?.jsonArray
      ?: root["data"]?.jsonArray
      ?: JsonArray(emptyList())
    val rooms = list.mapNotNull { parseRoomCard(it) }
    return rooms to (rooms.size >= PAGE_SIZE)
  }

  suspend fun search(keyword: String, page: Int): Pair<List<Streamer>, Boolean> {
    val params = listOf(
      "device_platform" to "webapp",
      "aid" to "6383",
      "channel" to "channel_pc_web",
      "search_channel" to "aweme_live",
      "keyword" to keyword,
      "search_source" to "normal_search",
      "query_correct_type" to "1",
      "is_filter_search" to "0",
      "offset" to ((page - 1) * PAGE_SIZE).toString(),
      "count" to PAGE_SIZE.toString(),
    )
    val signed = DouyinSigner.sign(params, DouyinSigner.USER_AGENT)
    val url = "$URL_SEARCH?${signed.queryString()}"
    val response = http.get(url, headers = headers())
    val root = response.requireSuccess().let { parseObject(it.body, "search") }
    val list = root["data"]?.jsonArray ?: JsonArray(emptyList())
    val rooms = list.mapNotNull { element ->
      val obj = element as? JsonObject ?: return@mapNotNull null
      val live = obj["lives"]?.jsonObject ?: obj
      parseRoomCard(live)
    }
    return rooms to (rooms.size >= PAGE_SIZE)
  }

  suspend fun roomInfo(roomId: String): DouyinRoomInfo {
    val params = listOf(
      "aid" to "6383",
      "app_name" to "douyin_web",
      "live_id" to "1",
      "device_platform" to "web",
      "language" to "zh-CN",
      "enter_from" to "web_live",
      "cookie_enabled" to "true",
      "screen_width" to "1920",
      "screen_height" to "1080",
      "browser_language" to "zh-CN",
      "browser_platform" to "Win32",
      "browser_name" to "Chrome",
      "browser_version" to "122.0.0.0",
      "web_rid" to roomId,
    )
    val signed = DouyinSigner.sign(params, DouyinSigner.USER_AGENT)
    val url = "$URL_ENTER?${signed.queryString()}"
    val response = http.get(url, headers = headers())
    val root = response.requireSuccess().let { parseObject(it.body, "enter") }
    val data = root["data"]?.jsonObject
      ?: throw PlatformProtocolException("抖音房间数据为空", Platform.DOUYIN)
    val room = data["room"]?.jsonObject
      ?: throw PlatformProtocolException("抖音房间数据缺失 room 字段", Platform.DOUYIN)
    val status = room["status"]?.jsonPrimitive?.intOrNull ?: 2
    val owner = room["owner"]?.jsonObject
    val streamUrl = room["stream_url"]?.jsonObject
    return DouyinRoomInfo(
      roomId = room["id_str"]?.jsonPrimitive?.contentOrNull ?: roomId,
      title = room["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      streamerName = owner?.get("nickname")?.jsonPrimitive?.contentOrNull.orEmpty(),
      avatarUrl = owner?.get("avatar_thumb")?.jsonObject?.get("url_list")
        ?.jsonArray?.firstOrNull()?.jsonPrimitive?.contentOrNull?.let(Texts::normalizeUrl),
      coverUrl = room["cover"]?.jsonObject?.get("url_list")?.jsonArray?.firstOrNull()
        ?.jsonPrimitive?.contentOrNull?.let(Texts::normalizeUrl),
      viewerCount = room["stats"]?.jsonObject?.get("total_user")
        ?.jsonPrimitive?.longOrNull
        ?: room["user_count_str"]?.jsonPrimitive?.contentOrNull?.let { parseCount(it) }
        ?: 0,
      isLive = status == 2,
      variants = parseVariants(streamUrl),
    )
  }

  suspend fun playback(roomId: String): List<StreamVariant> {
    val info = roomInfo(roomId)
    if (!info.isLive) throw PlatformOfflineException("主播未开播", Platform.DOUYIN)
    if (info.variants.isEmpty()) {
      throw PlatformProtocolException("抖音未解析到播放地址", Platform.DOUYIN)
    }
    return info.variants.map { variant ->
      StreamVariant(
        qualityLabel = variant.name,
        url = variant.url,
        lineName = "抖音",
        isHls = variant.url.contains(".m3u8", ignoreCase = true),
        qualityRank = variant.rank,
        qualityCode = variant.rank,
      )
    }
  }

  private fun parseVariants(streamUrl: JsonObject?): List<DouyinVariant> {
    if (streamUrl == null) return emptyList()
    val builder = mutableListOf<DouyinVariant>()
    val hls = streamUrl["hls_pull_url_map"] as? JsonObject
    if (hls != null) {
      hls.entries.forEachIndexed { index, entry ->
        val url = entry.value.jsonPrimitive.contentOrNull ?: return@forEachIndexed
        if (url.isBlank()) return@forEachIndexed
        builder += DouyinVariant(
          name = qualityName(entry.key),
          url = url,
          rank = qualityRank(entry.key, index),
        )
      }
    }
    streamUrl["hls_pull_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { url ->
      if (builder.none { it.url == url }) {
        builder += DouyinVariant(name = "原画", url = url, rank = 0)
      }
    }
    val flv = streamUrl["flv_pull_url"] as? JsonObject
    flv?.entries?.forEachIndexed { index, entry ->
      val url = entry.value.jsonPrimitive.contentOrNull ?: return@forEachIndexed
      if (url.isBlank()) return@forEachIndexed
      if (builder.any { it.url == url }) return@forEachIndexed
      builder += DouyinVariant(
        name = qualityName(entry.key),
        url = url,
        rank = qualityRank(entry.key, index) + 10,
      )
    }
    return builder.sortedBy { it.rank }
  }

  private fun qualityName(key: String): String = when {
    key.contains("origin", ignoreCase = true) || key == "FULL_HD1" -> "原画"
    key == "HD1" -> "蓝光"
    key == "SD1" -> "高清"
    key == "SD2" -> "标清"
    else -> key
  }

  private fun qualityRank(key: String, index: Int): Int = when {
    key.contains("origin", ignoreCase = true) || key == "FULL_HD1" -> 0
    key == "HD1" -> 1
    key == "SD1" -> 2
    key == "SD2" -> 3
    else -> 10 + index
  }

  private fun parseRoomCard(element: JsonElement): Streamer? {
    val obj = element as? JsonObject ?: return null
    val room = obj["room"]?.jsonObject ?: obj
    val idText = room["id_str"]?.jsonPrimitive?.contentOrNull
      ?: room["web_rid"]?.jsonPrimitive?.contentOrNull
      ?: room["id"]?.jsonPrimitive?.longOrNull?.toString()
      ?: return null
    val owner = room["owner"]?.jsonObject
    val status = room["status"]?.jsonPrimitive?.intOrNull
    return Streamer(
      platform = Platform.DOUYIN,
      roomId = idText,
      streamerName = owner?.get("nickname")?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "抖音主播" },
      title = room["title"]?.jsonPrimitive?.contentOrNull.orEmpty().ifBlank { "直播中" },
      coverUrl = room["cover"]?.jsonObject?.get("url_list")?.jsonArray?.firstOrNull()
        ?.jsonPrimitive?.contentOrNull?.let(Texts::normalizeUrl),
      avatarUrl = owner?.get("avatar_thumb")?.jsonObject?.get("url_list")?.jsonArray
        ?.firstOrNull()?.jsonPrimitive?.contentOrNull?.let(Texts::normalizeUrl),
      viewerCount = room["stats"]?.jsonObject?.get("total_user")?.jsonPrimitive?.longOrNull
        ?: room["user_count_str"]?.jsonPrimitive?.contentOrNull?.let { parseCount(it) }
        ?: 0,
      isLive = status == null || status == 2,
      updatedAt = System.currentTimeMillis(),
    )
  }

  private fun parseCount(raw: String): Long {
    val trimmed = raw.trim()
    val multiplier = when {
      trimmed.endsWith("亿") -> 100_000_000.0
      trimmed.endsWith("万") || trimmed.endsWith("w") || trimmed.endsWith("W") -> 10_000.0
      else -> 1.0
    }
    val numeric = trimmed.dropLastWhile { !it.isDigit() && it != '.' }
    return numeric.toDoubleOrNull()?.let { (it * multiplier).toLong() } ?: 0
  }

  private fun headers(): Map<String, String> = mapOf(
    "User-Agent" to DouyinSigner.USER_AGENT,
    "Referer" to "https://live.douyin.com/",
    "Accept" to "application/json, text/plain, */*",
    "Accept-Language" to "zh-CN,zh;q=0.9",
  )

  private fun parseObject(body: String, label: String): JsonObject = try {
    json.parseToJsonElement(body).jsonObject
  } catch (error: Exception) {
    throw PlatformProtocolException("抖音 $label 返回无法解析的 JSON", Platform.DOUYIN, error)
  }

  companion object {
    const val URL_PARTITION = "https://live.douyin.com/webcast/web/partition/detail/room/"
    const val URL_SEARCH = "https://live.douyin.com/webcast/search/partition/room/"
    const val URL_ENTER = "https://live.douyin.com/webcast/room/web/enter/"
    const val PAGE_SIZE = 20
  }
}
