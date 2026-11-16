package com.livehub.platform.douyin

import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import com.livehub.platform.api.AssetReader
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.coroutines.cancellation.CancellationException

/**
 * A cancelled call must stop, not fall back.
 *
 * Almost every network call in this adapter sits inside a lenient shape —
 * `runCatching { … }.getOrNull() ?: fallback` — which catches `Throwable` and therefore also catches
 * `CancellationException`. The result was not "an error is hidden" but "**the fallback runs on behalf
 * of nobody**":
 *
 *  * `rooms` requested the recommendation feed after a cancelled partition request, because a
 *    cancellation looked exactly like the signature rejection it was written to recover from;
 *  * `cookieHeader` answered with the shipped literal cookie instead of reporting cancellation, so
 *    the caller then sent a request with a cookie that could not work.
 *
 * `runCatchingCancellable` (in `core:network`) is the one-word fix, and these two tests are the
 * reason it exists. They assert the request count as well as the exception, because the old
 * behaviour on the `rooms` path was to *return a page* — an assertion on the exception alone would
 * not have distinguished the two.
 *
 * JUnit 5 in this module: assertion messages go **last**.
 */
class DouyinCancellationTest {

  private class FakeHttp(private val respond: suspend (String) -> HttpResponse) : HttpClient {
    val urls = mutableListOf<String>()

    override suspend fun execute(request: Request): HttpResponse {
      val url = request.url.toString()
      urls += url
      return respond(url)
    }
  }

  private fun adapter(http: HttpClient): DouyinAdapter = DouyinAdapter(
    http = http,
    webSocketClient = OkHttpClient(),
    api = DouyinApi(http, InMemoryDouyinCookieStore().apply { save("ttwid=seeded") }),
    assets = AssetReader.Empty,
  )

  /** The regression: a cancelled partition request used to request the feed as well. */
  @Test
  fun `a cancelled room page does not fall back to the recommendation feed`() {
    val http = FakeHttp { throw CancellationException("superseded") }

    assertThrows<CancellationException> {
      runBlocking { adapter(http).rooms(categoryId = "1:1", cursor = null, slug = null) }
    }

    assertEquals(
      1,
      http.urls.size,
      "the fallback must not run for a cancelled call: ${http.urls}",
    )
  }

  /**
   * The regression: a cancelled negotiation used to be reported as "no cookie", so the caller went
   * on to send a request anyway.
   */
  @Test
  fun `a cancelled cookie negotiation is not treated as a missing cookie`() {
    val http = FakeHttp { throw CancellationException("superseded") }
    val api = DouyinApi(http, InMemoryDouyinCookieStore())

    assertThrows<CancellationException> {
      runBlocking { api.cookieHeader() }
    }
  }

  /** The lenient half must still be lenient: a plain failure is not allowed to escape. */
  @Test
  fun `an ordinary failure still falls back`() {
    val http = FakeHttp { throw java.io.IOException("connection reset") }

    val page = runBlocking { adapter(http).rooms(categoryId = "1:1", cursor = null, slug = null) }

    assertEquals(2, http.urls.size, "partition then feed: ${http.urls}")
    assertEquals(
      0,
      page.rooms.size,
      "a failed partition shows nothing rather than throwing: ${page.rooms}",
    )
  }
}
