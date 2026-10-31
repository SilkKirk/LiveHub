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

  /** First page of a category, using Douyu's offset based recommendation list. */
  suspend fun rooms(categoryId: String, offset: Int, limit: Int): DouyuRoomPage {
    val url = "$URL_REC_LIST?offset=$offset&cate2=${encode(categoryId)}&limit=$limit"
    val response = http.get(url)
    val root = response.requireSuccess().parseObject("newRecList")
    return DouyuParser.recList(root, offset = offset, limit = limit)
  }

  /** Directory listing (older rooms, page based). Used as a second page source. */
  suspend fun directory(categoryId: String, page: Int, limit: Int): DouyuRoomPage {
    val url = "$URL_MIX_LIST/2_${encode(categoryId)}/$page?limit=$limit"
    val response = http.get(url)
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
    val probe = requestPlay(roomId = roomId, signData = signData, cdn = "", rate = -1)
    val selection = DouyuQuality.select(probe, preferredQuality)

    val final = if (selection.rate != -1) {
      requestPlay(roomId = roomId, signData = signData, cdn = selection.cdn, rate = selection.rate)
    } else {
      probe
    }

    val variants = DouyuQuality.variants(final, selection)
    if (variants.isEmpty()) {
      throw PlatformProtocolException("斗鱼未解析到播放地址", Platform.DOUYU)
    }
    return PlaybackSource(room = info.toStreamer(), variants = variants, defaultVariant = variants.first())
  }

  /** Computes the `signData` blob (`key=value&...`) for a room. */
  internal suspend fun sign(roomId: String, nowSeconds: Long = System.currentTimeMillis() / 1000): String {
    val realRoomId = betard(roomId).realRoomId
    return DouyuSigner.sign(realRoomId = realRoomId, did = DouyuSigner.DEFAULT_DID, tt = nowSeconds)
  }

  private suspend fun requestPlay(
    roomId: String,
    signData: String,
    cdn: String,
    rate: Int,
  ): DyPlayResponse {
    val body = "$signData&cdn=${encode(cdn)}&rate=$rate"
    val response: HttpResponse = http.postForm(
      "$URL_GET_H5_PLAY/${betard(roomId).realRoomId}",
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
