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
import kotlinx.serialization.json.booleanOrNull
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

  /**
   * Live rooms for a partition.
   *
   * `webcast/web/partition/detail/room` now answers HTTP 444 (Douyin's anti-bot status)
   * for requests that do not carry a full browser fingerprint, including ones with a
   * valid `ttwid`. `webcast/feed` still serves JSON to an anonymous client.
   *
   * Two hard platform limits follow from that substitution:
   *  * **No partition filtering.** The feed ignores `partition`, `partition_type` and
   *    `tag_id`; every combination returns the same rooms.
   *  * **No pagination.** `offset` is ignored and `count` is capped at 6 items whatever
   *    value is sent, so the result is always a single page of 6.
   *
   * The adapter reports `hasMore = false` rather than letting an infinite-scroll list
   * spin forever on duplicates.
   */
  /**
   * One page of rooms for a partition.
   *
   * This is the endpoint that actually honours `partition`/`partition_type`; the
   * anonymous `webcast/feed` path ignored every partition parameter and capped at six
   * rooms. It requires an `a_bogus` signature — without it Douyin answers HTTP 200 with
   * an empty body, which previously looked like "this partition has no rooms".
   */
  suspend fun rooms(
    partition: String,
    partitionType: String,
    page: Int,
  ): Pair<List<Streamer>, Boolean> {
    val offset = ((page - 1) * PAGE_SIZE).coerceAtLeast(0)
    val params = listOf(
      "aid" to "6383",
      "app_name" to "douyin_web",
      "live_id" to "1",
      "device_platform" to "web",
      "language" to "zh-CN",
      "enter_from" to "web_homepage_hot",
      "cookie_enabled" to "true",
      "screen_width" to "1920",
      "screen_height" to "1080",
      "browser_language" to "zh-CN",
      "browser_platform" to "Win32",
      "browser_name" to "Chrome",
      "browser_version" to DouyinSigner.BROWSER_VERSION,
      "count" to PAGE_SIZE.toString(),
      "offset" to offset.toString(),
      "partition" to partition,
      "partition_type" to partitionType,
      "req_from" to "2",
      // An empty msToken is rejected; the web client always sends a 107 character value.
      "msToken" to DouyinSigner.randomMsToken(),
    )
    val url = "$URL_PARTITION_V2?${DouyinSigner.signQuery(params)}"

    val response = http.get(url, headers = headers())
    val root = response.requireSuccess().let { parseObject(it.body, "partition") }
    val status = root["status_code"]?.jsonPrimitive?.intOrNull ?: -1
    if (status != 0) {
      throw PlatformProtocolException("抖音分区列表获取失败: status=$status", Platform.DOUYIN)
    }

    val wrapper = root["data"] as? JsonObject ?: return emptyList<Streamer>() to false
    val list = wrapper["data"] as? JsonArray ?: JsonArray(emptyList())
    val rooms = list.mapNotNull { parsePartitionItem(it) }
    val apiHasMore = wrapper["has_more"]?.jsonPrimitive?.booleanOrNull
    return rooms to (apiHasMore ?: (rooms.size >= PAGE_SIZE))
  }

  /** Maps one `partition/detail/room` entry, whose room object sits under `room`. */
  private fun parsePartitionItem(element: JsonElement): Streamer? {
    val envelope = element as? JsonObject ?: return null
    val webRid = envelope.str("web_rid")
      ?: (envelope["room"] as? JsonObject)?.str("id_str")
      ?: return null
    if (webRid.isBlank()) return null
    val room = envelope["room"] as? JsonObject
    val owner = room?.get("owner") as? JsonObject
    val stats = room?.get("stats") as? JsonObject
    val viewers = stats?.get("total_user")?.jsonPrimitive?.longOrNull
      ?: stats?.str("user_count_str")?.let { parseCount(it) }
      ?: 0
    return Streamer(
      platform = Platform.DOUYIN,
      roomId = webRid,
      streamerName = owner?.str("nickname").orEmpty().ifBlank { "抖音主播" },
      title = room?.str("title").orEmpty().ifBlank { "直播中" },
      coverUrl = firstImage(room?.get("cover")),
      avatarUrl = firstImage(owner?.get("avatar_thumb")),
      viewerCount = viewers,
      // The partition endpoint only returns rooms that are on air, so no status check is
      // needed here. Reading `room.status` was wrong: that field is not the live flag in
      // this payload, and it marked every card as 未开播.
      isLive = true,
      updatedAt = System.currentTimeMillis(),
    )
  }

  /**
   * One page of the anonymous recommendation feed.
   *
   * This is the endpoint that actually works without a logged-in, fingerprinted client —
   * verified live: an unsigned `webcast/feed` request returns ~300 KB of real rooms, while
   * `webcast/web/partition/detail/room/v2` answers HTTP 200 with a **zero-length body** for
   * every partition, signed or not. It ignores `partition`, so it cannot filter; it exists
   * as the fallback that keeps the Douyin tab from being permanently empty.
   */
  suspend fun feed(offset: Int, count: Int): Pair<List<Streamer>, Boolean> {
    val params = listOf(
      "aid" to "6383",
      "app_name" to "douyin_web",
      "live_id" to "1",
      "device_platform" to "web",
      "language" to "zh-CN",
      "enter_from" to "web_live",
      "cookie_enabled" to "true",
      "browser_language" to "zh-CN",
      "browser_name" to "Chrome",
      "browser_version" to DouyinSigner.BROWSER_VERSION,
      "count" to count.toString(),
      "offset" to offset.toString(),
    )
    val response = runCatching {
      http.get("$URL_FEED?${DouyinSigner.signQuery(params)}", headers = headers()).requireSuccess()
    }.getOrNull() ?: return emptyList<Streamer>() to false
    val root = runCatching { parseObject(response.body, "feed") }.getOrNull()
      ?: return emptyList<Streamer>() to false
    val list = root["data"] as? JsonArray ?: return emptyList<Streamer>() to false
    val rooms = list.mapNotNull { parseFeedItem(it) }
    // The feed caps `count` server-side (6 in practice) and ignores `offset`, so a short
    // page is the end rather than a signal to ask for more.
    return rooms to (rooms.size >= count)
  }

  /**
   * Maps one `webcast/feed` entry.
   *
   * The feed wraps every card in an envelope whose room object lives under `data`;
   * older responses put the room fields at the top level, so both shapes are accepted.
   */
  private fun parseFeedItem(element: JsonElement): Streamer? {
    val envelope = element as? JsonObject ?: return null
    val room = (envelope["data"] as? JsonObject)
      ?: (envelope["room"] as? JsonObject)
      ?: envelope
    val idText = room.str("id_str")
      ?: room["id"]?.jsonPrimitive?.longOrNull?.toString()
      ?: envelope.str("web_rid")
      ?: room.str("web_rid")
      ?: return null
    if (idText.isBlank() || idText == "0") return null
    val owner = room["owner"] as? JsonObject
    val status = room["status"]?.jsonPrimitive?.intOrNull
    val viewers = room["stats"]?.jsonObject?.get("total_user")?.jsonPrimitive?.longOrNull
      ?: room["user_count"]?.jsonPrimitive?.longOrNull
      ?: room.str("user_count_str")?.let { parseCount(it) }
      ?: 0
    return Streamer(
      platform = Platform.DOUYIN,
      roomId = idText,
      streamerName = owner?.str("nickname").orEmpty().ifBlank { "抖音主播" },
      title = room.str("title").orEmpty().ifBlank { "直播中" },
      coverUrl = firstImage(room["cover"]) ?: firstImage(room["dynamic_cover"]),
      avatarUrl = firstImage(owner?.get("avatar_thumb")),
      viewerCount = viewers,
      isLive = status == null || status == 2,
      updatedAt = System.currentTimeMillis(),
    )
  }

  private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonElement)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

  private fun firstImage(element: JsonElement?): String? {
    val obj = element as? JsonObject ?: return null
    return obj["url_list"]?.jsonArray?.firstOrNull()
      ?.jsonPrimitive?.contentOrNull
      ?.let(Texts::normalizeUrl)
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
    val url = "$URL_SEARCH?${DouyinSigner.signQuery(params)}"
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

  /**
   * Fetches the room page HTML.
   *
   * The danmaku handshake needs two values that only appear in the page: the numeric
   * `room.id_str` and a `user_unique_id`. Neither is returned by any of the JSON endpoints,
   * so the page is the only source. A failure here is not fatal — the caller falls back to
   * the API for the room id and to a random id for the visitor — hence an empty string.
   */
  suspend fun roomPageHtml(webRid: String): String = runCatching {
    http.get(
      "https://live.douyin.com/$webRid",
      headers = mapOf(
        "User-Agent" to DouyinSigner.USER_AGENT,
        "Referer" to "https://live.douyin.com/",
        "Cookie" to DouyinSigner.DEFAULT_COOKIE,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      ),
    ).requireSuccess().body
  }.getOrDefault("")

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
      "browser_version" to DouyinSigner.BROWSER_VERSION,
      "web_rid" to roomId,
    )
    val url = "$URL_ENTER?${DouyinSigner.signQuery(params)}"
    val response = http.get(url, headers = headers())

    // The enter endpoint rejects unknown clients with an empty 200 body, so a feed
    // lookup is the reliable fallback: it already carries the stream URL.
    val root = runCatching { parseObject(response.body, "enter") }.getOrNull()
    val data = root?.get("data")?.let { it as? JsonObject }
    val entered = data?.get("room")?.let { it as? JsonObject }
    val room = entered ?: findInFeed(roomId)
      ?: throw PlatformProtocolException(
        "抖音直播间信息获取失败，该直播间可能未开播",
        Platform.DOUYIN,
      )
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
    // The anonymous feed only exposes the flat `hls_pull_url` string, which is already
    // the highest quality the CDN will serve.
    streamUrl["hls_pull_url"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }?.let { url ->
      if (builder.none { it.url == url }) {
        builder += DouyinVariant(name = "原画", url = url, rank = 0)
      }
    }
    streamUrl["flv_pull_url_map"]?.let { it as? JsonObject }?.let { flv ->
      flv.entries.forEachIndexed { index, entry ->
        val url = entry.value.jsonPrimitive.contentOrNull ?: return@forEachIndexed
        if (url.isBlank() || builder.any { it.url == url }) return@forEachIndexed
        builder += DouyinVariant(
          name = qualityName(entry.key),
          url = url,
          rank = qualityRank(entry.key, index) + 10,
        )
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

  /**
   * Finds a room in `webcast/feed` by id.
   *
   * Douyin's `enter` endpoint only answers fully for a logged-in, fingerprinted client;
   * the feed response contains the same room object (including `stream_url`) and works
   * anonymously, so it doubles as the detail source.
   */
  private suspend fun findInFeed(roomId: String): JsonObject? {
    val params = listOf(
      "aid" to "6383",
      "app_name" to "douyin_web",
      "live_id" to "1",
      "device_platform" to "web",
      "language" to "zh-CN",
      "enter_from" to "web_live",
      "cookie_enabled" to "true",
      "browser_language" to "zh-CN",
      "browser_name" to "Chrome",
      "browser_version" to DouyinSigner.BROWSER_VERSION,
      "count" to "50",
      "offset" to "0",
    )
    val signedQuery = DouyinSigner.signQuery(params)
    val response = runCatching {
      http.get("$URL_FEED?$signedQuery", headers = headers()).requireSuccess()
    }.getOrNull() ?: return null
    val root = runCatching { parseObject(response.body, "feed") }.getOrNull() ?: return null
    val list = root["data"] as? JsonArray ?: return null
    return list.firstNotNullOfOrNull { element ->
      val envelope = element as? JsonObject ?: return@firstNotNullOfOrNull null
      val candidate = (envelope["data"] as? JsonObject) ?: envelope
      val id = candidate.str("id_str")
        ?: candidate["id"]?.jsonPrimitive?.longOrNull?.toString()
        ?: envelope.str("web_rid")
      if (id == roomId) candidate else null
    }
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

  /**
   * Request headers.
   *
   * The `Cookie` is not optional: Douyin answers the partition and feed endpoints with
   * HTTP 200 and an empty body when there is no cookie, so a cookieless request looks
   * exactly like an empty category.
   */
  private fun headers(): Map<String, String> = mapOf(
    "User-Agent" to DouyinSigner.USER_AGENT,
    "Referer" to "https://live.douyin.com/",
    "Cookie" to DouyinSigner.DEFAULT_COOKIE,
    "Accept" to "application/json, text/plain, */*",
    "Accept-Language" to "zh-CN,zh;q=0.9",
  )

  private fun parseObject(body: String, label: String): JsonObject = try {
    json.parseToJsonElement(body).jsonObject
  } catch (error: Exception) {
    throw PlatformProtocolException("抖音 $label 返回无法解析的 JSON", Platform.DOUYIN, error)
  }

  companion object {
    const val URL_FEED = "https://live.douyin.com/webcast/feed/"
    /** Partition-scoped list; requires an `a_bogus` signature. */
    const val URL_PARTITION_V2 =
      "https://live.douyin.com/webcast/web/partition/detail/room/v2/"
    const val URL_PARTITION = "https://live.douyin.com/webcast/web/partition/detail/room/"
    const val URL_SEARCH = "https://live.douyin.com/webcast/search/partition/room/"
    const val URL_ENTER = "https://live.douyin.com/webcast/room/web/enter/"
    const val PAGE_SIZE = 20
  }
}
