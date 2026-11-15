package com.livehub.platform.douyin

import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import com.livehub.platform.api.PlatformException
import com.livehub.platform.api.PlatformProtocolException
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException

/**
 * A failed request must not be reported as "the broadcaster is not streaming".
 *
 * `enterRoom` used to wrap its HTTP call in `runCatching { … }.getOrNull()`, and an empty body is
 * this endpoint's own signal for "the cookie is stale" — so "the network is down" and "nothing came
 * back" collapsed into the same value. The result: `roomInfo` fell through to its null branch and
 * the room page told the user 该直播间可能未开播 on a phone with no connectivity. Two further shapes
 * had the same fate: an answer that was not JSON at all (a WAF page, a captive portal), and any
 * non-zero `status_code` that was not the documented "no such room".
 *
 * The audience for this file is the next person who reaches for `runCatching` around an HTTP call in
 * an adapter: the *only* thing leniency may swallow here is "the platform answered, and the answer
 * says no".
 *
 * JUnit 5 in this module: assertion messages go **last**.
 */
class DouyinRoomErrorTest {

  private class FakeHttp(private val respond: (url: String) -> HttpResponse) : HttpClient {
    val urls = mutableListOf<String>()

    override suspend fun execute(request: Request): HttpResponse {
      val url = request.url.toString()
      urls += url
      return respond(url)
    }
  }

  private fun api(respond: (url: String) -> HttpResponse) =
    DouyinApi(FakeHttp(respond), InMemoryDouyinCookieStore().apply { save("ttwid=stored") })

  /** The regression: no connectivity is a network failure, not an offline broadcaster. */
  @Test
  fun `a transport failure is not reported as an offline room`() {
    val thrown = assertThrows<IOException> {
      runBlocking {
        api { throw IOException("Failed to connect to live.douyin.com/1.2.3.4:443") }
          .roomInfo("1002683794")
      }
    }

    assertFalse(
      thrown.message.orEmpty().contains("未开播"),
      "a dead network must not be described as an offline broadcaster: ${thrown.message}",
    )
  }

  /** A body that is not JSON is a protocol failure, not a missing room. */
  @Test
  fun `a body that is not json is a protocol failure`() {
    val thrown = assertThrows<PlatformProtocolException> {
      runBlocking { api { HttpResponse(200, "<html>challenge</html>", emptyMap(), "") }.roomInfo("1") }
    }

    assertEquals(PlatformException.Kind.PROTOCOL, thrown.kind)
    assertFalse(
      thrown.message.orEmpty().contains("未开播"),
      "an unreadable answer must not be described as an offline broadcaster: ${thrown.message}",
    )
  }

  /**
   * A refusal that is not "no such room" names the refusal.
   *
   * `4001038` keeps meaning "no room with that id" and still returns null, because that is what
   * lets search say 没有找到 instead of an error. Everything else — a login wall, a risk check, a
   * bad parameter — is the platform refusing the *request*, and it says so with its status.
   */
  @Test
  fun `a non-zero status that is not "not found" names the refusal`() {
    val thrown = assertThrows<PlatformProtocolException> {
      runBlocking {
        api { HttpResponse(200, """{"status_code":2483}""", emptyMap(), "") }.roomInfo("1")
      }
    }

    assertTrue(
      thrown.message.orEmpty().contains("2483"),
      "the refusal has to name its status: ${thrown.message}",
    )
  }

  /** The one status that really does mean "nothing there" keeps returning null. */
  @Test
  fun `a missing room is still a missing room`() {
    val info = runBlocking {
      api { HttpResponse(200, """{"status_code":4001038}""", emptyMap(), "") }.let { a ->
        // `roomInfo` turns null into a message; the null itself is what search relies on, so ask
        // through `search` the way the search path does.
        a.search("1002683794", page = 1)
      }
    }

    assertTrue(info.first.isEmpty(), "an unknown id yields no cards: ${info.first}")
    assertFalse(info.second, "and no second page")
  }
}
