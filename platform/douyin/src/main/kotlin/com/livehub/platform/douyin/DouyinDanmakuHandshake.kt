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
   * The id appears escaped (`\"user_unique_id\":\"123\"`) in the embedded JSON and
   * occasionally unescaped, so both shapes are tried.
   */
  fun extractUserUniqueId(html: String): String {
    val patterns = listOf(
      Regex("""\\"user_unique_id\\":\\"(\d+)\\""""),
      Regex(""""user_unique_id"\s*:\s*"(\d+)""""),
    )
    patterns.forEach { pattern ->
      pattern.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return ""
  }

  /**
   * Extracts the numeric room id from the room page HTML.
   *
   * The page embeds `\"roomInfo\":{\"roomId\":\"…\",\"web_rid\":\"…\"}`. Verified against the
   * live page on four web_rids: `roomId` is currently an **empty string** every time, so in
   * practice the caller has to fall back to the API. The pattern is kept because the field is
   * the correct one and has carried a value before; an empty result simply means "not in the
   * HTML", which the caller already handles.
   */
  fun extractRoomId(html: String): String {
    val patterns = listOf(
      Regex("""\\"roomInfo\\":\{\\"roomId\\":\\"(\d+)\\""""),
      Regex(""""roomInfo"\s*:\s*\{\s*"roomId"\s*:\s*"(\d+)""""),
    )
    patterns.forEach { pattern ->
      pattern.find(html)?.groupValues?.getOrNull(1)?.takeIf { it.isNotBlank() }?.let { return it }
    }
    return ""
  }

  /** Twelve random digits, the fallback when the page carries no `user_unique_id`. */
  fun randomUserUniqueId(): String {
    val random = java.security.SecureRandom()
    return buildString(12) { repeat(12) { append('0' + random.nextInt(10)) } }
  }
}
