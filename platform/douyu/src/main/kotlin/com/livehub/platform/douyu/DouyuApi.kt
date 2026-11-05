package com.livehub.platform.douyu

import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.PlaybackSource
import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpClients
import com.livehub.core.network.HttpResponse
import com.livehub.platform.api.PlatformOfflineException
import com.livehub.platform.api.PlatformProtocolException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Douyu API client: pure request building and response parsing.
 *
 * Splitting this from [DouyuAdapter] makes every mapping unit-testable offline:
 * tests feed recorded JSON and assert on the resulting models.
 */
class DouyuApi(
  private val http: HttpClient,
  /**
   * Evaluates the site signing script. The app supplies it because it owns the JS engine
   * and the bundled crypto library; tests leave it null and exercise the fallback.
   */
  private val scriptSigner: DouyuScriptSigner? = null,
) {

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
   * Uses `gapi/rkc/directory/mixListV1/<level>_<id>/<page>`, the endpoint the web client
   * itself calls. It answers `{"code":0,"data":{"rl":[...]}}` with 40 rooms per page and
   * paginates properly: verified page 1 vs 2 vs 3 share zero room ids.
   *
   * Earlier revisions scraped `www.douyu.com/directory/game/<slug>` because the *mobile*
   * `newRecList` endpoint had been retired, and then reported "single page only" because
   * the scraped HTML ignored `?page=`. That whole detour is unnecessary — mixListV1 was
   * simply being called with wrong category ids (`2_86` instead of `2_1`).
   *
   * @param categoryId `cate2Id` for a two level browse, `cate3Id` when [level] is 3, or
   *   [ALL_CATEGORY_ID] for the cross-group feed.
   * @param level 2 for a category, 3 for a sub-category. Ignored for [ALL_CATEGORY_ID].
   */
  suspend fun rooms(
    categoryId: String,
    page: Int,
    limit: Int,
    level: Int = 2,
  ): DouyuRoomPage {
    val currentPage = if (page <= 0) 1 else page
    // `0_0` is what douyu.com/directory/all itself calls for 全部分类: one feed spanning
    // every 大区, paginated exactly like a category (verified: pages 1..3 share no room id,
    // page 50 still answers 40 rooms, page 200 is empty).
    val segment = when {
      categoryId == ALL_CATEGORY_ID -> "0_0"
      level == 3 -> "3_$categoryId"
      else -> "2_$categoryId"
    }
    val url = "$URL_MIX_LIST/$segment/$currentPage?limit=$limit"

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

    // `mixListV1` returns no total and no page count, so an empty `data.rl` is the only
    // end-of-list signal (`douyu_spec.md` §1.4). Counting parsed rooms against `limit`
    // — what this used to do — is wrong twice over:
    //  * the server picks the page size itself and ignores `limit`; the recorded
    //    `?limit=20` capture in `fixtures/douyu-mixlistv1.json` came back with 40 rooms,
    //    so comparing against the requested 30 was always comparing unrelated numbers;
    //  * `rooms` is the *mapped* list, and [DouyuParser.mixListRoom] drops entries with a
    //    blank or zero `rid`, so one bad row made a full page look short and silently
    //    ended the list — the grid said "没有更多了" with the rest of the category unread.
    val hasMore = list.isNotEmpty()
    return DouyuRoomPage(
      rooms = rooms,
      nextOffset = if (hasMore) (currentPage + 1).toString() else null,
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

  /**
   * Resolves playable variants for a room, one per advertised quality.
   *
   * The probe answers with every selectable rate (`原画720P30:0 | 超清:3 | 高清:2` on a
   * live room), but each rate needs its own signed `getH5Play` call before it has a URL.
   * Previously only the single selected rate was resolved, so the player's quality row
   * always contained exactly one chip and the setting was unreachable — the menu existed
   * but could not change anything. Now every advertised rate is resolved (in parallel, so
   * the extra latency is one round trip rather than five), which makes the existing
   * quality selector work without any extra UI plumbing.
   *
   * @param preferredCdn explicit line to resolve, or null for the preferred one. The
   *   response advertises its own line list, which the UI shows next to the qualities.
   */
  suspend fun playback(
    roomId: String,
    preferredQuality: Int?,
    preferredCdn: String? = null,
  ): PlaybackSource {
    val info = betard(roomId)
    if (!info.isLive) throw PlatformOfflineException("主播未开播", Platform.DOUYU)

    val realRoomId = info.realRoomId
    val signData = sign(realRoomId = realRoomId, roomId = roomId)

    val probe = requestPlay(realRoomId = realRoomId, roomId = roomId, signData = signData, cdn = "", rate = -1)
    val selection = DouyuQuality.select(probe, preferredQuality)
    val cdn = DouyuQuality.selectCdn(probe.cdns, preferredCdn)

    // One request per distinct rate. The probe's own rate is reused instead of re-asking
    // for it, which saves a round trip in the common "auto" case.
    val wantedRates = DouyuQuality.rateOptions(probe).map { it.rate }.distinct().ifEmpty { listOf(selection.rate) }
    val resolved = coroutineScope {
      wantedRates.map { rate ->
        async(Dispatchers.IO) {
          if (rate == selection.rate && selection.cdn == cdn) {
            DouyuQuality.variants(probe, selection)
          } else {
            runCatching {
              val response = requestPlay(
                realRoomId = realRoomId,
                roomId = roomId,
                signData = signData,
                cdn = cdn,
                rate = rate,
              )
              val label = DouyuQuality.rateOptions(probe).firstOrNull { it.rate == rate }?.name
                ?.ifBlank { DouyuQuality.labelFor(rate) }
                ?: DouyuQuality.labelFor(rate)
              DouyuQuality.variants(response, DouyuQuality.Selection(rate = rate, rateLabel = label, cdn = cdn))
            }.getOrDefault(emptyList())
          }
        }
      }.awaitAll().flatten()
    }

    val variants = resolved
      .distinctBy { it.url }
      .ifEmpty { DouyuQuality.variants(probe, selection) }
      .sortedBy { it.qualityRank }

    if (variants.isEmpty()) {
      throw PlatformProtocolException("斗鱼未解析到播放地址", Platform.DOUYU)
    }
    val default = variants.firstOrNull { it.qualityCode == selection.rate } ?: variants.first()
    return PlaybackSource(
      room = info.toStreamer(),
      variants = variants,
      defaultVariant = default,
      lines = probe.cdns,
      selectedLine = cdn,
    )
  }
  /**
   * Computes the `signData` blob (`key=value&...`) for a room.
   *
   * Douyu rotates the signature algorithm, and the fixed digest computed here used to be
   * accepted and is now rejected with HTTP 403. When a [scriptSigner] is available the
   * site script is evaluated instead, which keeps working across rotations; the pure
   * Kotlin formula remains as the fallback so signing still succeeds without a JS engine.
   */
  internal suspend fun sign(
    realRoomId: String,
    roomId: String,
    nowSeconds: Long = System.currentTimeMillis() / 1000,
  ): String {
    val signer = scriptSigner
    if (signer != null) {
      val script = runCatching { homeH5EncScript(realRoomId) }.getOrNull()
      if (!script.isNullOrBlank()) {
        val signed = runCatching {
          signer.sign(
            script = script,
            roomId = realRoomId,
            did = DouyuSigner.DEFAULT_DID,
            tsSeconds = nowSeconds,
          )
        }.getOrNull()
        if (!signed.isNullOrBlank()) return signed
      }
    }
    return DouyuSigner.sign(realRoomId = realRoomId, did = DouyuSigner.DEFAULT_DID, tt = nowSeconds)
  }

  /** Fetches the current signing script source for [realRoomId]. */
  private suspend fun homeH5EncScript(realRoomId: String): String? {
    val response = http.get(
      "$URL_HOME_H5_ENC?rids=$realRoomId",
      headers = mapOf(
        "Referer" to "https://www.douyu.com/$realRoomId",
        "User-Agent" to HttpClients.UA_DESKTOP,
      ),
    )
    val root = DouyuResponseRules.requireJson(response.body, response.code, "homeH5Enc")
    return DouyuSigner.parseEncSource(root, realRoomId)
  }

  private suspend fun requestPlay(
    realRoomId: String,
    roomId: String,
    signData: String,
    cdn: String,
    rate: Int,
  ): DyPlayResponse {
    // `ver` / `iar` / `ive` / `hevc` / `fa` are part of the payload the web client sends.
    // Omitting them makes getH5Play answer HTTP 403 on a valid signature.
    val body = buildString {
      append(signData)
      append("&cdn=").append(encode(cdn))
      append("&rate=").append(rate)
      append("&ver=").append(encode(WEB_VERSION))
      append("&iar=1&ive=1&hevc=0&fa=0")
    }
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

  /** Delegates to [DouyuResponseRules]; see there for why HTML needs translating. */
  private fun HttpResponse.parseObject(label: String): JsonObject =
    DouyuResponseRules.requireJson(body = body, code = code, label = label)

  companion object {
    /** Douyu web client build; getH5Play rejects a payload without it. */
    const val WEB_VERSION = "Douyu_223061205"
    const val URL_WEB = "https://www.douyu.com"
    const val URL_CATEGORIES = "https://m.douyu.com/api/cate/list"
    const val URL_HOME_H5_ENC = "https://www.douyu.com/swf_api/homeH5Enc"
    /** Prefix marking a category id as a level-3 sub-category rather than a cate2. */
    const val SUB_CATEGORY_PREFIX = "c3:"
    /** Marker for the cross-group feed; see [rooms]. */
    const val ALL_CATEGORY_ID = Category.ALL_ID
    const val URL_MIX_LIST = "https://www.douyu.com/gapi/rkc/directory/mixListV1"
    const val URL_THREE_CATE = "https://capi.douyucdn.cn/api/v1/getThreeCate"
    const val URL_SEARCH = "https://www.douyu.com/japi/search/api/searchUser"
    const val URL_BETARD = "https://www.douyu.com/betard"
    const val URL_GET_H5_PLAY = "https://www.douyu.com/lapi/live/getH5Play"

    fun encode(value: String): String = java.net.URLEncoder.encode(value, "UTF-8")
  }
}
