package com.livehub.platform.douyin

/**
 * Douyin web client identifiers and query assembly.
 *
 * The signatures themselves live in [DouyinABogus] (pure Kotlin, transcribed verbatim from
 * the reference implementation) and in the app's `DouyinWebMsdkSigner`, which evaluates the
 * site's own `webmssdk.js` for the danmaku handshake.
 *
 * An earlier revision also carried a locally invented `xBogus`/`aBogus` pair in this
 * object. `rooms()` happened to call the real [DouyinABogus] while `search`, `roomInfo` and
 * `findInFeed` called the invented one, so three of four endpoints sent a signature the
 * server cannot validate. Those functions are gone: there is now exactly one `a_bogus`
 * implementation in the module, so the wrong one cannot be picked by accident.
 */
object DouyinSigner {

  /** Browser version echoed in requests; Douyin cross-checks it against the UA. */
  const val BROWSER_VERSION = "116.0.0.0"

  /**
   * Web user agent, matching the reference client byte for byte.
   *
   * Douyin binds the signature to the UA, so this string and the `browser_version`
   * parameter have to agree.
   */
  const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; WOW64) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/116.0.5845.97 Safari/537.36 " +
      "Core/1.116.567.400 QQBrowser/19.7.6764.400"

  /**
   * Default `ttwid` cookie.
   *
   * The partition and feed endpoints answer HTTP 200 with an empty body when the request
   * carries no cookie at all, which is indistinguishable from "no rooms": a request
   * without this header silently looks like an empty category.
   */
  const val DEFAULT_COOKIE =
    "ttwid=1%7C2iDIYVmjzMcpZ20fcaFde0VghXAA3NaNXE_SLR68IyE%7C1761045455" +
      "%7Cab35197d5cfb21df6cbb2fa7ef1c9262206b062c315b9d04da746d0b37dfbc7d"

  /** Length of the anti-bot `msToken` the web client sends. */
  const val MS_TOKEN_LENGTH = 107

  /**
   * A syntactically valid `msToken`.
   *
   * The web client generates this in JavaScript. The server does not verify the contents
   * but the partition endpoint rejects an **empty** value, so a random base62 string of the
   * documented length is what gets sent.
   */
  fun randomMsToken(): String {
    val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789"
    val random = java.security.SecureRandom()
    return buildString(MS_TOKEN_LENGTH) {
      repeat(MS_TOKEN_LENGTH) { append(alphabet[random.nextInt(alphabet.length)]) }
    }
  }

  /**
   * Appends the `a_bogus` parameter to an ordered parameter list.
   *
   * @param orderedParams the parameters **in the exact order they appear in the URL**; the
   *   signature covers that order, so callers must not sort or reorder them.
   */
  fun signQuery(orderedParams: List<Pair<String, String>>): String {
    val query = orderedParams.joinToString("&") { (key, value) -> "$key=${encodeQueryValue(value)}" }
    val bogus = DouyinABogus.aBogus(query, USER_AGENT)
    return "$query&a_bogus=${encodeQueryValue(bogus)}"
  }

  /** Percent-encodes a query value the way the Douyin web client does. */
  fun encodeQueryValue(value: String): String =
    java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
}
