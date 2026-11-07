package com.livehub.platform.douyin

import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import com.livehub.core.network.HttpClient
import com.livehub.core.network.Texts
import com.livehub.platform.api.PlatformAuthRequiredException
import com.livehub.platform.api.PlatformOfflineException
import com.livehub.platform.api.PlatformProtocolException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
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
  /**
   * The card for this room.
   *
   * [roomId] is the id **the caller used**, not the payload's. `enter` reports Douyin's
   * internal `id_str` (`7703900142109592362`), while everything outside the player addresses a
   * room by its `web_rid` (`1002683794`) — the id in the room's URL, in search results, and in
   * the follow list. Defaulting to the payload id here is how a room followed from the player
   * ended up stored under its internal id: `enter?web_rid=<id_str>` answers
   * `status_code 4001038` with no rooms, so that card could never be reopened or refreshed.
   * The internal id stays on [DouyinRoomInfo.roomId] for the danmaku handshake, which needs it.
   */
  fun toStreamer(roomId: String): Streamer = Streamer(
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

/**
 * Everything opening a room needs, parsed from **one** `room/web/enter` response.
 *
 * The adapter used to call `playback` for the URLs and then `roomDetail` for the `Streamer`,
 * which fetched this payload twice. Returning the parse result alongside the renditions
 * makes that second fetch impossible to reintroduce by accident.
 */
data class DouyinPlayback(
  val info: DouyinRoomInfo,
  val variants: List<StreamVariant>,
)

/** HTTP + parsing for Douyin live. */
class DouyinApi(
  private val http: HttpClient,
  /**
   * Persists a negotiated `ttwid` across calls and restarts.
   *
   * Defaults to a no-op so tests and callers that do not care behave exactly as before.
   */
  private val cookieStore: DouyinCookieStore = DouyinCookieStore.inMemory(),
) {

  val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
  }

  /**
   * The `Cookie` header to send.
   *
   * Negotiates a real `ttwid` on first use instead of shipping the year-old literal. That
   * literal is why the first room-open after a cold start failed: Douyin answered the enter
   * call with an empty body and only then handed back a usable `ttwid` in `Set-Cookie`, so
   * the second attempt — and every later one — worked. Fetching the page first means the
   * first attempt already carries a valid value.
   *
   * Read from the store on **every** call rather than cached here: a QR login writes new
   * cookies while the process is running, and a cached header kept sending the anonymous
   * `ttwid` until the next launch — which reads as "just logged in, still asked to log in".
   *
   * Everything here is best-effort: if the negotiation fails the fallback is still sent,
   * because a request with *no* cookie looks like an empty category rather than an error.
   */
  suspend fun cookieHeader(): String {
    val stored = cookieStore.header()
    if (stored.contains("ttwid=")) return stored
    val fresh = runCatching { negotiateTtwid() }.getOrNull()
    if (!fresh.isNullOrBlank()) {
      cookieStore.save(fresh)
      return cookieStore.header().ifBlank { fresh }
    }
    return stored.ifBlank { DouyinSigner.FALLBACK_COOKIE }
  }

  /**
   * Asks Douyin for a `ttwid` by loading any live page.
   *
   * The page is not parsed: what matters is the `Set-Cookie` header, which arrives on a
   * plain 200 for every `live.douyin.com` URL.
   */
  private suspend fun negotiateTtwid(): String? {
    val response = runCatching {
      http.get(
        "https://live.douyin.com/",
        headers = mapOf(
          "User-Agent" to DouyinSigner.USER_AGENT,
          "Accept" to "text/html,application/xhtml+xml,*/*",
          "Accept-Encoding" to "identity",
        ),
      )
    }.getOrNull() ?: return null
    val cookies = response.headersNamed("Set-Cookie")
    if (cookies.isEmpty()) return null
    val ttwid = cookies.firstNotNullOfOrNull { raw ->
      val pair = raw.substringBefore(';').trim()
      pair.takeIf { it.startsWith("ttwid=") && it.length > "ttwid=".length }
    } ?: return null
    return ttwid
  }

  /**
   * Partition tree from the bundled catalogue.
   *
   * The adapter loads the asset first; this is the network-side equivalent and intentionally
   * does not invent a tree. Douyin publishes no partition endpoint that returns the
   * catalogue (the `partition` parameter *filters* rooms, it does not enumerate sections), so
   * an empty list here means "the asset is missing", which the UI can report honestly.
   */
  suspend fun categories(): List<Category> = emptyList()

  /**
   * One page of rooms for a partition.
   *
   * This is the endpoint that actually honours `partition`/`partition_type`. It requires an
   * `a_bogus` signature: unsigned, `/room/` answers HTTP 444 and `/room/v2/` answers HTTP 200
   * with a zero-length body — the latter is what previously looked like "this partition has
   * no rooms" and drove the adapter onto the recommendation feed for every category. With a
   * valid signature it returns `status_code = 0` and real, partition-filtered rooms.
   *
   * The rooms it returns carry `room.status == 0` while `webcast/feed` uses `2`, so status is
   * not a live flag on either path; [isRoomOnAir] deliberately ignores it.
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
      // Judged by the same rule as roomDetail: a pull URL must exist. This endpoint only
      // lists rooms it considers live, so `true` here was a reasonable shortcut for a card —
      // but it disagreed with the detail page, which reported 没有直播 for a room whose
      // `stream_url` was empty. The card is what the user taps, so both sides have to agree.
      isLive = isRoomOnAir(room?.get("stream_url")?.jsonObject),
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
      // Same rule as the detail page, so a feed card never promises a stream the player
      // cannot open.
      isLive = isRoomOnAir(room["stream_url"]?.jsonObject),
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

  /**
   * Douyin search.
   *
   * A **web_rid** resolves through `room/web/enter` and needs no login. `web_rid` is a *string*,
   * not a number: Douyin hands creators vanity ids like `AYM0707` (measured 2026-11-07 — that one
   * returns 房间「来啦来啦，开搞开搞」/ DNF王大枪 and is live), and the numeric form is just the
   * common case. So "is this an id?" cannot be answered by "are all characters digits"; that
   * assumption is what made a perfectly good room number fall through to the keyword branch and
   * get told to log in.
   *
   * The order is therefore: **try the id first, fall back to keyword search**. A candidate is a
   * single ASCII word (letters/digits/`_`/`-`), which is what both id forms satisfy and what no
   * plausible nickname with spaces or CJK does.
   *
   * A **keyword** goes to `www.douyin.com/aweme/v1/web/live/search/`, which refuses anonymous
   * callers with `status_code = 2483` / `请先登录，再继续搜索吧` (measured 2026-11-04, with a
   * negotiated `ttwid` and the full parameter set). So keyword search is only offered when a
   * QR login has put a session cookie in [cookieStore], and the message says where to log in.
   */
  suspend fun search(keyword: String, page: Int): Pair<List<Streamer>, Boolean> {
    val webRid = webRidFrom(keyword)
    if (webRid != null) {
      // One id can only produce one card, so there is no second page to ask for.
      if (page > 1) return emptyList<Streamer>() to false
      val info = enterRoom(webRid)
      if (info != null) return listOf(info.toStreamer(webRid)) to false
      // The id did not resolve. An ASCII word can still be a nickname (`uzi`, `PDD`), so a
      // logged-in session gets one more attempt as a keyword; without a session, "没有找到" is
      // the honest answer — claiming "需要登录" for a string the user means as a room id is the
      // exact misreport this path keeps producing.
      if (!cookieStore.isLoggedIn()) return emptyList<Streamer>() to false
    }

    val trimmed = keyword.trim()
    if (trimmed.isEmpty()) return emptyList<Streamer>() to false

    if (!cookieStore.isLoggedIn()) {
      throw PlatformAuthRequiredException(
        "抖音的关键词搜索需要登录（接口返回 2483「请先登录，再继续搜索吧」）。" +
          "在「设置 → 抖音账号」扫码登录后即可按昵称搜索；" +
          "输入直播间号（字母数字都行）或直接粘贴直播间链接时不需要登录。",
        Platform.DOUYIN,
      )
    }
    return searchByKeyword(trimmed, page)
  }

  /**
   * The web_rid inside [raw], or null when [raw] cannot be one.
   *
   * Accepts the shapes a user can realistically paste — the bare id (`1002683794` or the vanity
   * form `AYM0707`), the whole live-room URL, the app's own share text
   * ("… https://live.douyin.com/AYM0707 … 复制打开"), full-width digits from a CJK keyboard, and a
   * `房间号：<id>` label. Returns null for anything that reads as a name with spaces or CJK, so a
   * nickname still reaches the keyword path.
   *
   * `Char.isDigit()` alone was not enough in either direction: it is false for the zero-width
   * space that survives `trim()` (category Cf, not whitespace), and a `web_rid` need not be
   * numeric at all.
   */
  fun webRidFrom(raw: String): String? {
    // `live.douyin.com/<id>` / `v.douyin.com/<code>`: the path segment is the id, and it may be
    // letters. Matched before anything else so a link never falls through to "keyword".
    URL_ROOM_ID.find(raw)?.groupValues?.getOrNull(1)?.let { candidate ->
      return candidate.takeIf { it.isIdShaped() }
    }

    // Strip the label a user types in front of the id, plus decoration that a copy carries along
    // (whitespace, zero-width chars, full-width punctuation). What remains has to *be* the id.
    val stripped = raw.replace(NUMBER_LABEL, "")
      .filterNot { it.isWhitespace() || it.isInvisibleOrSeparator() }
    if (stripped.isEmpty()) return null

    // Full-width digits are digits (category Nd) but not ASCII; fold them so the value is a valid
    // query parameter. Letters are left alone: a web_rid is case-sensitive.
    val folded = stripped.map { if (it.isDigit()) it.digitToInt().digitToChar() else it }.joinToString("")
    return folded.takeIf { it.isIdShaped() }
  }

  /**
   * Whether [value] has the shape of a web_rid: one ASCII word, no separators inside.
   *
   * ASCII-only on purpose. `Char.isLetterOrDigit()` is true for CJK, so it would accept 红眼阿旺
   * as an id and never reach keyword search.
   */
  private fun String.isIdShaped(): Boolean =
    length in 1..MAX_WEB_RID_LENGTH && all { char ->
      char in 'a'..'z' || char in 'A'..'Z' || char in '0'..'9' || char == '_' || char == '-'
    }

  /**
   * Characters a copy can carry along that never mean anything in an id.
   *
   * `-` and `_` are deliberately **not** here: a web_rid may contain them (`AYM-0707`), so
   * stripping them would corrupt the id. They are legal id characters, handled by
   * [isIdShaped] instead.
   */
  private fun Char.isInvisibleOrSeparator(): Boolean =
    this == '\u200B' || this == '\u200C' || this == '\u200D' || this == '\uFEFF' ||
      this == '：' || this == ':' || this == '　' ||
      this == '，' || this == ',' || this == '.' || this == '。'

  /** Keyword search for a logged-in session; see [search]. */
  private suspend fun searchByKeyword(
    keyword: String,
    page: Int,
  ): Pair<List<Streamer>, Boolean> {
    val params = listOf(
      "device_platform" to "webapp",
      "aid" to "6383",
      "channel" to "channel_pc_web",
      "search_channel" to "aweme_live",
      "keyword" to keyword,
      "search_source" to "switch_tab",
      "query_correct_type" to "1",
      "is_filter_search" to "0",
      "from_group_id" to "",
      "offset" to ((page - 1) * SEARCH_PAGE_SIZE).toString(),
      "count" to SEARCH_PAGE_SIZE.toString(),
      "pc_client_type" to "1",
      "version_code" to "170400",
      "version_name" to "17.4.0",
      "cookie_enabled" to "true",
      "screen_width" to "1980",
      "screen_height" to "1080",
      "browser_language" to "zh-CN",
      "browser_platform" to "Win32",
      "browser_name" to "Edge",
      "browser_version" to "125.0.0.0",
      "browser_online" to "true",
      "engine_name" to "Blink",
      "engine_version" to "125.0.0.0",
      "os_name" to "Windows",
      "os_version" to "10",
      "cpu_core_num" to "12",
      "device_memory" to "8",
      "platform" to "PC",
      "downlink" to "10",
      "effective_type" to "4g",
      "round_trip_time" to "100",
      // The web client sends a browser id. The endpoint appears to require its presence
      // rather than a particular value (the reference client hardcodes one too).
      "webid" to SEARCH_WEB_ID,
    )
    val query = params.joinToString("&") { (key, value) ->
      "$key=${DouyinSigner.encodeQueryValue(value)}"
    }
    // Deliberately unsigned: the reference client sends this endpoint without `a_bogus`
    // (`getAbogusUrl` is commented out there), and a signature is bound to the UA and query
    // shape — sending a wrong one turns a working request into a refusal.
    val response = http.get(
      "$URL_LIVE_SEARCH?$query",
      headers = headers().toMutableMap().apply {
        put("Referer", "https://www.douyin.com/search/${DouyinSigner.encodeQueryValue(keyword)}?type=live")
        put("Accept", "application/json, text/plain, */*")
      },
    )
    val body = response.requireSuccess().body
    if (body.isBlank() || body.trim() == "blocked") {
      throw PlatformProtocolException("抖音搜索被限制，请稍后再试", Platform.DOUYIN)
    }

    val root = parseObject(body, "live/search")
    val status = root["status_code"]?.jsonPrimitive?.intOrNull ?: -1
    if (status == STATUS_LOGIN_REQUIRED) {
      throw PlatformAuthRequiredException(
        "抖音要求重新登录后才能搜索：${root.str("status_msg") ?: "请先登录"}",
        Platform.DOUYIN,
      )
    }
    if (status != 0) {
      throw PlatformProtocolException(
        "抖音搜索失败: status=$status ${root.str("status_msg").orEmpty()}".trim(),
        Platform.DOUYIN,
      )
    }

    val items = root["data"] as? JsonArray ?: JsonArray(emptyList())
    val rooms = items.mapNotNull { element ->
      val lives = (element as? JsonObject)?.get("lives") as? JsonObject
        ?: return@mapNotNull null
      // `rawdata` is the live room object, double-encoded as a JSON string.
      val room = lives.str("rawdata")
        ?.let { raw -> runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() }
        ?: lives
      parseSearchCard(room)
    }
    return rooms to (rooms.size >= SEARCH_PAGE_SIZE)
  }

  /** Maps one `live/search` hit; the room object is the same shape the partition list uses. */
  private fun parseSearchCard(room: JsonObject): Streamer? {
    val owner = room["owner"] as? JsonObject
    val webRid = owner?.str("web_rid") ?: room.str("web_rid") ?: return null
    if (webRid.isBlank()) return null
    val streamUrl = room["stream_url"] as? JsonObject
    return Streamer(
      platform = Platform.DOUYIN,
      roomId = webRid,
      streamerName = owner?.str("nickname").orEmpty().ifBlank { "抖音主播" },
      title = room.str("title").orEmpty().ifBlank { "直播中" },
      coverUrl = firstImage(room["cover"]),
      avatarUrl = firstImage(owner?.get("avatar_thumb")),
      viewerCount = room["stats"]?.jsonObject?.get("total_user")?.jsonPrimitive?.longOrNull
        ?: room.str("user_count_str")?.let { parseCount(it) }
        ?: 0,
      // A search hit from the live channel is a live room; when the payload does carry pull
      // URLs, they are the honest answer and win.
      isLive = if (streamUrl == null) true else isRoomOnAir(streamUrl),
      updatedAt = System.currentTimeMillis(),
    )
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
        "Cookie" to cookieHeader(),
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
      ),
    ).requireSuccess().body
  }.getOrDefault("")

  suspend fun roomInfo(roomId: String): DouyinRoomInfo = enterRoom(roomId)
    ?: throw PlatformProtocolException(
      "抖音直播间信息获取失败，该直播间可能未开播",
      Platform.DOUYIN,
    )

  /**
   * One `room/web/enter` round trip, or null when the payload carries no room.
   *
   * Split out of [roomInfo] so [search] can ask "is there a room with this id?" without
   * turning "no" into the error the room page needs to show.
   */
  private suspend fun enterRoom(roomId: String): DouyinRoomInfo? {
    val params = listOf(
      "aid" to "6383",
      "app_name" to "douyin_web",
      "live_id" to "1",
      "device_platform" to "web",
      "language" to "zh-CN",
      "browser_language" to "zh-CN",
      "browser_platform" to "Win32",
      "browser_name" to "Chrome",
      "browser_version" to DouyinSigner.BROWSER_VERSION,
      "web_rid" to roomId,
      // `msToken` must be present even when empty. The reference client sends it on this
      // endpoint and it works; omitting the key entirely (as this did) made `enter` answer
      // HTTP 200 with an empty body, which then fell through to the six-room feed and
      // produced "该直播间可能未开播" for every room not in that feed.
      "msToken" to "",
    )
    val url = "$URL_ENTER?${DouyinSigner.signQuery(params)}"
    // The Referer has to be the room page, not the site root: Douyin checks it against
    // `web_rid`, and a mismatched Referer is another way to get the empty 200.
    val response = http.get(
      url,
      headers = headers().toMutableMap().apply {
        put("Referer", "https://live.douyin.com/$roomId")
        // Without this the body may be gzip'd, and the hand-rolled reader below would see
        // bytes it cannot parse.
        put("Accept-Encoding", "identity")
      },
    )

    val root = runCatching { parseObject(response.body, "enter") }.getOrNull()
    // The live shape is `data.data[0]`, not `data.room` — reading `data.room` always yielded
    // null, so the feed fallback ran even on a successful response.
    val data = root?.get("data")?.let { it as? JsonObject }
    val entered = (data?.get("data") as? JsonArray)?.firstOrNull()?.let { it as? JsonObject }
      ?: data?.get("room")?.let { it as? JsonObject }
    // `data.user` carries the anchor identity; the room object does not repeat it.
    val anchor = data?.get("user")?.let { it as? JsonObject }
    val room = entered ?: findInFeed(roomId) ?: return null
    val owner = room["owner"]?.jsonObject
    val streamUrl = room["stream_url"]?.jsonObject
    // `enter` puts the anchor on `data.user`; the feed items carry it on the room's `owner`.
    // Reading only `room.owner` left the nickname and avatar blank for every `enter` hit.
    val nickname = anchor?.get("nickname")?.jsonPrimitive?.contentOrNull
      ?: owner?.get("nickname")?.jsonPrimitive?.contentOrNull
    val avatarUrls = anchor?.get("avatar_thumb")?.jsonObject?.get("url_list")
      ?: owner?.get("avatar_thumb")?.jsonObject?.get("url_list")
    return DouyinRoomInfo(
      roomId = room["id_str"]?.jsonPrimitive?.contentOrNull ?: roomId,
      title = room["title"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      streamerName = nickname.orEmpty(),
      avatarUrl = avatarUrls?.jsonArray?.firstOrNull()
        ?.jsonPrimitive?.contentOrNull?.let(Texts::normalizeUrl),
      coverUrl = room["cover"]?.jsonObject?.get("url_list")?.jsonArray?.firstOrNull()
        ?.jsonPrimitive?.contentOrNull?.let(Texts::normalizeUrl),
      viewerCount = room["stats"]?.jsonObject?.get("total_user")
        ?.jsonPrimitive?.longOrNull
        ?: room["user_count_str"]?.jsonPrimitive?.contentOrNull?.let { parseCount(it) }
        ?: 0,
      // A pull URL is the only honest evidence that a room is on air; see isRoomOnAir.
      isLive = isRoomOnAir(streamUrl),
      variants = parseVariants(streamUrl),
    )
  }

  /**
   * Decides whether a room is on air, from the pull URLs alone.
   *
   * `room.status` is not a usable live flag and never was. Measured across the two endpoints
   * that serve it: `webcast/feed` reports `status = 2` for every card including rooms whose
   * `stream_url` is empty, while `partition/detail/room/v2` reports `status = 0` for all 15
   * genuinely live rooms. Reading it therefore produced both failure modes at once — offline
   * cards shown as 直播中, and live partition rooms hidden.
   *
   * The pull URLs are the honest signal: Douyin only fills them for a room it is actually
   * streaming, and they are exactly what the player needs. Both spellings count, because the
   * payload has two: `hls_pull_url_map`/`flv_pull_url` maps from `enter`, and the **flat**
   * `hls_pull_url` string the anonymous feed carries. Reading only the maps made every
   * feed-sourced room look offline — `playback` then threw 主播未开播 for a room whose hls URL
   * was right there in the same object, which is the one rendition [parseVariants] documents
   * as the feed's only one.
   */
  private fun isRoomOnAir(streamUrl: JsonObject?): Boolean {
    if (streamUrl == null) return false
    fun hasUrl(map: JsonObject?): Boolean = map?.values?.any { element ->
      element.jsonPrimitive.contentOrNull?.isNotBlank() == true
    } == true
    fun flatUrl(key: String): Boolean =
      (streamUrl[key] as? JsonPrimitive)?.contentOrNull?.isNotBlank() == true

    return hasUrl(streamUrl["hls_pull_url_map"] as? JsonObject) ||
      hasUrl(streamUrl["flv_pull_url"] as? JsonObject) ||
      hasUrl(streamUrl["flv_pull_url_map"] as? JsonObject) ||
      flatUrl("hls_pull_url") ||
      flatUrl("flv_pull_url") ||
      flatUrl("rtmp_pull_url")
  }

  /**
   * Resolves a room for playback from a single `enter` call.
   *
   * The streamer metadata and the pull URLs come from the same response: the caller needs
   * both, and asking for the payload twice (as the adapter used to) doubled the wait before
   * the first frame, while a failure on the redundant second call turned a room whose URLs
   * had already resolved into 抖音房间信息获取失败.
   */
  suspend fun playback(roomId: String): DouyinPlayback {
    val info = roomInfo(roomId)
    if (!info.isLive) throw PlatformOfflineException("主播未开播", Platform.DOUYIN)
    if (info.variants.isEmpty()) {
      throw PlatformProtocolException("抖音未解析到播放地址", Platform.DOUYIN)
    }
    val variants = info.variants.map { variant ->
      StreamVariant(
        qualityLabel = variant.name,
        url = variant.url,
        lineName = "抖音",
        isHls = variant.url.contains(".m3u8", ignoreCase = true),
        qualityRank = variant.rank,
        qualityCode = variant.rank,
      )
    }
    return DouyinPlayback(info = info, variants = variants)
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
          rank = qualityRank(entry.key, index) + FLV_OFFSET,
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
        rank = qualityRank(entry.key, index) + FLV_OFFSET,
      )
    }
    return builder.sortedBy { it.rank }.let { ordered ->
      // One chip per quality. `enter` describes the *same* renditions twice — once under
      // `hls_pull_url_map` and once under `flv_pull_url` — and both maps use the same keys, so
      // `FULL_HD1` produced two variants that both display as 原画: the 清晰度 row offered two
      // identical chips and one of them was the container the reference resolver warns about
      // ("some Douyin FLV streams may be H.265-in-FLV which Media3/ExoPlayer may not render —
      // audio-only on many devices"). Keeping the best-ranked candidate resolves the duplicate
      // in favour of hls, and a quality that exists *only* as flv is still offered.
      ordered.distinctBy { it.name }
    }
  }

  private fun qualityName(key: String): String = when {
    key.contains("origin", ignoreCase = true) || key == "FULL_HD1" -> "原画"
    key == "HD1" -> "蓝光"
    key == "SD1" -> "高清"
    key == "SD2" -> "标清"
    else -> key
  }

  /**
   * Orders renditions best-first, with **every** hls rendition ahead of every flv one.
   *
   * The hls/flv split is load-bearing, not cosmetic: the reference resolver prefers hls for
   * the same reason ("some Douyin FLV streams may be H.265-in-FLV which Media3/ExoPlayer may
   * not render — audio-only on many devices"). Known keys keep the ranks they have always
   * had, because `qualityCode` is persisted as the user's preferred quality; a key nobody has
   * mapped yet lands in the 4..[FLV_OFFSET]-1 gap and is clamped there, so it can never sort
   * after the flv renditions and hand the player the format that may not render.
   */
  private fun qualityRank(key: String, index: Int): Int = when {
    key.contains("origin", ignoreCase = true) || key == "FULL_HD1" -> 0
    key == "HD1" -> 1
    key == "SD1" -> 2
    key == "SD2" -> 3
    else -> (4 + index).coerceAtMost(FLV_OFFSET - 1)
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
   * exactly like an empty category. The value is negotiated (see [cookieHeader]) rather
   * than the shipped literal, which was a year old and made the first room-open fail.
   *
   * Suspending because negotiation may need one network call the first time; afterwards the
   * value is cached and this is a map lookup.
   */
  private suspend fun headers(): Map<String, String> = mapOf(
    "User-Agent" to DouyinSigner.USER_AGENT,
    "Referer" to "https://live.douyin.com/",
    "Cookie" to cookieHeader(),
    "Accept" to "application/json, text/plain, */*",
    "Accept-Language" to "zh-CN,zh;q=0.9",
  )

  private fun parseObject(body: String, label: String): JsonObject = try {
    json.parseToJsonElement(body).jsonObject
  } catch (error: Exception) {
    throw PlatformProtocolException("抖音 $label 返回无法解析的 JSON", Platform.DOUYIN, error)
  }

  companion object {
    /**
     * Rank shift applied to every flv rendition.
     *
     * The values below it (0..3 for the known keys, 4..9 for hls keys not mapped yet) are
     * reserved for hls, so "hls sorts first" holds for renditions nobody has seen before.
     */
    private const val FLV_OFFSET = 10

    const val URL_FEED = "https://live.douyin.com/webcast/feed/"
    /**
     * Partition-scoped list; requires an `a_bogus` signature.
     *
     * The unscoped `/partition/detail/room/` variant answers HTTP 444 for every request, so
     * there is no second URL to fall back to.
     */
    const val URL_PARTITION_V2 =
      "https://live.douyin.com/webcast/web/partition/detail/room/v2/"
    const val URL_ENTER = "https://live.douyin.com/webcast/room/web/enter/"
    /**
     * Keyword search, on the **www** host — the live host has no search path at all
     * (`/webcast/search/partition/room/` answers 404 on every Douyin host).
     */
    const val URL_LIVE_SEARCH = "https://www.douyin.com/aweme/v1/web/live/search/"

    /** Page size the web client asks for; doubles as the "has more" threshold. */
    const val SEARCH_PAGE_SIZE = 10

    /**
     * Browser id sent with keyword search.
     *
     * Borrowed from the reference client (`dart_simple_live`), which hardcodes one as well.
     * Douyin appears to check that the parameter exists rather than its value.
     */
    private const val SEARCH_WEB_ID = "7382872326016435738"

    /** `status_code` Douyin uses for "log in before you search". */
    private const val STATUS_LOGIN_REQUIRED = 2483

    /**
     * The room id at the end of a live-room link.
     *
     * Covers the shapes the app and the site produce: `https://live.douyin.com/<id>`,
     * `live.douyin.com/<id>`, an optional query string, and the short `v.douyin.com/<code>`
     * form (whose path is a share code, not a room number, and is passed through as-is — the
     * `enter` call is what decides whether it resolves).
     */
    private val URL_ROOM_ID = Regex(
      """(?:live|webcast|v)\.douyin\.com/(?:share/)?([A-Za-z0-9]+)""",
      RegexOption.IGNORE_CASE,
    )

    /**
     * The label a user types in front of an id.
     *
     * Stripped before the "is anything left besides the number?" check, because 房间号 is a
     * word — treating it like punctuation would mean treating every letter as punctuation, and
     * then a nickname would parse as an id.
     */
    private val NUMBER_LABEL = Regex("""房间号\s*[:：]?|直播间\s*[:：]?|room\s*id\s*[:：]?""", RegexOption.IGNORE_CASE)

    /**
     * Longest string still treated as a web_rid.
     *
     * Measured ids are 6–20 characters (`AYM0707`, `1002683794`); the cap only has to be long
     * enough for a real one and short enough that a long sentence is not mistaken for an id.
     */
    private const val MAX_WEB_RID_LENGTH = 32
    const val PAGE_SIZE = 20
  }
}
