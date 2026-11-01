package com.livehub.platform.bilibili

import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.PlaybackSource
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
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

/** A `(parentAreaId, areaId)` pair identifying a Bilibili live section. */
data class BilibiliArea(val parentId: String, val areaId: String) {
  val encoded: String get() = "$parentId:$areaId"

  companion object {
    val HOT = BilibiliArea("0", "0")

    fun decode(raw: String): BilibiliArea {
      val parts = raw.split(':', limit = 2)
      return if (parts.size == 2) BilibiliArea(parts[0], parts[1]) else HOT
    }
  }
}

/** HTTP + parsing layer for Bilibili live. */
class BilibiliApi(
  private val http: HttpClient,
  private val cookieStore: BilibiliCookieStore,
) {
  val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
  }

  @Volatile private var cachedAccessId: String? = null
  @Volatile private var accessIdFetchedAt: Long = 0

  @Volatile private var cachedNav: NavKeys? = null
  @Volatile private var navFetchedAt: Long = 0

  data class NavKeys(val mixinKey: String, val mid: Long)

  // ------------------------------------------------------------------ categories

  suspend fun categories(): List<Category> = BilibiliCategories.all()

  // ---------------------------------------------------------------------- rooms

  suspend fun rooms(area: BilibiliArea, page: Int, pageSize: Int): Pair<List<Streamer>, Boolean> {
    return if (area == BilibiliArea.HOT) {
      hotRooms(page = page, pageSize = pageSize)
    } else {
      areaRooms(area = area, page = page)
    }
  }

  private suspend fun areaRooms(area: BilibiliArea, page: Int): Pair<List<Streamer>, Boolean> {
    val wts = System.currentTimeMillis() / 1000
    val accessId = accessId()
    val ordered = listOf(
      "area_id" to area.areaId,
      "page" to page.toString(),
      "parent_area_id" to area.parentId,
      "platform" to "web",
      "sort_type" to "",
      "vajra_business_key" to "",
      "w_webid" to accessId,
      "web_location" to "444.253",
    )
    val signed = BilibiliSigner.signListQuery(ordered, wts)
    val response = http.get(
      "$URL_SECOND_LIST?${signed.query}",
      headers = mapOf(
        "User-Agent" to UA_MOBILE,
        "Referer" to "https://www.bilibili.com/",
        "Cookie" to "buvid3=i;",
      ),
    )
    val root = response.requireSuccess().let { parseObject(it.body, "second/getList") }
    ensureSuccess(root, "B站分区列表获取失败")
    val list = root["data"]?.jsonObject?.get("list")?.jsonArray ?: JsonArray(emptyList())
    val rooms = list.mapNotNull { parseRoom(it) }
    return rooms to (rooms.size >= DEFAULT_PAGE_SIZE)
  }

  private suspend fun hotRooms(page: Int, pageSize: Int): Pair<List<Streamer>, Boolean> {
    val url = "$URL_INDEX_LIST?platform=web&parent_area_id=0&area_id=0&page=$page&page_size=$pageSize"
    val response = http.get(
      url,
      headers = mapOf(
        "User-Agent" to HttpClients.UA_SIMPLE,
        "Referer" to "https://live.bilibili.com/",
      ),
    )
    val root = response.requireSuccess().let { parseObject(it.body, "index/getList") }
    ensureSuccess(root, "B站热门列表获取失败")
    val modules = root["data"]?.jsonObject?.get("room_list")?.jsonArray ?: JsonArray(emptyList())
    val rooms = modules.flatMap { module ->
      val list = (module as? JsonObject)?.get("list")?.jsonArray ?: JsonArray(emptyList())
      list.mapNotNull { parseRoom(it) }
    }
    return rooms to (rooms.size >= pageSize)
  }

  // --------------------------------------------------------------------- search

  suspend fun search(keyword: String, page: Int): Pair<List<Streamer>, Boolean> {
    val cookie = ensureBuvidCookie()
    val url = "$URL_SEARCH?context=&search_type=live&cover_type=user_cover&order=" +
      "&keyword=${encode(keyword)}&category_id=&__refresh__=&_extra=&highlight=0" +
      "&single_column=0&page=$page"
    val response = http.get(
      url,
      headers = buildMap {
        put("User-Agent", UA_CHROME138)
        put("Referer", "https://live.bilibili.com/")
        if (cookie.isNotBlank()) put("Cookie", cookie)
      },
    )
    val root = response.requireSuccess().let { parseObject(it.body, "search/type") }
    val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
    if (code != 0) return emptyList<Streamer>() to false
    val users = root["data"]?.jsonObject?.get("result")?.jsonObject?.get("live_user")?.jsonArray
      ?: JsonArray(emptyList())
    val rooms = users.mapNotNull { parseSearchRoom(it) }
    return rooms to (rooms.size >= DEFAULT_PAGE_SIZE)
  }

  // --------------------------------------------------------------------- detail

  suspend fun roomInfo(roomId: String): BilibiliRoomInfo {
    val url = "$URL_ROOM_INFO?room_id=$roomId"
    val response = http.get(
      url,
      headers = mapOf(
        "User-Agent" to HttpClients.UA_SIMPLE,
        "Referer" to "https://live.bilibili.com/",
      ),
    )
    val root = response.requireSuccess().let { parseObject(it.body, "roomInfo") }
    ensureSuccess(root, "B站房间信息获取失败")
    val data = root["data"]?.jsonObject ?: throw PlatformProtocolException("B站房间数据为空", Platform.BILIBILI)
    return BilibiliRoomInfo(
      roomId = roomId,
      title = data.str("title").orEmpty(),
      streamerName = data.str("uname") ?: data["anchor_info"]?.jsonObject?.get("base_info")
        ?.jsonObject?.str("uname").orEmpty(),
      coverUrl = data.str("user_cover")?.let(Texts::normalizeUrl),
      avatarUrl = data["anchor_info"]?.jsonObject?.get("base_info")?.jsonObject
        ?.str("face")?.let(Texts::normalizeUrl),
      viewerCount = data["watched_show"]?.jsonObject?.get("num")
        ?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0,
      isLive = (data["live_status"]?.jsonPrimitive?.intOrNull ?: 0) == 1,
    )
  }

  // ------------------------------------------------------------------- playback

  suspend fun playback(roomId: String, preferredQn: Int?): PlaybackSource {
    val qn = preferredQn ?: DEFAULT_QN
    val cookie = cookieStore.header()
    val url = "$URL_PLAY_INFO?room_id=$roomId&protocol=0,1&format=0,1,2&codec=0,1&qn=$qn&platform=web"
    val response = http.get(
      url,
      headers = buildMap {
        put("User-Agent", HttpClients.UA_SIMPLE)
        put("Referer", "https://live.bilibili.com/")
        if (cookie.isNotBlank()) put("Cookie", cookie)
      },
    )
    val root = response.requireSuccess().let { parseObject(it.body, "getRoomPlayInfo") }
    val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
    if (code != 0) {
      throw PlatformProtocolException("B站播放地址获取失败: code=$code", Platform.BILIBILI)
    }
    val playurl = root["data"]?.jsonObject?.get("playurl_info")?.jsonObject?.get("playurl")
      ?: throw PlatformProtocolException("B站播放数据为空", Platform.BILIBILI)
    val streams = playurl.jsonObject["stream"]?.jsonArray ?: JsonArray(emptyList())

    val variants = mutableListOf<StreamVariant>()
    streams.forEach { streamElement ->
      val stream = streamElement as? JsonObject ?: return@forEach
      val formats = stream["format"]?.jsonArray ?: JsonArray(emptyList())
      formats.forEach { formatElement ->
        val format = formatElement as? JsonObject ?: return@forEach
        val codecs = format["codec"]?.jsonArray ?: JsonArray(emptyList())
        codecs.forEach { codecElement ->
          val codec = codecElement as? JsonObject ?: return@forEach
          val baseUrl = codec.str("base_url") ?: return@forEach
          val urlInfo = codec["url_info"]?.jsonArray?.firstOrNull() as? JsonObject
          val host = urlInfo?.str("host") ?: return@forEach
          val extra = urlInfo.str("extra").orEmpty().trimStart('?', '&')
          val full = buildPlayUrl(host, baseUrl, extra)
          val isHls = baseUrl.contains(".m3u8", ignoreCase = true)
          variants += StreamVariant(
            qualityLabel = BilibiliQuality.labelForQn(codec.qualityQn()),
            url = full,
            lineName = host.removePrefix("https://").removePrefix("http://").substringBefore('/'),
            isHls = isHls,
            qualityRank = BilibiliQuality.rankOf(baseUrl),
            qualityCode = codec.qualityQn(),
          )
        }
      }
    }

    if (variants.isEmpty()) {
      throw PlatformProtocolException("B站未解析到播放地址", Platform.BILIBILI)
    }
    val ordered = variants.sortedWith(compareBy({ it.qualityRank }, { it.qualityLabel }))
    val room = Streamer(
      platform = Platform.BILIBILI,
      roomId = roomId,
      streamerName = "",
      title = "",
      isLive = true,
      updatedAt = System.currentTimeMillis(),
    )
    return PlaybackSource(room = room, variants = ordered, defaultVariant = ordered.first())
  }

  // ------------------------------------------------------------------- danmaku

  suspend fun danmakuInfo(roomId: String): DanmakuInfo {
    val nav = navKeys()
    val wts = System.currentTimeMillis() / 1000
    val signed = BilibiliSigner.signWbi(
      params = mapOf(
        "id" to roomId,
        "type" to "0",
        "web_location" to "444.8",
      ),
      mixinKey = nav.mixinKey,
      wts = wts,
    )
    val cookie = cookieStore.header()
    val response = http.get(
      "$URL_DANMU_INFO?${signed.query}",
      headers = buildMap {
        put("User-Agent", UA_CHROME122)
        put("Referer", "https://live.bilibili.com/")
        if (cookie.isNotBlank()) put("Cookie", cookie)
      },
    )
    val root = response.requireSuccess().let { parseObject(it.body, "getDanmuInfo") }
    val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
    if (code != 0) {
      throw PlatformProtocolException("B站弹幕参数获取失败(code=$code)", Platform.BILIBILI)
    }
    val data = root["data"]?.jsonObject
      ?: throw PlatformProtocolException("B站弹幕数据为空", Platform.BILIBILI)
    val token = data.str("token")?.takeIf { it.isNotBlank() }
      ?: throw PlatformProtocolException("B站弹幕 token 为空", Platform.BILIBILI)
    val realRoomId = data.str("roomid") ?: roomId
    val hosts = data["host_list"]?.jsonArray.orEmpty()
    val endpoints = hosts.mapNotNull { element ->
      val obj = element as? JsonObject ?: return@mapNotNull null
      val host = obj.str("host") ?: return@mapNotNull null
      val port = obj["wss_port"]?.jsonPrimitive?.intOrNull ?: 443
      BilibiliDanmakuProtocol.Endpoint(host, port)
    }
    return DanmakuInfo(
      token = token,
      roomId = realRoomId,
      uid = nav.mid,
      endpoints = BilibiliDanmakuProtocol.orderEndpoints(endpoints),
    )
  }

  data class DanmakuInfo(
    val token: String,
    val roomId: String,
    val uid: Long,
    val endpoints: List<BilibiliDanmakuProtocol.Endpoint>,
  )

  /** Fetches (and caches for six hours) the WBI mixin key and the logged-in mid. */
  suspend fun navKeys(forceRefresh: Boolean = false): NavKeys {
    val cached = cachedNav
    val fresh = System.currentTimeMillis() - navFetchedAt < NAV_TTL_MILLIS
    if (!forceRefresh && cached != null && fresh) return cached

    val cookie = cookieStore.header()
    val response = http.get(
      URL_NAV,
      headers = buildMap {
        put("User-Agent", UA_FIREFOX)
        put("Referer", "https://www.bilibili.com/")
        if (cookie.isNotBlank()) put("Cookie", cookie)
      },
    )
    val root = runCatching { parseObject(response.body, "nav") }.getOrNull()
    val data = root?.get("data")?.jsonObject
    val imgKey = BilibiliSigner.keyFromUrl(data?.get("wbi_img")?.jsonObject?.str("img_url").orEmpty())
    val subKey = BilibiliSigner.keyFromUrl(data?.get("wbi_img")?.jsonObject?.str("sub_url").orEmpty())
    val mid = data?.get("mid")?.jsonPrimitive?.longOrNull ?: 0
    val keys = if (imgKey.isBlank() || subKey.isBlank()) {
      NavKeys(mixinKey = BilibiliSigner.mixinKey(FALLBACK_IMG_KEY, FALLBACK_SUB_KEY), mid = mid)
    } else {
      NavKeys(mixinKey = BilibiliSigner.mixinKey(imgKey, subKey), mid = mid)
    }
    cachedNav = keys
    navFetchedAt = System.currentTimeMillis()
    return keys
  }

  // -------------------------------------------------------------------- helpers

  /** Scrapes the `access_id` used as `w_webid`; cached for 30 minutes. */
  suspend fun accessId(): String {
    val cached = cachedAccessId
    if (cached != null && System.currentTimeMillis() - accessIdFetchedAt < ACCESS_ID_TTL_MILLIS) {
      return cached
    }
    val html = runCatching {
      http.get(
        "https://live.bilibili.com/lol",
        headers = mapOf(
          "User-Agent" to UA_MOBILE,
          "Referer" to "https://www.bilibili.com/",
        ),
      ).requireSuccess().body
    }.getOrNull().orEmpty()
    val id = ACCESS_ID_REGEX.find(html)?.groupValues?.getOrNull(1)
    if (!id.isNullOrBlank()) {
      cachedAccessId = id
      accessIdFetchedAt = System.currentTimeMillis()
      return id
    }
    // A stale value keeps the list endpoint working even when the page changes.
    return cached ?: FALLBACK_ACCESS_ID
  }

  /**
   * Ensures the cookie jar holds `buvid3`/`buvid4`; the search endpoint rejects
   * anonymous requests without them.
   */
  suspend fun ensureBuvidCookie(): String {
    val current = cookieStore.header()
    if (current.contains("buvid3=") && current.contains("buvid4=")) return current
    val response = runCatching {
      http.get(
        "$URL_FINGER_SPI",
        headers = buildMap {
          put("User-Agent", UA_CHROME138)
          put("Referer", "https://live.bilibili.com/")
          if (current.isNotBlank()) put("Cookie", current)
        },
      )
    }.getOrNull() ?: return current
    val root = runCatching { parseObject(response.body, "finger/spi") }.getOrNull() ?: return current
    val data = root["data"]?.jsonObject ?: return current
    val b3 = data.str("b_3")
    val b4 = data.str("b_4")
    val merged = buildMap {
      current.split(';').map { it.trim() }.filter { it.contains('=') }.forEach {
        put(it.substringBefore('='), it.substringAfter('='))
      }
      if (!b3.isNullOrBlank()) put("buvid3", b3)
      if (!b4.isNullOrBlank()) put("buvid4", b4)
    }
    val header = merged.entries.joinToString("; ") { "${it.key}=${it.value}" }
    cookieStore.save(header)
    return header
  }

  private fun buildPlayUrl(host: String, baseUrl: String, extra: String): String {
    val base = host.trimEnd('/') + baseUrl
    if (extra.isEmpty()) return base
    val joiner = when {
      base.endsWith("?") || base.endsWith("&") -> ""
      base.contains("?") -> "&"
      else -> "?"
    }
    return base + joiner + extra
  }

  /**
   * Maps one room object from either list endpoint.
   *
   * The two endpoints use **different** live flags, which a captured hot-tab response
   * made obvious: `second/getList` sends `live_status` (int, 1 = live) while the hot tab
   * sends `status` (boolean) and no `live_status` at all. Reading only `live_status`
   * meant every hot-tab room fell through the filter, and a future `status = false`
   * room would have been shown as live.
   */
  private fun parseRoom(element: JsonElement): Streamer? {
    val obj = element as? JsonObject ?: return null
    val roomId = obj.str("roomid") ?: obj.str("room_id") ?: obj.str("roomId") ?: return null

    val liveStatus = obj["live_status"]?.jsonPrimitive?.intOrNull
      ?: obj["liveStatus"]?.jsonPrimitive?.intOrNull
    val booleanStatus = obj["status"]?.jsonPrimitive?.booleanOrNull

    val isLive = when {
      liveStatus != null -> liveStatus == 1
      booleanStatus != null -> booleanStatus
      // Neither flag present: assume live rather than hiding the whole list.
      else -> true
    }
    if (!isLive) return null

    val viewers = obj["watched_show"]?.jsonObject?.get("num")?.jsonPrimitive?.contentOrNull
      ?.toLongOrNull()
      ?: obj["online"]?.jsonPrimitive?.longOrNull
      ?: 0
    return Streamer(
      platform = Platform.BILIBILI,
      roomId = roomId,
      streamerName = Texts.decodeHtmlEntities(obj.str("uname").orEmpty()).trim().ifBlank { "B站主播" },
      title = Texts.decodeHtmlEntities(obj.str("title").orEmpty()).trim().ifBlank { "直播中" },
      coverUrl = (obj.str("user_cover") ?: obj.str("cover") ?: obj.str("keyframe"))
        ?.let(Texts::normalizeUrl),
      avatarUrl = obj.str("face")?.let(Texts::normalizeUrl),
      viewerCount = viewers,
      isLive = true,
      updatedAt = System.currentTimeMillis(),
    )
  }

  private fun parseSearchRoom(element: JsonElement): Streamer? {
    val obj = element as? JsonObject ?: return null
    val roomId = obj.str("roomid") ?: return null
    val liveStatus = obj["live_status"]?.jsonPrimitive?.intOrNull ?: 1
    return Streamer(
      platform = Platform.BILIBILI,
      roomId = roomId,
      streamerName = Texts.stripHighlightTags(Texts.decodeHtmlEntities(obj.str("uname").orEmpty()))
        .trim().ifBlank { "B站主播" },
      title = Texts.stripHighlightTags(Texts.decodeHtmlEntities(obj.str("title").orEmpty()))
        .trim().ifBlank { "暂无标题" },
      coverUrl = obj.str("cover")?.let(::normalizeSearchImage),
      avatarUrl = obj.str("uface")?.let(::normalizeSearchImage),
      viewerCount = obj["online"]?.jsonPrimitive?.longOrNull ?: 0,
      isLive = liveStatus == 1,
      updatedAt = System.currentTimeMillis(),
    )
  }

  private fun normalizeSearchImage(raw: String): String {
    val absolute = if (raw.startsWith("http")) raw else "https:$raw"
    return if (absolute.contains('@')) absolute else "$absolute@400w.jpg"
  }

  private fun JsonObject.str(key: String): String? =
    (this[key] as? JsonElement)?.jsonPrimitive?.contentOrNull

  private fun JsonObject.qualityQn(): Int =
    this["current_qn"]?.jsonPrimitive?.intOrNull
      ?: this["qn"]?.jsonPrimitive?.intOrNull
      ?: 0

  private fun ensureSuccess(root: JsonObject, message: String) {
    val code = root["code"]?.jsonPrimitive?.intOrNull ?: 0
    if (code != 0) {
      val detail = root.str("message") ?: root.str("msg").orEmpty()
      throw PlatformProtocolException("$message: $code $detail", Platform.BILIBILI)
    }
  }

  private fun parseObject(body: String, label: String): JsonObject = try {
    json.parseToJsonElement(body).jsonObject
  } catch (error: Exception) {
    throw PlatformProtocolException("B站 $label 返回无法解析的 JSON", Platform.BILIBILI, error)
  }

  companion object {
    const val URL_SECOND_LIST = "https://api.live.bilibili.com/xlive/web-interface/v1/second/getList"
    const val URL_INDEX_LIST = "https://api.live.bilibili.com/xlive/web-interface/v1/index/getList"
    const val URL_SEARCH = "https://api.bilibili.com/x/web-interface/search/type"
    const val URL_ROOM_INFO = "https://api.live.bilibili.com/room/v1/Room/get_info"
    const val URL_PLAY_INFO = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo"
    const val URL_DANMU_INFO = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo"
    const val URL_NAV = "https://api.bilibili.com/x/web-interface/nav"
    const val URL_FINGER_SPI = "https://api.bilibili.com/x/frontend/finger/spi"

    const val DEFAULT_QN = 10000
    const val DEFAULT_PAGE_SIZE = 20
    const val NAV_TTL_MILLIS = 6L * 60 * 60 * 1000
    const val ACCESS_ID_TTL_MILLIS = 30L * 60 * 1000

    // Stable fallbacks: used only when the live endpoints are unreachable so the
    // feature degrades instead of failing outright.
    const val FALLBACK_ACCESS_ID = "1c6c1b4b8d1f4c0c9a3f5cf1e2a70b3c"
    const val FALLBACK_IMG_KEY = "7cd084941338484aae1ad9425b84077c"
    const val FALLBACK_SUB_KEY = "4932caff0ff746eab6f01bf08b70ac45"

    const val UA_MOBILE =
      "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    const val UA_CHROME122 =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    const val UA_CHROME138 =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
        "(KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"
    const val UA_FIREFOX =
      "Mozilla/5.0 (X11; Linux x86_64; rv:138.0) Gecko/20100101 Firefox/138.0"

    val ACCESS_ID_REGEX = Regex(""""access_id"\s*:\s*"([^"]+)"""")

    fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
  }
}

/** Room details resolved from `room/v1/Room/get_info`. */
data class BilibiliRoomInfo(
  val roomId: String,
  val title: String,
  val streamerName: String,
  val coverUrl: String?,
  val avatarUrl: String?,
  val viewerCount: Long,
  val isLive: Boolean,
)
