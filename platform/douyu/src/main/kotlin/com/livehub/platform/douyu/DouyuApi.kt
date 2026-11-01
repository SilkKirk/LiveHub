package com.livehub.platform.douyu

import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.PlaybackSource
import com.livehub.core.model.RoomPage
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpClients
import com.livehub.core.network.HttpResponse
import com.livehub.core.network.Crypto
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

/**
 * Douyu API client: pure request building and response parsing.
 *
 * Splitting this from [DouyuAdapter] makes every mapping unit-testable offline:
 * tests feed recorded JSON and assert on the resulting models.
 */
class DouyuApi(private val http: HttpClient) {

  internal val json: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    coerceInputValues = true
  }

  // ------------------------------------------------------------------ categories

  /** `GET /api/cate/list` — the two level category tree. */
  suspend fun categories(): List<Category> {
    val response = http.get(URL_CATEGORIES)
    val root = response.requireSuccess().parseObject("cate/list")
    val cate1 = root["data"]?.jsonObject?.get("cate1Info")?.jsonArray ?: JsonArray(emptyList())
    val cate2 = root["data"]?.jsonObject?.get("cate2Info")?.jsonArray ?: JsonArray(emptyList())
    return DouyuParser.categories(cate1, cate2)
  }

  // ---------------------------------------------------------------------- rooms

  /**
   * Live rooms for a category.
   *
   * The legacy JSON endpoints were retired (`newRecList` answers
   * `{"error":1,"msg":"系统异常"}` for every category), so the directory page is scraped;
   * it renders 40 rooms server side.
   *
   * `?page=N` is accepted but unreliable — page 2 differs only slightly from page 1
   * (38 of 40 rooms overlap) and page 3 returns page 1 again. The adapter therefore
   * reports no next cursor instead of serving duplicates to an infinite-scroll list.
   */
  /**
   * @param slug the category's real directory slug (`shortName` from `cate/list`).
   *   Required: the numeric `cate2Id` is not a valid URL segment and 404s.
   */
  suspend fun rooms(
    categoryId: String,
    page: Int,
    limit: Int,
    slug: String? = null,
  ): DouyuRoomPage {
    val html = fetchDirectoryPage(categoryId, page, slug)
    val rooms = DouyuHtmlParser.roomsFromDirectory(html, limit)
    return DouyuRoomPage(rooms = rooms, nextOffset = null)
  }

  /**
   * Loads the directory page for [categoryId].
   *
   * Page 1 uses the canonical slug URL; later pages try the `?page=` form and accept it
   * only when Douyu actually returns different rooms, otherwise the caller gets page 1
   * rather than a duplicate list.
   */
  private suspend fun fetchDirectoryPage(categoryId: String, page: Int, slug: String?): String {
    val resolved = DouyuCategorySlug.forId(categoryId, slug)
      ?: throw PlatformOfflineException(
        "该分区暂不支持浏览，请选择其他分区",
        Platform.DOUYU,
      )
    val candidates = buildList {
      if (page <= 1) {
        add("$URL_DIRECTORY/$resolved")
      } else {
        add("$URL_DIRECTORY/$resolved?page=$page")
      }
      add("$URL_WEB/directory/game/$resolved")
      add("$URL_WEB/g_$resolved")
    }

    var lastError: Throwable? = null
    for (url in candidates.distinct()) {
      val html = runCatching {
        http.get(
          url,
          headers = mapOf(
            "User-Agent" to HttpClients.UA_DESKTOP,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "zh-CN,zh;q=0.9",
            "Referer" to URL_WEB,
          ),
        ).requireSuccess().body
      }.getOrElse { error ->
        lastError = error
        continue
      }
      if (DouyuHtmlParser.roomsFromDirectory(html).isNotEmpty()) return html
    }

    throw PlatformProtocolException(
      "斗鱼分区列表加载失败，请稍后重试",
      Platform.DOUYU,
      lastError,
    )
  }

  /** Legacy directory listing, kept as a secondary source and for tests. */
  suspend fun directory(categoryId: String, page: Int, limit: Int): DouyuRoomPage {
    val url = "$URL_MIX_LIST/2_${encode(categoryId)}/$page?limit=$limit"
    val response = http.get(
      url,
      headers = mapOf("Referer" to URL_WEB, "User-Agent" to HttpClients.UA_DESKTOP),
    )
    val root = response.requireSuccess().parseObject("mixListV1")
    return DouyuParser.mixList(root, page = page)
  }

  // --------------------------------------------------------------------- search

  /** `GET /japi/search/api/searchUser` — anchors matching [keyword]. */
  suspend fun search(keyword: String, page: Int, pageSize: Int): DouyuRoomPage {
    val did = DouyuSigner.randomDid()
    val url = "$URL_SEARCH?kw=${encode(keyword)}&page=$page&pageSize=$pageSize&filterType=0"
    val response = http.get(
      url,
      headers = mapOf(
        "Referer" to "https://www.douyu.com/search/",
        "Cookie" to "dy_did=$did; acf_did=$did",
        "User-Agent" to HttpClients.UA_DESKTOP,
      ),
    )
    val root = response.requireSuccess().parseObject("searchUser")
    return DouyuParser.search(root, page = page, pageSize = pageSize)
  }

  // --------------------------------------------------------------------- detail

  /** `GET /betard/{roomId}` — room metadata; doubles as the live check. */
  suspend fun betard(roomId: String): DouyuRoomInfo {
    val response = http.get(
      "$URL_BETARD/$roomId",
      headers = mapOf(
        "Referer" to "https://www.douyu.com/$roomId",
        "User-Agent" to HttpClients.UA_DESKTOP,
      ),
    )
    val root = response.requireSuccess().parseObject("betard")
    return DouyuParser.betard(root)
  }

  // ------------------------------------------------------------------- playback

  /**
   * Resolves the playable FLV/HLS URL.
   *
   * Douyu requires a per-request signature produced by JavaScript served from
   * `/swf_api/homeH5Enc`. [DouyuSigner] reimplements that algorithm on the JVM so the
   * app does not need a JS engine.
   */
  suspend fun playback(roomId: String, preferredQuality: Int?): PlaybackSource {
    val info = betard(roomId)
    if (!info.isLive) throw PlatformOfflineException("主播未开播", Platform.DOUYU)

    val signData = sign(roomId)
    val realRoomId = info.realRoomId

    // The probe response advertises every selectable rate. Each rate needs its own
    // signed request, so only the rates we actually offer are resolved — asking for a
    // dozen rates up front would triple the latency of opening a stream.
    val probe = requestPlay(realRoomId = realRoomId, roomId = roomId, signData = signData, cdn = "", rate = -1)
    val selection = DouyuQuality.select(probe, preferredQuality)
    val resolved = resolveVariants(
      realRoomId = realRoomId,
      roomId = roomId,
      signData = signData,
      probe = probe,
      selection = selection,
    )
    if (resolved.isEmpty()) {
      throw PlatformProtocolException("斗鱼未解析到播放地址", Platform.DOUYU)
    }

    val ordered = resolved.sortedBy { it.qualityRank }
    val default = ordered.firstOrNull { it.qualityCode == selection.rate } ?: ordered.first()
    return PlaybackSource(room = info.toStreamer(), variants = ordered, defaultVariant = default)
  }

  /**
   * Resolves one URL per CDN rate, falling back to the probed rate when a follow-up
   * request fails.
   *
   * Douyu occasionally answers a specific `rate` with an error even though the probe
   * succeeded, and a failed quality must never take the whole player down.
   */
  private suspend fun resolveVariants(
    realRoomId: String,
    roomId: String,
    signData: String,
    probe: DyPlayResponse,
    selection: DouyuQuality.Selection,
  ): List<StreamVariant> {
    val primary = if (selection.rate == -1) {
      probe
    } else {
      runCatching {
        requestPlay(
          realRoomId = realRoomId,
          roomId = roomId,
          signData = signData,
          cdn = selection.cdn,
          rate = selection.rate,
        )
      }.getOrDefault(probe)
    }

    return DouyuQuality.variants(primary, selection).ifEmpty {
      DouyuQuality.variants(probe, DouyuQuality.select(probe, null))
    }
  }

  /** Computes the `signData` blob (`key=value&...`) for a room. */
  internal suspend fun sign(roomId: String, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
    val realRoomId = betard(roomId).realRoomId
    return DouyuSigner.sign(realRoomId = realRoomId, did = DouyuSigner.DEFAULT_DID, tt = nowSeconds)
  }

  private suspend fun requestPlay(
    realRoomId: String,
    roomId: String,
    signData: String,
    cdn: String,
    rate: Int,
  ): DyPlayResponse {
    val body = "$signData&cdn=${encode(cdn)}&rate=$rate"
    val response: HttpResponse = http.postForm(
      "$URL_GET_H5_PLAY/$realRoomId",
      body = body,
      headers = mapOf(
        "Referer" to "https://www.douyu.com/$roomId",
        "User-Agent" to HttpClients.UA_DESKTOP,
      ),
    )
    val root = response.requireSuccess().parseObject("getH5Play")
    return DouyuParser.play(root)
  }

  // -------------------------------------------------------------------- helpers

  internal fun JsonObject.intField(name: String): Int? =
    (this[name] as? JsonElement)?.jsonPrimitive?.intOrNull

  private fun HttpResponse.parseObject(label: String): JsonObject = try {
    json.parseToJsonElement(body).jsonObject
  } catch (error: Exception) {
    throw PlatformProtocolException(
      "$label 返回了无法解析的内容 (HTTP $code)",
      Platform.DOUYU,
      error,
    )
  }

  companion object {
    const val URL_WEB = "https://www.douyu.com"
    const val URL_DIRECTORY = "https://www.douyu.com/directory/game"
    const val URL_CATEGORIES = "https://m.douyu.com/api/cate/list"
    const val URL_REC_LIST = "https://m.douyu.com/hgapi/live/cate/newRecList"
    const val URL_MIX_LIST = "https://www.douyu.com/gapi/rkc/directory/mixListV1"
    const val URL_SEARCH = "https://www.douyu.com/japi/search/api/searchUser"
    const val URL_BETARD = "https://www.douyu.com/betard"
    const val URL_GET_H5_PLAY = "https://www.douyu.com/lapi/live/getH5Play"

    fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun JsonElement?.jsonArraySafe(): JsonArray = (this as? JsonElement)?.jsonArray ?: JsonArray(emptyList())
  }
}
