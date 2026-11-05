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
import kotlinx.serialization.json.intOrNull
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

/**
 * A resolved room: the page that was parsed, the variant built from it, and the CDN lines
 * that variant could have come from.
 *
 * The page is returned rather than thrown away because the caller needs the room metadata
 * as well, and a room page costs a full HTML download. See [HuyaApi.playback].
 */
data class HuyaPlayback(
  val room: HuyaRoomInfo,
  val variants: List<StreamVariant>,
  /** Distinct CDN hosts, best first, as the line selector shows them. */
  val lines: List<String> = emptyList(),
  /** The line [variants] was built for. */
  val selectedLine: String? = null,
)

/** One page of search results plus the `start` the next request must use. */
data class HuyaSearchPage(
  val rooms: List<Streamer>,
  val nextStart: Int?,
)

/** HTTP + parsing layer for Huya. */
class HuyaApi(private val http: HttpClient) {

  val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
  }

  // ----------------------------------------------------------------- categories

  /**
   * No network source for Huya's category tree.
   *
   * Every public endpoint that could serve it is gone: `liveHttpUI/getLiveCateList` and
   * `getCateList` answer HTTP 500, and the category page only carries `data-gid` attributes
   * describing page state (46 of them, including `0`), not the two-level catalogue. The
   * adapter therefore always uses the bundled `assets/categories/huya_categories.json`.
   *
   * That table is worth keeping: sampled against `getLiveList`, 15 of 20 gids returned
   * rooms, and the 5 empty ones are niche game categories nobody was streaming at the time
   * rather than retired ids. Returns an empty list so a missing asset degrades to "no
   * categories" instead of inventing a wrong tree.
   */
  suspend fun categories(): List<Category> = emptyList()

  // ---------------------------------------------------------------------- rooms

  /**
   * One page of a category.
   *
   * `iPageSize` is **ignored** by the server: verified live, requesting 10, 20, 30 or 60 all
   * return 120 rooms and report `iPageSize: 120`. Pagination therefore has to come from the
   * metadata the response actually carries — `iPageNo`, `iTotal` and `iTotalPage` — instead
   * of guessing from the row count. The previous `rooms.size >= pageSize` test was always
   * true for the first five pages and only stopped because page 6 happens to be empty.
   *
   * @return the page's rooms and whether another page exists.
   */
  suspend fun rooms(gid: String, page: Int, pageSize: Int): Pair<List<Streamer>, Boolean> {
    val url = "$URL_LIVE_LIST?iGid=$gid&iPageNo=$page&iPageSize=$pageSize"
    val response = http.get(url, headers = mapOf("User-Agent" to HttpClients.UA_SIMPLE))
    val root = response.requireSuccess().let { parseObject(it.body, "getLiveList") }
    val list = (root["vList"] as? JsonArray)
      ?: (root["data"]?.jsonObject?.get("vList") as? JsonArray)
      ?: JsonArray(emptyList())
    val rooms = list.mapNotNull { parseListRoom(it) }
    return rooms to hasMorePages(root, rooms.size, page)
  }

  /**
   * Decides whether another page exists, from the metadata in [root].
   *
   * Extracted so the rule is testable without a network round trip, and documented here
   * because getting it wrong is invisible: the previous implementation compared the row
   * count against the requested `pageSize`, which Huya ignores.
   *
   * `iTotalPage` alone is the page count, and it has to be trusted even when `iTotal` is 0:
   * the whole-site feed (`iGid=0`, huya.com/l) reports `iTotal: 0` with `iTotalPage: 73` —
   * measured, and page 73 really does answer ~90 rooms while page 74 answers none and
   * collapses `iTotalPage` back to 1. Requiring `iTotal > 0` alongside it made that feed
   * stop after the first 120 rooms.
   */
  internal fun hasMorePages(root: JsonObject, roomCount: Int, requestedPage: Int): Boolean {
    if (roomCount == 0) return false
    val total = root.long("iTotal") ?: -1
    val totalPages = root.int("iTotalPage") ?: -1
    val reportedPage = root.int("iPageNo") ?: requestedPage
    return when {
      totalPages > 0 -> reportedPage < totalPages
      // Fall back to the total when the page count is missing; never to the row count,
      // which is a fixed 120 regardless of what was asked for.
      total > 0 -> reportedPage.toLong() * roomCount < total
      else -> false
    }
  }

  /** Reads an int field, tolerating both numeric and string encodings. */
  private fun JsonObject.int(key: String): Int? =
    (this[key] as? JsonElement)?.jsonPrimitive?.let { it.intOrNull ?: it.contentOrNull?.toIntOrNull() }

  private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonElement)?.jsonPrimitive?.let { it.longOrNull ?: it.contentOrNull?.toLongOrNull() }

  // --------------------------------------------------------------------- search

  /**
   * One page of anchor search results.
   *
   * `start` is the offset the server's own `docs` window begins at, not a page number:
   * verified live, the endpoint answers **20** documents for `start=0` but **40** for any
   * `start > 0`, whatever `rows` says (10, 20 and 30 all came back as 40). Counting rows
   * against a fixed page size therefore cannot decide "is there another page" — the
   * previous `rooms.size >= SEARCH_PAGE_SIZE` test was true for every page and only ended
   * when the server returned nothing.
   *
   * The response carries `numFound` and echoes `start`, which give the exact rule:
   * announce another page while `start + docs.size < numFound`. @return the page's rooms
   * and the `start` to ask for next, or `null` at the end.
   */
  suspend fun search(keyword: String, start: Int): HuyaSearchPage {
    val safeStart = start.coerceAtLeast(0)
    val url = "$URL_SEARCH?m=Search&do=getSearchContent&q=${encode(keyword)}" +
      "&uid=0&v=1&typ=-5&livestate=0&rows=$SEARCH_PAGE_SIZE&start=$safeStart"
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
    val bucket = root["response"]?.jsonObject?.get("1")?.jsonObject
    val docs = bucket?.get("docs")?.jsonArray ?: JsonArray(emptyList())
    val rooms = docs.mapNotNull { parseSearchRoom(it) }
    return HuyaSearchPage(
      rooms = rooms,
      nextStart = nextSearchStart(bucket, safeStart, docs.size),
    )
  }

  /**
   * Decides the `start` of the next search page, or `null` at the end.
   *
   * Extracted so the rule is testable without a network round trip. Two properties matter
   * and neither is obvious from a single response:
   *
   * - The advance is the number of **documents** the server sent, not the number of rooms
   *   that survived [parseSearchRoom]: a page whose documents are dropped for having no
   *   room id still consumed that many results, and advancing by the mapped count would
   *   re-request them forever.
   * - An empty page ends the sequence even when `numFound` still promises more. Huya stops
   *   serving somewhere past `start` ≈ 1520 for a 1507-result query — verified live, a
   *   `start=1540` request answers `numFound: 0` with no documents — so the total alone
   *   would keep the pager asking for pages that never arrive.
   */
  internal fun nextSearchStart(bucket: JsonObject?, requestedStart: Int, docCount: Int): Int? {
    if (bucket == null || docCount == 0) return null
    val total = bucket.long("numFound") ?: return null
    val reportedStart = bucket.int("start") ?: requestedStart
    val consumed = reportedStart.toLong() + docCount
    return if (consumed < total) consumed.toInt() else null
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

  /**
   * Resolves the playable variant for a room.
   *
   * A Huya room page publishes **one quality** (原画) on several CDNs, so the two axes the
   * player UI knows are filled like this: [HuyaPlayback.variants] carries the single
   * variant and [HuyaPlayback.lines] carries the distinct CDN hosts, with
   * [HuyaPlayback.selectedLine] naming the one whose URL was built.
   *
   * This used to emit one variant per `gameStreamInfoList` entry. A real room page lists
   * six entries for three hosts — `AL`/`AL13` → `al-game.flv.huya.com`, `TX`/`TX15` →
   * `tx.flv.huya.com`, `HS`/`HS24` → `hs-game.flv.huya.com` — and every entry became a
   * variant labelled 原画, so the 清晰度 row drew six identical chips. Nothing distinguished
   * them, and two out of every three were the same CDN as another entry.
   *
   * The reference resolver has no quality axis either: it sorts the same entries by CDN and
   * plays the first one (`HuyaStreamUrlResolverAndroid.resolve`). Collapsing them into one
   * variant therefore drops no choice the reference offers, and the host — not `sCdnType`,
   * which differs per alias — is what identifies a CDN.
   *
   * @param preferredLine the CDN to resolve, as [HuyaPlayback.lines] names it. An unknown
   *   value falls back to the best ranked CDN instead of failing, so a stale preference
   *   from a room whose CDN list changed still plays.
   */
  suspend fun playback(roomId: String, preferredLine: String? = null): HuyaPlayback {
    val info = roomInfo(roomId)
    if (info.streams.isEmpty()) {
      throw PlatformOfflineException("主播未开播", Platform.HUYA)
    }
    val candidates = info.streams
      .sortedBy { HuyaSigner.cdnRank(it.cdnType) }
      .distinctBy { hostOf(it.flvUrl) }
    val chosen = candidates.firstOrNull { it.cdnType.equals(preferredLine, ignoreCase = true) }
      ?: candidates.first()
    val url = HuyaSigner.buildStreamUrl(
      flvUrl = chosen.flvUrl,
      streamName = chosen.streamName,
      suffix = chosen.suffix,
      antiCode = chosen.antiCode,
    )
    val variant = StreamVariant(
      qualityLabel = "原画",
      url = HuyaSigner.postProcess(url, chosen.cdnType),
      lineName = chosen.cdnType,
      isHls = chosen.suffix.contains("m3u8", ignoreCase = true),
      qualityRank = 0,
      qualityCode = ORIGINAL_QUALITY,
    )
    return HuyaPlayback(
      room = info,
      variants = listOf(variant),
      lines = candidates.map { it.cdnType },
      selectedLine = chosen.cdnType,
    )
  }

  /** The host of a stream URL — the identity of a CDN, unlike its per-alias `sCdnType`. */
  private fun hostOf(url: String): String =
    url.substringAfter("://", url).substringBefore('/').lowercase()

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

  /**
   * Maps one search document.
   *
   * The document carries the room's popularity as `game_activityCount` — the same
   * "人气" value `getLiveList` reports as `lUserCount`, and the number huya.com prints on
   * the card. This used to be left at 0, and `StreamerCard` only draws the viewer badge
   * when the count is positive, so every Huya search result silently lost its popularity
   * while the category and follow lists showed theirs.
   */
  internal fun parseSearchRoom(element: JsonElement): Streamer? {
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
      viewerCount = obj.long("game_activityCount") ?: 0,
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

    /**
     * The quality code of Huya's only selectable quality, 原画.
     *
     * Constant rather than derived: this field used to hold `HuyaSigner.cdnRank(cdnType)`,
     * which is a *line* preference. Because the player persists the code as the platform's
     * preferred quality, picking a CDN on one room wrote a "quality" that no other room
     * could honour — and for a two-CDN page the codes collided with real Douyu rates.
     */
    const val ORIGINAL_QUALITY = 0

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
    /**
     * Matches the viewer count whether the page encodes it as a number or as a string.
     *
     * The pattern used to be `"?(\d+)"` — the opening quote optional but the closing quote
     * mandatory. No JSON encodes a number that way (`12345"` is not a value), so in practice
     * it only ever matched the string form and a numeric `"activityCount":12345` silently
     * fell through to `0L`, i.e. every room reported zero viewers. Making both quotes
     * optional accepts either encoding; the room page has not been captured, so which one
     * Huya actually emits is not asserted here.
     */
    private val ACTIVITY_REGEX = Regex(""""(?:activityCount|totalCount)"\s*:\s*"?(\d+)"?""")
    private val CATEGORY_REGEX = Regex(""""gameFullName"\s*:\s*"([^"]*)"""")

    fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
  }
}
