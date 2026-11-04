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

  /**
   * Category tree fetched from the live API.
   *
   * The bundled table (`assets/categories/bilibili_categories.json`) is the primary source
   * because it needs no network. It goes stale as Bilibili adds areas, though — the shipped
   * copy carried 386 sub-categories while the live tree had 456, and 9 ids that no longer
   * exist — so this is the fallback that keeps the list complete when the asset is missing
   * or a refresh is wanted. `room/v1/Area/getList` is used rather than
   * `web-interface/v1/index/getAreaList`, which 404s.
   */
  suspend fun categories(): List<Category> {
    val response = runCatching {
      http.get(
        URL_AREA_LIST,
        headers = mapOf(
          "User-Agent" to UA_MOBILE,
          "Referer" to "https://live.bilibili.com/",
        ),
      )
    }.getOrNull() ?: return emptyList()
    val root = runCatching { parseObject(response.body, "Area/getList") }.getOrNull()
      ?: return emptyList()
    if (root["code"]?.jsonPrimitive?.intOrNull != 0) return emptyList()
    val groups = root["data"] as? JsonArray ?: return emptyList()

    val out = mutableListOf<Category>()
    var order = 0
    groups.forEach { groupElement ->
      val group = groupElement as? JsonObject ?: return@forEach
      val parentId = group["id"]?.jsonPrimitive?.intOrNull ?: return@forEach
      val groupName = group.str("name") ?: return@forEach
      val subs = group["list"] as? JsonArray ?: return@forEach
      subs.forEach { subElement ->
        val sub = subElement as? JsonObject ?: return@forEach
        // `id` arrives as a string in this endpoint, unlike the bundled table.
        val areaId = sub["id"]?.jsonPrimitive?.contentOrNull?.toIntOrNull() ?: return@forEach
        val name = sub.str("name") ?: return@forEach
        out += Category(
          platform = Platform.BILIBILI,
          id = "$parentId:$areaId",
          name = name,
          parentId = parentId.toString(),
          parentName = groupName,
          sortOrder = order++,
        )
      }
    }
    return out
  }

  // ---------------------------------------------------------------------- rooms

  suspend fun rooms(area: BilibiliArea, page: Int, pageSize: Int): Pair<List<Streamer>, Boolean> {
    return if (area == BilibiliArea.HOT) {
      hotRooms(page = page, pageSize = pageSize)
    } else {
      areaRooms(area = area, page = page)
    }
  }

  /**
   * One page of a category.
   *
   * `second/getList` is risk-controlled: it answers `code = -352` instead of an HTTP error.
   * The cause is the cookie — see [LIST_COOKIE] — so a rejection is retried once with a
   * freshly scraped `w_webid`, which is the part that can actually go stale.
   *
   * The retry used to call `cookieStore.clear()`. That was actively harmful: the store also
   * holds `SESSDATA`, so a category that hit -352 silently logged the user out of Bilibili.
   * Nothing here needs to touch the jar; the list request no longer reads it at all.
   */
  private suspend fun areaRooms(area: BilibiliArea, page: Int): Pair<List<Streamer>, Boolean> {
    val first = requestAreaPage(area, page, accessId())
    if (first["code"]?.jsonPrimitive?.intOrNull == RISK_CONTROL_CODE) {
      // Only the scraped identifier is discarded; the user's session is left alone.
      cachedAccessId = null
      accessIdFetchedAt = 0
      val retry = requestAreaPage(area, page, accessId())
      ensureSuccess(retry, "B站分区列表获取失败")
      return parseAreaPage(retry)
    }
    ensureSuccess(first, "B站分区列表获取失败")
    return parseAreaPage(first)
  }

  private suspend fun requestAreaPage(
    area: BilibiliArea,
    page: Int,
    accessId: String,
  ): JsonObject {
    val wts = System.currentTimeMillis() / 1000
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
        // Always the placeholder, never the stored jar — see LIST_COOKIE for why a real
        // buvid makes this endpoint answer -352.
        "Cookie" to LIST_COOKIE,
      ),
    )
    return response.requireSuccess().let { parseObject(it.body, "second/getList") }
  }

  /**
   * One page of a category, plus whether the response itself says there is more.
   *
   * The cursor used to be `rooms.size >= 20`, i.e. "I got back as many rooms as I asked
   * for". Both halves of that guess are wrong, measured against the live endpoint:
   *
   *  * The page size is the **server's** choice and varies per area — `1:145` answered 20
   *    rooms, `2:249` answered 27 and `2:86` answered 40 — so no single threshold can stand
   *    in for "this page was full".
   *  * A quiet area answers a *single room* with `has_more = 1` (`2:1048`, `2:1096`; `2:575`
   *    answered three). One card is below any threshold, so the category reported itself
   *    finished after the first page and the rest was unreachable.
   *
   * The response carries the answer directly in `data.has_more` (1/0). An empty page omits
   * the field altogether, which is also the end of the list.
   */
  private fun parseAreaPage(root: JsonObject): Pair<List<Streamer>, Boolean> {
    val data = root["data"] as? JsonObject
    val list = data?.get("list")?.jsonArray ?: JsonArray(emptyList())
    val rooms = list.mapNotNull { parseRoom(it) }
    val flag = data?.get("has_more")?.jsonPrimitive?.intOrNull
    val hasMore = if (flag != null) flag == 1 else list.isNotEmpty()
    return rooms to hasMore
  }

  /**
   * The hot tab, which is a single page.
   *
   * `page` and `page_size` are both ignored: `page_size=5`, `=20` and `=200` all answer the
   * same 110 rooms in 11 modules, and page 2 answers 110 rooms of which **97 are the ones
   * page 1 already returned** (page 5 does the same). Reporting "more" from the room count —
   * what this used to do — therefore kept the grid appending a rotating copy of itself with
   * no end, and every card the reader had already seen came back with it.
   */
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
    return rooms to false
  }

  // --------------------------------------------------------------------- search

  /**
   * Searches live rooms.
   *
   * A blank keyword short-circuits without a request, and any failure — unparseable JSON, a
   * non-2xx status, a non-zero `code` — surfaces as "no results" rather than an exception.
   * Search is the one place where an error message is less useful than an empty list: the
   * reference client behaves the same way, and a transient failure would otherwise replace
   * the result grid with a retry button the user did not ask for.
   *
   * The rooms live in **`result.live_room`**, not `result.live_user`. The reference client
   * reads `live_user`, and so did this method, but a captured response shows why that is the
   * wrong bucket: `live_user` answers **6 users per page regardless of the page number** (page
   * 1 and page 2 carried the same six ids) and those users come without a room title or a
   * viewer count — most were not even live. `live_room` answers **40 rooms per page** with
   * `title`, `uname`, `cover`, `uface`, `online` and `live_status`, i.e. exactly the fields
   * [parseSearchRoom] reads. Searching Bilibili therefore showed six stale cards and no way
   * to see the other 800-odd results.
   */
  suspend fun search(keyword: String, page: Int): Pair<List<Streamer>, Boolean> {
    val trimmed = keyword.trim()
    if (trimmed.isEmpty()) return emptyList<Streamer>() to false
    val cookie = runCatching { ensureBuvidCookie() }.getOrDefault("")
    val url = "$URL_SEARCH?context=&search_type=live&cover_type=user_cover&order=" +
      "&keyword=${encode(trimmed)}&category_id=&__refresh__=&_extra=&highlight=0" +
      "&single_column=0&page=$page"
    val response = runCatching {
      http.get(
        url,
        headers = buildMap {
          put("User-Agent", UA_CHROME138)
          put("Referer", "https://live.bilibili.com/")
          if (cookie.isNotBlank()) put("Cookie", cookie)
        },
      ).requireSuccess()
    }.getOrNull() ?: return emptyList<Streamer>() to false
    val root = runCatching { parseObject(response.body, "search/type") }.getOrNull()
      ?: return emptyList<Streamer>() to false
    val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
    if (code != 0) return emptyList<Streamer>() to false
    val data = root["data"] as? JsonObject
    val users = (data?.get("result") as? JsonObject)?.get("live_room")?.jsonArray
      ?: JsonArray(emptyList())
    val rooms = users.mapNotNull { parseSearchRoom(it) }
    return rooms to hasMoreSearchPage(data, page, rooms)
  }

  /**
   * Whether another search page exists.
   *
   * Counting the rooms on the page cannot answer this: the page size is 40 and the last page
   * is short, so "as many as I expected" is not a signal. The response says it outright —
   * `pageinfo.live_room.numPages` is the room bucket's own page count (22 for a query whose
   * rooms ended on page 22) while `data.numPages` is the largest across all buckets (25 for
   * the same query) and would page through three empty pages. Measured on page 22 (37 rooms)
   * and page 23 (0 rooms).
   */
  private fun hasMoreSearchPage(
    data: JsonObject?,
    page: Int,
    rooms: List<Streamer>,
  ): Boolean {
    val totalPages = (data?.get("pageinfo") as? JsonObject)
      ?.get("live_room")?.jsonObject?.get("numPages")?.jsonPrimitive?.intOrNull
      ?: data?.get("numPages")?.jsonPrimitive?.intOrNull
    return if (totalPages != null) page < totalPages else rooms.isNotEmpty()
  }

  // --------------------------------------------------------------------- detail

  /**
   * Everything the player header shows, from `getH5InfoByRoom`.
   *
   * This used to read `room/v1/Room/get_info`, which no longer carries the fields the
   * header needs: the live response has `title`, `live_status`, `online` and `user_cover`
   * but **no `uname`, no `anchor_info` and no `watched_show`** at all. Every branch that
   * looked for a name therefore fell through, so a Bilibili room opened with an empty
   * streamer name, an empty avatar and a viewer count of 0.
   *
   * `getH5InfoByRoom` is what the web room page itself calls, and the shape the reference
   * client maps in `fetchBilibiliFollowInfo`: the anchor sits in `anchor_info.base_info`
   * and the numbers in `room_info`. `room_info.online` is the count next to the live
   * badge; `watched_show.num` is the cumulative "看过" figure and only a fallback.
   */
  suspend fun roomInfo(roomId: String): BilibiliRoomInfo {
    val url = "$URL_ROOM_DETAIL?room_id=$roomId"
    val response = http.get(
      url,
      headers = mapOf(
        "User-Agent" to HttpClients.UA_SIMPLE,
        "Referer" to "https://live.bilibili.com/",
      ),
    )
    val root = response.requireSuccess().let { parseObject(it.body, "getH5InfoByRoom") }
    ensureSuccess(root, "B站房间信息获取失败")
    val data = root["data"]?.jsonObject
      ?: throw PlatformProtocolException("B站房间数据为空", Platform.BILIBILI)
    val room = data["room_info"] as? JsonObject ?: data
    val anchor = (data["anchor_info"] as? JsonObject)?.get("base_info") as? JsonObject
    val watched = data["watched_show"]?.jsonObject?.get("num")
      ?.jsonPrimitive?.contentOrNull?.toLongOrNull()
    return BilibiliRoomInfo(
      roomId = roomId,
      title = room.str("title").orEmpty(),
      streamerName = anchor?.str("uname")?.takeIf { it.isNotBlank() }
        ?: room.str("uname").orEmpty(),
      coverUrl = (room.str("keyframe") ?: room.str("cover") ?: room.str("user_cover"))
        ?.let(Texts::normalizeUrl),
      avatarUrl = anchor?.str("face")?.let(Texts::normalizeUrl),
      viewerCount = room["online"]?.jsonPrimitive?.longOrNull ?: watched ?: 0,
      isLive = (room["live_status"]?.jsonPrimitive?.intOrNull ?: 0) == 1,
    )
  }

  /**
   * Liveness of one room, for refreshing the follow list.
   *
   * That list refreshes every followed room, so it stays on the cheap `Room/get_info`
   * endpoint — the same one the reference client polls for the same reason. It has no
   * anchor name, which is fine: the follow entry already carries the name the user
   * followed. The full payload is [roomInfo]'s job.
   */
  suspend fun liveStatus(roomId: String): BilibiliLiveStatus {
    val url = "$URL_ROOM_INFO?room_id=$roomId"
    val response = http.get(
      url,
      headers = mapOf(
        "User-Agent" to HttpClients.UA_SIMPLE,
        "Referer" to "https://live.bilibili.com/",
      ),
    )
    val root = response.requireSuccess().let { parseObject(it.body, "Room/get_info") }
    ensureSuccess(root, "B站房间状态获取失败")
    val data = root["data"]?.jsonObject
      ?: throw PlatformProtocolException("B站房间数据为空", Platform.BILIBILI)
    return BilibiliLiveStatus(
      isLive = (data["live_status"]?.jsonPrimitive?.intOrNull ?: 0) == 1,
      title = data.str("title")?.takeIf { it.isNotBlank() },
      coverUrl = data.str("user_cover")?.let(Texts::normalizeUrl),
      viewerCount = data["online"]?.jsonPrimitive?.longOrNull ?: 0,
    )
  }

  // ------------------------------------------------------------------- playback

  /**
   * Resolves playable variants for a room.
   *
   * `getRoomPlayInfo` answers a matrix of `stream × format × codec` candidates: the
   * **codec** object carries the quality (`current_qn`), and the same quality appears
   * once per container — verified live, `qn=10000` on an anonymous client answers
   * `flv/avc 150`, `flv/hevc 250`, `ts/avc 150`, `ts/hevc 250`, `fmp4/avc 150`,
   * `fmp4/hevc 250`. Emitting all six produced a quality menu with six chips for two
   * qualities, several of which looked identical. One variant per quality is kept,
   * preferring HLS over progressive FLV because Media3's HLS module handles CDN
   * failover and discontinuity markers better.
   */
  suspend fun playback(roomId: String, preferredQn: Int?, preferredLine: String? = null): PlaybackSource {
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

    // Every `url_info` entry is the same rendition served from a different CDN, and the same
    // quality comes back once per container (flv / ts / fmp4) × codec (avc / hevc). The capture
    // in `fixtures/bilibili-playinfo.json` answers **six** entries for one quality, two hosts
    // each, and a different host pair per container — reading only `url_info[0]` threw away
    // every alternative, which is why the 线路 row had nothing to switch to.
    val candidates = mutableListOf<Candidate>()
    for (streamElement in streams) {
      val stream = streamElement as? JsonObject ?: continue
      val formats = stream["format"]?.jsonArray ?: JsonArray(emptyList())
      for (formatElement in formats) {
        val format = formatElement as? JsonObject ?: continue
        val codecs = format["codec"]?.jsonArray ?: JsonArray(emptyList())
        for (codecElement in codecs) {
          val codec = codecElement as? JsonObject ?: continue
          val baseUrl = codec.str("base_url") ?: continue
          val qn = codec.qualityQn()
          val isHls = baseUrl.contains(".m3u8", ignoreCase = true)
          val rank = BilibiliQuality.rankOf(baseUrl)
          val infos = codec["url_info"]?.jsonArray ?: JsonArray(emptyList())
          for (infoElement in infos) {
            val urlInfo = infoElement as? JsonObject ?: continue
            val host = urlInfo.str("host") ?: continue
            val extra = urlInfo.str("extra").orEmpty().trimStart('?', '&')
            candidates += Candidate(
              qn = qn,
              baseUrl = baseUrl,
              host = host.removePrefix("https://").removePrefix("http://").substringBefore('/'),
              url = buildPlayUrl(host, baseUrl, extra),
              isHls = isHls,
              rank = rank,
            )
          }
        }
      }
    }

    if (candidates.isEmpty()) {
      throw PlatformProtocolException("B站未解析到播放地址", Platform.BILIBILI)
    }
    // One entry per quality, best container first: HLS before FLV, then whatever the answer
    // listed first, then the first CDN of that container.
    val bestPerQuality = candidates
      .groupBy { it.qn }
      .mapValues { (_, list) -> list.minWith(compareBy({ it.rank }, { it.baseUrl }, { it.host })) }
    val defaultQn = preferredQn?.takeIf { bestPerQuality.containsKey(it) }
      ?: bestPerQuality.keys.minWith(compareBy { qualityOrder(it) })

    // The line axis is the CDN list of the container this quality actually resolves through.
    // Mixing in the other containers' hosts offered six chips, four of which pointed at a
    // format the player was not using.
    val container = bestPerQuality.getValue(defaultQn).baseUrl
    val containerHosts = candidates.filter { it.qn == defaultQn && it.baseUrl == container }
    val lines = containerHosts.map { it.host }.distinct()
    // A line the room no longer publishes (or one remembered from another room) falls back to
    // the first CDN of this container instead of failing: a stale preference must still play.
    val chosen = preferredLine?.let { line -> containerHosts.firstOrNull { it.host == line } }
      ?: containerHosts.first()
    val ordered = bestPerQuality
      .map { (qn, best) -> if (qn == defaultQn) chosen else best }
      .sortedWith(compareBy({ qualityOrder(it.qn) }, { it.rank }))
    val room = Streamer(
      platform = Platform.BILIBILI,
      roomId = roomId,
      streamerName = "",
      title = "",
      isLive = true,
      updatedAt = System.currentTimeMillis(),
    )
    return PlaybackSource(
      room = room,
      variants = ordered.map { it.toVariant() },
      defaultVariant = chosen.toVariant(),
      lines = lines,
      selectedLine = chosen.host,
    )
  }

  /** One rendition: a quality, in one container, served from one CDN. */
  private data class Candidate(
    val qn: Int,
    val baseUrl: String,
    val host: String,
    val url: String,
    val isHls: Boolean,
    val rank: Int,
  ) {
    fun toVariant(): StreamVariant = StreamVariant(
      qualityLabel = BilibiliQuality.labelForQn(qn),
      url = url,
      lineName = host,
      isHls = isHls,
      qualityRank = rank,
      qualityCode = qn,
    )
  }

  /** qn codes sort high-to-low so the best quality is the first chip. */
  private fun qualityOrder(qn: Int?): Int = when (qn) {
    null, 0 -> Int.MAX_VALUE
    else -> -qn
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

  /**
   * Scrapes the `access_id` used as `w_webid`; cached for 30 minutes.
   *
   * The token is a tenant-scoped JWT (24h TTL) embedded in the category page. Two rules,
   * both measured against the live endpoint:
   *
   *  * The scrape must use the **same** cookie value the list request later sends, so it
   *    uses [LIST_COOKIE] rather than the jar.
   *  * A value that is not a real JWT is worthless: the previously hardcoded fallback made
   *    every request answer -352. When the scrape fails we therefore return an empty string
   *    and let the caller surface a real error, instead of sending a token that cannot work.
   */
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
          "Cookie" to LIST_COOKIE,
        ),
      ).requireSuccess().body
    }.getOrNull().orEmpty()
    val id = ACCESS_ID_REGEX.find(html)?.groupValues?.getOrNull(1)
    if (!id.isNullOrBlank() && id.count { it == '.' } == 2) {
      cachedAccessId = id
      accessIdFetchedAt = System.currentTimeMillis()
      return id
    }
    // A previously scraped value outlives a transient page change; the hardcoded constant
    // did not, because it was never a valid token in the first place.
    return cached.orEmpty()
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
    /** The authoritative area tree; `web-interface/v1/index/getAreaList` 404s. */
    const val URL_AREA_LIST = "https://api.live.bilibili.com/room/v1/Area/getList?show_pinyin=1"
    const val URL_SEARCH = "https://api.bilibili.com/x/web-interface/search/type"
    const val URL_ROOM_INFO = "https://api.live.bilibili.com/room/v1/Room/get_info"

    /** The room payload the player header needs; `Room/get_info` no longer has the anchor. */
    const val URL_ROOM_DETAIL = "https://api.live.bilibili.com/xlive/web-room/v1/index/getH5InfoByRoom"
    const val URL_PLAY_INFO = "https://api.live.bilibili.com/xlive/web-room/v2/index/getRoomPlayInfo"
    const val URL_DANMU_INFO = "https://api.live.bilibili.com/xlive/web-room/v1/index/getDanmuInfo"
    const val URL_NAV = "https://api.bilibili.com/x/web-interface/nav"
    const val URL_FINGER_SPI = "https://api.bilibili.com/x/frontend/finger/spi"

    const val DEFAULT_QN = 10000
    const val NAV_TTL_MILLIS = 6L * 60 * 60 * 1000
    const val ACCESS_ID_TTL_MILLIS = 30L * 60 * 1000

    /** Bilibili answers this when the `w_webid` is risk-controlled. */
    const val RISK_CONTROL_CODE = -352

    /**
     * Cookie the list endpoint needs. Bilibili answers `-352` when the request carries no
     * cookie at all, so this placeholder is used until a real buvid is negotiated.
     */
    const val PLACEHOLDER_COOKIE = "buvid3=i;"

    /**
     * Cookie `second/getList` must carry, verbatim.
     *
     * The rule the live endpoint actually enforces is "**a `buvid3` must be present**".
     * Measured (area 2:86):
     *
     *  * `buvid3=i;` → `code = 0`, 40 rooms
     *  * no cookie, or a cookie without `buvid3` (e.g. only `LIVE_BUVID`) → `-352`
     *  * a *real* `buvid3` from `finger/spi` → also `-352`
     *
     * So the placeholder is not merely tolerated, it is the only value that works: this
     * endpoint accepts an anonymous marker and rejects a device fingerprint it has no
     * session for.
     *
     * Setting the header is necessary but was not sufficient. OkHttp's `BridgeInterceptor`
     * replaces the `Cookie` header with the cookie jar's contents whenever the jar has
     * anything for that host, and `getRoomInfo` hands back `Set-Cookie: LIVE_BUVID=…`. Once
     * the user opened a single room, the jar held `LIVE_BUVID`, this header was overwritten
     * with it, and the request lost its `buvid3` — every category then answered -352 until
     * the process restarted and the in-memory jar was cleared. `InMemoryCookieJar` now
     * ignores bilibili.com for exactly this reason.
     */
    const val LIST_COOKIE = "buvid3=i;"

    // Stable fallback used only when the nav endpoint is unreachable, so WBI signing
    // degrades instead of failing outright. There is deliberately no equivalent for the
    // list `access_id`: that one is a JWT the server validates, and a hardcoded stand-in
    // answered -352 on every request.
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

/** Room details resolved from `getH5InfoByRoom`. */
data class BilibiliRoomInfo(
  val roomId: String,
  val title: String,
  val streamerName: String,
  val coverUrl: String?,
  val avatarUrl: String?,
  val viewerCount: Long,
  val isLive: Boolean,
)

/** The few fields the follow list needs, from `Room/get_info`. */
data class BilibiliLiveStatus(
  val isLive: Boolean,
  val title: String?,
  val coverUrl: String?,
  val viewerCount: Long,
)
