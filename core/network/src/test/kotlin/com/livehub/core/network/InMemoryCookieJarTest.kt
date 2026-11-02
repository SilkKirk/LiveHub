package com.livehub.core.network

import okhttp3.Cookie
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The cookie jar must stay out of Bilibili's way.
 *
 * This guards a defect that only appeared after watching a stream: OkHttp's
 * `BridgeInterceptor` replaces the caller's `Cookie` header with the jar's contents
 * whenever the jar holds anything for that host, and Bilibili's `getRoomInfo` answers
 * `Set-Cookie: LIVE_BUVID=…`. The jar then replayed `LIVE_BUVID` onto `second/getList`,
 * which dropped the `buvid3` marker that endpoint requires, so every category answered
 * `-352` until the process restarted and the in-memory jar was lost.
 *
 * These assertions drive the jar directly rather than through a socket: the replacement
 * happens inside OkHttp, and what LiveHub controls is whether the jar hands anything back.
 */
class InMemoryCookieJarTest {

  private fun cookie(name: String, value: String, host: String): Cookie =
    Cookie.Builder()
      .name(name)
      .value(value)
      .domain(host)
      .path("/")
      .build()

  @Test
  fun `bilibili cookies are never returned, so an explicit header survives`() {
    val jar = InMemoryCookieJar()
    val url = "https://api.live.bilibili.com/xlive/web-interface/v1/second/getList".toHttpUrl()

    jar.saveFromResponse(url, listOf(cookie("LIVE_BUVID", "AUTO5717935942545160", "api.live.bilibili.com")))

    // Empty is the whole point: OkHttp only overwrites the caller's Cookie header when this
    // is non-empty, so an empty list preserves the `buvid3=i;` the request set itself.
    assertTrue(
      jar.loadForRequest(url).isEmpty(),
      "the jar must not supply bilibili cookies; an explicit buvid3 header would be replaced",
    )
  }

  @Test
  fun `every bilibili subdomain is excluded, not just the list host`() {
    val jar = InMemoryCookieJar()
    val hosts = listOf(
      "api.live.bilibili.com",
      "live.bilibili.com",
      "api.bilibili.com",
      "bilibili.com",
    )

    hosts.forEach { host ->
      val url = "https://$host/some/path".toHttpUrl()
      jar.saveFromResponse(url, listOf(cookie("buvid3", "real-value", host)))
      assertTrue(
        jar.loadForRequest(url).isEmpty(),
        "$host must be excluded: its cookies are owned by BilibiliCookieStore",
      )
    }
  }

  @Test
  fun `other platforms still keep their cookies`() {
    val jar = InMemoryCookieJar()
    val url = "https://www.douyu.com/gapi/rkc/directory/mixListV1/2_1/1".toHttpUrl()

    jar.saveFromResponse(url, listOf(cookie("dy_session", "abc", "www.douyu.com")))
    val loaded = jar.loadForRequest(url)

    assertEquals(1, loaded.size)
    assertEquals("dy_session", loaded.first().name)
    assertEquals("abc", loaded.first().value)
  }

  @Test
  fun `a lookalike host that only ends with the string is not confused with bilibili`() {
    val jar = InMemoryCookieJar()
    // "notbilibili.com" ends with "bilibili.com" as a raw string, but is a different
    // domain; the suffix check must require the dot boundary.
    val url = "https://notbilibili.com/x".toHttpUrl()

    jar.saveFromResponse(url, listOf(cookie("k", "v", "notbilibili.com")))

    assertEquals(1, jar.loadForRequest(url).size)
  }
}
