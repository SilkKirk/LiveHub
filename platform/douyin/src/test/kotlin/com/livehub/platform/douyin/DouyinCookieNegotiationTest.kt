package com.livehub.platform.douyin

import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Douyin's `ttwid` must be negotiated, not shipped.
 *
 * The bug this guards: `DEFAULT_COOKIE` was a literal issued on 2025-10-21. By the time a
 * user opened their first room a year later, sending it made `room/web/enter` answer with an
 * empty body — so the app showed "该直播间可能未开播". Douyin handed back a fresh `ttwid` in
 * `Set-Cookie`, the OkHttp jar stored it, and the *second* room-open worked. That is exactly
 * the reported "first one fails, open another room and it works" behaviour.
 */
class DouyinCookieNegotiationTest {

  /** Records requests and replays a canned response. */
  private class FakeHttp(
    private val setCookies: List<String>,
    private val body: String = "<html></html>",
  ) : HttpClient {
    val requested = mutableListOf<String>()

    override suspend fun execute(request: Request): HttpResponse {
      requested += request.url.toString()
      return HttpResponse(
        code = 200,
        body = body,
        headers = if (setCookies.isEmpty()) emptyMap() else mapOf("Set-Cookie" to setCookies),
        url = request.url.toString(),
      )
    }
  }

  private val ttwid = "ttwid=1%7Cfresh%7C1793595256%7Cdeadbeef"

  @Test
  fun `a negotiated ttwid is used instead of the shipped literal`() = runBlocking {
    val http = FakeHttp(setCookies = listOf("$ttwid; Path=/; Domain=.douyin.com", "UIFID_TEMP=abc; Path=/"))
    val api = DouyinApi(http, DouyinCookieStore.InMemory)

    val header = api.cookieHeader()

    assertEquals(ttwid, header)
    assertFalse(header.contains("1761045455"), "the year-old literal must not be sent")
  }

  @Test
  fun `only the ttwid cookie is kept, not every Set-Cookie`() = runBlocking {
    val http = FakeHttp(setCookies = listOf("UIFID_TEMP=abc; Path=/", "$ttwid; Path=/"))
    val api = DouyinApi(http, DouyinCookieStore.InMemory)

    assertEquals(ttwid, api.cookieHeader())
  }

  @Test
  fun `the value is cached so later calls do not re-negotiate`() = runBlocking {
    val http = FakeHttp(setCookies = listOf(ttwid))
    val api = DouyinApi(http, DouyinCookieStore.InMemory)

    api.cookieHeader()
    api.cookieHeader()
    api.cookieHeader()

    assertEquals(1, http.requested.size, "negotiation must happen once per instance")
  }

  @Test
  fun `a previously stored value skips the network entirely`() = runBlocking {
    val store = InMemoryDouyinCookieStore().apply { save(ttwid) }
    val http = FakeHttp(setCookies = listOf("ttwid=should-not-be-used"))
    val api = DouyinApi(http, store)

    assertEquals(ttwid, api.cookieHeader())
    assertTrue(http.requested.isEmpty(), "a persisted ttwid needs no request")
  }

  @Test
  fun `negotiation persists the value for the next start`() = runBlocking {
    val store = InMemoryDouyinCookieStore()
    val api = DouyinApi(FakeHttp(setCookies = listOf(ttwid)), store)

    api.cookieHeader()

    assertEquals(ttwid, store.header())
  }

  @Test
  fun `a failed negotiation still sends the fallback so the request is not cookieless`() = runBlocking {
    // No Set-Cookie at all: Douyin answers an empty body to a cookieless request, which is
    // indistinguishable from "no rooms", so something has to be sent regardless.
    val api = DouyinApi(FakeHttp(setCookies = emptyList()), DouyinCookieStore.InMemory)

    val header = api.cookieHeader()

    assertEquals(DouyinSigner.FALLBACK_COOKIE, header)
    assertTrue(header.startsWith("ttwid="))
  }
}
