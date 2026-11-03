package com.livehub.platform.douyin

/**
 * Builds the douyin danmaku websocket URL.
 *
 * Douyin's push socket rejects a bare URL: the handshake needs ~30 query parameters **and**
 * a `signature` produced by the site's own `webmssdk.js` over those parameters. An earlier
 * revision connected to `wss://webcast5-ws-web-lf.douyin.com/webcast/im/push/v2/` with no
 * query string at all, which can never handshake — Douyin chat was therefore never
 * reachable, no matter how correct the protobuf decoding became.
 *
 * This object keeps the pure part (parameter assembly + stub input) separate from the JS
 * engine, so the whole shape is covered by JVM unit tests.
 */
object DouyinDanmakuHandshake {

  /** The three known push hosts; the reference client shuffles them per attempt. */
  val WS_BASES: List<String> = listOf(
    "wss://webcast5-ws-web-hl.douyin.com/webcast/im/push/v2/?",
    "wss://webcast3-ws-web-lq.douyin.com/webcast/im/push/v2/?",
    "wss://webcast5-ws-web-lf.douyin.com/webcast/im/push/v2/?",
  )

  const val VERSION_CODE = "180800"
  const val WEBCAST_SDK_VERSION = "1.3.0"
  const val UPDATE_VERSION_CODE = "1.3.0"
  const val HEARTBEAT_INTERVAL_MILLIS = 10_000L

  /** Everything the URL needs that is not a constant. */
  data class RoomInit(
    /** The **numeric** room id (`room.id_str`), not the URL's `web_rid`. */
    val roomId: String,
    val userUniqueId: String,
    val cookieHeader: String,
  )

  /**
   * Assembles the query string (without `signature`) for [init].
   *
   * @param timestamp used for the `cursor` field, which the web client seeds with the
   *   current time; injectable so the output can be asserted in tests.
   */
  fun query(init: RoomInit, timestamp: Long = System.currentTimeMillis()): String {
    val userAgent = DouyinSigner.USER_AGENT
    return listOf(
      "app_name=douyin_web",
      "version_code=$VERSION_CODE",
      "webcast_sdk_version=$WEBCAST_SDK_VERSION",
      "update_version_code=$UPDATE_VERSION_CODE",
      "compress=gzip",
      "cursor=${encode("h-1_t-$timestamp" + "_r-1_d-1_u-1")}",
      "host=${encode("https://live.douyin.com")}",
      "aid=6383",
      "live_id=1",
      "did_rule=3",
      "debug=false",
      "maxCacheMessageNumber=20",
      "endpoint=live_pc",
      "support_wrds=1",
      "im_path=${encode("/webcast/im/fetch/")}",
      "user_unique_id=${init.userUniqueId}",
      "device_platform=web",
      "cookie_enabled=true",
      "screen_width=1920",
      "screen_height=1080",
      "browser_language=zh-CN",
      "browser_platform=Win32",
      "browser_name=Mozilla",
      "browser_version=${encode(userAgent.removePrefix("Mozilla/"))}",
      "browser_online=true",
      "tz_name=Asia/Shanghai",
      "identity=audience",
      "room_id=${init.roomId}",
      "heartbeatDuration=0",
    ).joinToString("&")
  }

  /**
   * Full URL for [base].
   *
   * @param signature the token from the JS signer; a blank token yields null because a URL
   *   without `signature` is rejected and retrying the socket is pointless.
   */
  fun buildUrl(base: String, init: RoomInit, signature: String?, timestamp: Long = System.currentTimeMillis()): String? {
    val token = signature?.trim().orEmpty()
    if (token.isEmpty()) return null
    return base + query(init, timestamp) + "&signature=" + encode(token)
  }

  private fun encode(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

  /**
   * Extracts `user_unique_id` from the room page HTML.
   *
   * The page ships the same payload in several encodings at once: escaped inside a script
   * string (`\"user_unique_id\":\"…\"`), HTML-entity encoded inside a data attribute
   * (`&quot;user_unique_id&quot;:&quot;…&quot;`) and occasionally as plain JSON. Carrying
   * one pattern per spelling — the previous approach — reads only the spellings somebody
   * thought of, so the page is normalised once ([plainJson]) and matched tolerantly.
   *
   * The value is matched with the quotes optional because nothing pins the encoding: a bare
   * number is legal JSON, and the previous patterns demanded a closing quote, which no valid
   * JSON number has.
   */
  fun extractUserUniqueId(html: String): String =
    USER_UNIQUE_ID.find(plainJson(html))?.groupValues?.getOrNull(1).orEmpty()

  /**
   * Extracts the numeric room id from the page's `roomInfo` block.
   *
   * Read from `roomInfo.roomId` — and deliberately **not** from the `id_str` next to it. On
   * the live page (`https://live.douyin.com/164485212`, fetched 2026-11-03) that block reads
   * `{"roomId":"","web_rid":"164485212","anchor":{"id_str":"63734335401",…}`, i.e. the only
   * `id_str` there is the anchor's *user* id. The reference client's unanchored
   * `"id_str"\s*:\s*"(\d+)"` would therefore hand the socket a user id, and on the same page
   * the first `id_str` of all belongs to a partition (`\"id_str\":\"105\"` under
   * `recommendCategoryData`). Only `roomInfo.roomId` is the room, so that is the only field
   * read here.
   *
   * An empty result is the normal case today (the field is sent empty for anonymous
   * visitors); the caller then asks `enter` for the id instead. The pattern is kept because
   * the field is the correct one and has carried a value before.
   */
  fun extractRoomId(html: String): String =
    ROOM_ID.find(plainJson(html))?.groupValues?.getOrNull(1).orEmpty()

  /**
   * Collapses the page's quote encodings onto plain JSON quotes.
   *
   * A page carrying a room title with an escaped quote inside a JSON string would be
   * rewritten too, but the two keys matched below cannot plausibly appear in a title.
   */
  private fun plainJson(html: String): String =
    html.replace("&quot;", "\"").replace("\\\"", "\"")

  /** A visitor id is 19 digits in practice; the floor keeps a stray `0` out. */
  private val USER_UNIQUE_ID = Regex(""""user_unique_id"\s*:\s*"?(\d{6,})"?""")

  /**
   * `roomInfo.roomId`, within a bounded window so a missing field cannot pick up an
   * unrelated `roomId` from the rest of the payload.
   */
  private val ROOM_ID = Regex(
    """"roomInfo"\s*:\s*\{.{0,300}?"roomId"\s*:\s*"?(\d{6,})"?""",
    RegexOption.DOT_MATCHES_ALL,
  )

  /** Twelve random digits, the fallback when the page carries no `user_unique_id`. */
  fun randomUserUniqueId(): String {
    val random = java.security.SecureRandom()
    return buildString(12) { repeat(12) { append('0' + random.nextInt(10)) } }
  }
}
