package com.livehub.platform.huya

import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpClients
import com.livehub.core.network.Texts
import com.livehub.platform.api.PlatformOfflineException
import com.livehub.platform.api.PlatformProtocolException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** One CDN stream entry scraped from the room page. */
data class HuyaStreamInfo(
  val cdnType: String,
  val flvUrl: String,
  val streamName: String,
  val suffix: String,
  val antiCode: HuyaSigner.AntiCode,
)

/** Everything extracted from `https://www.huya.com/{roomId}`. */
data class HuyaRoomInfo(
  val roomId: String,
  val title: String,
  val streamerName: String,
  val avatarUrl: String?,
  val coverUrl: String?,
  val viewerCount: Long,
  val isLive: Boolean,
  val categoryName: String?,
  val streams: List<HuyaStreamInfo>,
) {
  fun toStreamer(): Streamer = Streamer(
    platform = Platform.HUYA,
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

/** HTTP + parsing layer for Huya. */
class HuyaApi(private val http: HttpClient) {

  val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
  }

  // ----------------------------------------------------------------- categories

  /**
   * Huya publishes its category list as a JS array on the live site; the JSON endpoint
   * is used where available and falls back to the curated static list otherwise.
   */
  suspend fun categories(): List<Category> = HuyaCategories.all()

  // ---------------------------------------------------------------------- rooms

  suspend fun rooms(gid: String, page: Int, pageSize: Int): Pair<List<Streamer>, Boolean> {
    val url = "$URL_LIVE_LIST?iGid=$gid&iPageNo=$page&iPageSize=$pageSize"
    val response = http.get(url, headers = mapOf("User-Agent" to HttpClients.UA_SIMPLE))
    val root = response.requireSuccess().let { parseObject(it.body, "getLiveList") }
    val list = (root["vList"] as? JsonArray)
      ?: (root["data"]?.jsonObject?.get("vList") as? JsonArray)
      ?: JsonArray(emptyList())
    val rooms = list.mapNotNull { parseListRoom(it) }
    return rooms to (rooms.size >= pageSize)
  }

  // --------------------------------------------------------------------- search

  suspend fun search(keyword: String, page: Int): Pair<List<Streamer>, Boolean> {
    val start = (page - 1) * SEARCH_PAGE_SIZE
    val url = "$URL_SEARCH?m=Search&do=getSearchContent&q=${encode(keyword)}" +
      "&uid=0&v=1&typ=-5&livestate=0&rows=$SEARCH_PAGE_SIZE&start=$start"
    val response = http.get(
      url,
      headers = mapOf(
        "User-Agent" to HttpClients.UA_SIMPLE,
        "Referer" to "https://www.huya.com/search/",
        "Origin" to "https://www.huya.com",
        "Accept" to "*/*",
      ),
    )
    val root = response.requireSuccess().let { parseObject(it.body, "search") }
    val docs = root["response"]?.jsonObject?.get("1")?.jsonObject?.get("docs")?.jsonArray
      ?: JsonArray(emptyList())
    val rooms = docs.mapNotNull { parseSearchRoom(it) }
    return rooms to (rooms.size >= SEARCH_PAGE_SIZE)
  }

  // --------------------------------------------------------------------- detail

  /** Loads the room page and extracts the embedded `stream` JSON object. */
  suspend fun roomInfo(roomId: String): HuyaRoomInfo {
    val html = fetchRoomHtml(roomId)
    return parseRoomHtml(roomId, html)
  }

  private suspend fun fetchRoomHtml(roomId: String): String {
    val desktop = runCatching {
      http.get(
        "$URL_ROOM/$roomId",
        headers = mapOf(
          "User-Agent" to UA_DESKTOP,
          "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
          "Referer" to "https://www.huya.com/",
          "Accept-Language" to "zh-CN,zh;q=0.9,en-US;q=0.6,en;q=0.4",
          "Cookie" to "huya_ua=webh5&0.1.0&websocket",
        ),
      ).requireSuccess().body
    }.getOrNull()
    if (desktop != null && desktop.contains("gameStreamInfoList")) return desktop

    val mobile = runCatching {
      http.get(
        "$URL_ROOM/$roomId",
        headers = mapOf(
          "User-Agent" to UA_MOBILE,
          "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
          "Referer" to "https://m.huya.com/",
          "Accept-Language" to "zh-CN,zh;q=0.9",
          "Cookie" to "huya_ua=webh5&0.1.0&websocket",
        ),
      ).requireSuccess().body
    }.getOrNull()
    return mobile ?: desktop ?: throw PlatformProtocolException("虎牙房间页加载失败", Platform.HUYA)
  }

  internal fun parseRoomHtml(roomId: String, html: String): HuyaRoomInfo {
    if (html.isBlank()) throw PlatformProtocolException("虎牙房间页为空", Platform.HUYA)

    val streamJson = extractStreamJson(html)
    val streams = streamJson?.let { parseStreamInfos(it) }.orEmpty()

    val title = firstGroup(TITLE_REGEX, html)
      ?: firstGroup(OG_TITLE_REGEX, html)
      ?: ""
    val streamerName = firstGroup(NICK_REGEX, html)
      ?: firstGroup(AVATAR_NAME_REGEX, html)
      ?: "虎牙主播"
    val avatar = firstGroup(AVATAR_REGEX, html)?.let { Texts.normalizeUrl(it) }
    val cover = firstGroup(SCREENSHOT_REGEX, html)?.let { Texts.normalizeUrl(it) }
    val viewers = firstGroup(ACTIVITY_REGEX, html)?.toLongOrNull() ?: 0L
    val categoryName = firstGroup(CATEGORY_REGEX, html)

    return HuyaRoomInfo(
      roomId = roomId,
      title = Texts.decodeHtmlEntities(title).ifBlank { "直播中" },
      streamerName = Texts.decodeHtmlEntities(streamerName).ifBlank { "虎牙主播" },
      avatarUrl = avatar,
      coverUrl = cover,
      viewerCount = viewers,
      isLive = streams.isNotEmpty(),
      categoryName = categoryName,
      streams = streams,
    )
  }

  /** Extracts the `stream: {...}` object terminated by `"iWebDefaultBitRate"`. */
  internal fun extractStreamJson(html: String): JsonObject? {
    val match = STREAM_REGEX.find(html) ?: return null
    val captured = match.groupValues[1] + "}"
    return runCatching { json.parseToJsonElement(captured).jsonObject }.getOrNull()
  }

  internal fun parseStreamInfos(root: JsonObject): List<HuyaStreamInfo> {
    val data = root["data"] as? JsonArray ?: return emptyList()
    val first = data.firstOrNull() as? JsonObject ?: return emptyList()
    val infos = first["gameStreamInfoList"] as? JsonArray ?: return emptyList()
    return infos.mapNotNull { element ->
      val obj = element as? JsonObject ?: return@mapNotNull null
      val cdnType = obj.str("sCdnType") ?: return@mapNotNull null
      val flvUrl = obj.str("sFlvUrl") ?: return@mapNotNull null
      val streamName = obj.str("sStreamName") ?: return@mapNotNull null
      val suffix = obj.str("sFlvUrlSuffix") ?: return@mapNotNull null
      val anti = obj.str("sFlvAntiCode")?.let(HuyaSigner::parseAntiCode) ?: return@mapNotNull null
      HuyaStreamInfo(
        cdnType = cdnType,
        flvUrl = flvUrl,
        streamName = streamName,
        suffix = suffix,
        antiCode = anti,
      )
    }
  }

  /** Resolves the playable variants for a room, ordered by CDN preference. */
  suspend fun playback(roomId: String): List<StreamVariant> {
    val info = roomInfo(roomId)
    if (info.streams.isEmpty()) {
      throw PlatformOfflineException("主播未开播", Platform.HUYA)
    }
    return info.streams
      .sortedBy { HuyaSigner.cdnRank(it.cdnType) }
      .map { stream ->
        val url = HuyaSigner.buildStreamUrl(
          flvUrl = stream.flvUrl,
          streamName = stream.streamName,
          suffix = stream.suffix,
          antiCode = stream.antiCode,
        )
        StreamVariant(
          qualityLabel = "原画",
          url = HuyaSigner.postProcess(url, stream.cdnType),
          lineName = stream.cdnType,
          isHls = stream.suffix.contains("m3u8", ignoreCase = true),
          qualityRank = HuyaSigner.cdnRank(stream.cdnType),
          qualityCode = HuyaSigner.cdnRank(stream.cdnType),
        )
      }
      .distinctBy { it.url }
  }

  // -------------------------------------------------------------------- helpers

  private fun parseListRoom(element: JsonElement): Streamer? {
    val obj = element as? JsonObject ?: return null
    val roomId = obj["lProfileRoom"]?.jsonPrimitive?.longOrNull?.toString()
      ?: obj.str("lProfileRoom")
      ?: return null
    if (roomId.isBlank() || roomId == "0") return null
    return Streamer(
      platform = Platform.HUYA,
      roomId = roomId,
      streamerName = obj.str("sNick").orEmpty().ifBlank { "虎牙主播" },
      title = obj.str("sIntroduction").orEmpty().ifBlank { "直播中" },
      coverUrl = obj.str("sScreenshot")?.let { Texts.normalizeUrl(it) },
      avatarUrl = obj.str("sAvatar180")?.let { Texts.normalizeUrl(it) },
      viewerCount = obj["lUserCount"]?.jsonPrimitive?.longOrNull ?: 0,
      isLive = true,
      updatedAt = System.currentTimeMillis(),
    )
  }

  private fun parseSearchRoom(element: JsonElement): Streamer? {
    val obj = element as? JsonObject ?: return null
    val roomId = obj["room_id"]?.jsonPrimitive?.longOrNull?.toString()
      ?: obj.str("room_id")
      ?: return null
    if (roomId.isBlank() || roomId == "0") return null
    val liveFlag = obj["gameLiveOn"]?.jsonPrimitive?.booleanOrNull
      ?: obj.str("gameLiveOn")?.equals("true", ignoreCase = true)
      ?: false
    return Streamer(
      platform = Platform.HUYA,
      roomId = roomId,
      streamerName = obj.str("game_nick").orEmpty().ifBlank { "虎牙主播" },
      title = obj.str("live_intro").orEmpty().ifBlank { "暂无标题" },
      avatarUrl = obj.str("game_avatarUrl180")?.let { Texts.normalizeUrl(it) },
      coverUrl = null,
      viewerCount = 0,
      isLive = liveFlag,
      updatedAt = System.currentTimeMillis(),
    )
  }

  private fun parseObject(body: String, label: String): JsonObject = try {
    json.parseToJsonElement(body).jsonObject
  } catch (error: Exception) {
    throw PlatformProtocolException("虎牙 $label 返回无法解析的 JSON", Platform.HUYA, error)
  }

  private fun firstGroup(regex: Regex, source: String): String? =
    regex.find(source)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }

  private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonElement)?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

  companion object {
    const val URL_LIVE_LIST = "https://live.huya.com/liveHttpUI/getLiveList"
    const val URL_SEARCH = "https://search.cdn.huya.com/"
    const val URL_ROOM = "https://www.huya.com"
    const val SEARCH_PAGE_SIZE = 20

    const val UA_DESKTOP =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    const val UA_MOBILE =
      "Mozilla/5.0 (iPhone; CPU iPhone OS 17_4 like Mac OS X) AppleWebKit/605.1.15 " +
        "(KHTML, like Gecko) Version/17.4 Mobile/15E148 Safari/604.1"

    val STREAM_REGEX = Regex("""(?s)stream:\s*(\{"data".*?),"iWebDefaultBitRate"""")
    private val TITLE_REGEX = Regex("""<title>([^<]*)</title>""")
    private val OG_TITLE_REGEX = Regex("""<meta\s+property="og:title"\s+content="([^"]*)"""")
    private val NICK_REGEX = Regex(""""nick"\s*:\s*"([^"]*)"""")
    private val AVATAR_NAME_REGEX = Regex(""""avatarName"\s*:\s*"([^"]*)"""")
    private val AVATAR_REGEX = Regex(""""avatar180"\s*:\s*"([^"]*)"""")
    private val SCREENSHOT_REGEX = Regex(""""screenshot"\s*:\s*"([^"]*)"""")
    private val ACTIVITY_REGEX = Regex(""""(?:activityCount|totalCount)"\s*:\s*"?(\d+)"""")
    private val CATEGORY_REGEX = Regex(""""gameFullName"\s*:\s*"([^"]*)"""")

    fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
  }
}
