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
   * One page of live rooms for a category.
   *
   * Uses `gapi/rkc/directory/mixListV1/2_<cate2Id>/<page>`, the endpoint the web client
   * itself calls. It answers `{"code":0,"data":{"rl":[...]}}` with 40 rooms per page and
   * paginates properly: verified page 1 vs 2 vs 3 share zero room ids.
   *
   * Earlier revisions scraped `www.douyu.com/directory/game/<slug>` because the *mobile*
   * `newRecList` endpoint had been retired, and then reported "single page only" because
   * the scraped HTML ignored `?page=`. That whole detour is unnecessary — mixListV1 was
   * simply being called with wrong category ids (`2_86` instead of `2_1`).
   *
   * @param categoryId `cate2Id` for a two level browse, or `cate3Id` when [level] is 3.
   * @param level 2 for a category, 3 for a sub-category.
   */
  suspend fun rooms(
    categoryId: String,
    page: Int,
    limit: Int,
    slug: String? = null,
    level: Int = 2,
  ): DouyuRoomPage {
    val currentPage = if (page <= 0) 1 else page
    val prefix = if (level == 3) "3" else "2"
    val url = "$URL_MIX_LIST/$prefix" + "_" + categoryId + "/" + currentPage + "?limit=" + limit

    val response = http.get(
      url,
      headers = mapOf(
        "User-Agent" to HttpClients.UA_DESKTOP,
        "Referer" to URL_WEB,
        "Accept" to "application/json, text/plain, */*",
      ),
    ).requireSuccess()

    val root = response.parseObject("mixListV1")
    val code = root["code"]?.jsonPrimitive?.intOrNull ?: -1
    if (code != 0) {
      throw PlatformProtocolException(
        "斗鱼分区列表加载失败（code=$code）",
        Platform.DOUYU,
      )
    }

    val list = root["data"]?.jsonObject?.get("rl")?.jsonArray ?: JsonArray(emptyList())
    val rooms = list.mapNotNull { DouyuParser.mixListRoom(it) }

    // `limit` is honoured server side, so a short page means the category is exhausted.
    val hasMore = rooms.size >= limit
    return DouyuRoomPage(
      rooms = rooms,
      nextOffset = if (hasMore) currentPage + 1 else null,
    )
  }

  /**
   * Sub-categories ("三级分类") for a `cate2Id`.
   *
   * `capi/v1/getThreeCate?tag_id=<cate2Id>` returns items shaped `{"id":"3683",
   * "name":"S15"}` — note the flat `id`/`name` keys, not `tagId`/`tagName`/`cateId`.
   * Rooms for a sub-category come from `mixListV1/3_<id>/<page>`.
   */
  suspend fun subCategories(categoryId: String): List<Category> {
    val response = http.get(
      "$URL_THREE_CATE?tag_id=" + categoryId + "&client_sys=android",
      headers = mapOf("User-Agent" to HttpClients.UA_DESKTOP),
    )
    val root = runCatching { response.requireSuccess().parseObject("getThreeCate") }.getOrNull()
      ?: return emptyList()
    val items = root["data"] as? JsonArray ?: return emptyList()

    return items.mapNotNull { element ->
      val obj = element as? JsonObject ?: return@mapNotNull null
      val id = obj["id"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: return@mapNotNull null
      val name = obj["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
        ?: return@mapNotNull null
      Category(
        platform = Platform.DOUYU,
        id = "$SUB_CATEGORY_PREFIX$id",
        name = name,
        parentId = categoryId,
        parentName = null,
        slug = null,
        sortOrder = id.toIntOrNull() ?: Int.MAX_VALUE,
      )
    }
  }

  /** Prefix marking a category id as a level-3 sub-category. */
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
    const val URL_CATEGORIES = "https://m.douyu.com/api/cate/list"
    /** Prefix marking a category id as a level-3 sub-category rather than a cate2. */
    const val SUB_CATEGORY_PREFIX = "c3:"
    const val URL_MIX_LIST = "https://www.douyu.com/gapi/rkc/directory/mixListV1"
    const val URL_THREE_CATE = "https://capi.douyucdn.cn/api/v1/getThreeCate"
    const val URL_REC_LIST = "https://m.douyu.com/hgapi/live/cate/newRecList"
    const val URL_SEARCH = "https://www.douyu.com/japi/search/api/searchUser"
    const val URL_BETARD = "https://www.douyu.com/betard"
    const val URL_GET_H5_PLAY = "https://www.douyu.com/lapi/live/getH5Play"

    fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")

    private fun JsonElement?.jsonArraySafe(): JsonArray = (this as? JsonElement)?.jsonArray ?: JsonArray(emptyList())
  }
}
