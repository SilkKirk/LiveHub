package com.livehub.platform.douyin

import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import com.livehub.platform.api.PlatformAuthRequiredException
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Douyin search is one room number, resolved through `room/web/enter`.
 *
 * Same shape as the reference client (`AndroidDtvRepository.searchAnchors`, the
 * `Platform.Douyin` branch): call `fetchRoomEnter` with the typed id and return one card whose
 * id is that text. The screen used to call `live.douyin.com/webcast/search/partition/room/`
 * instead and print its `HTTP 404` verbatim — that path does not exist on any Douyin host
 * (measured 2026-11-04), and the keyword endpoint that does exist refuses anonymous callers
 * with `2483 请先登录，再继续搜索吧`.
 */
class DouyinSearchTest {

  /** Serves a canned body per URL and records every request. */
  private class FakeDouyinHttp(
    private val respond: (url: String) -> HttpResponse,
  ) : HttpClient {
    val urls = mutableListOf<String>()

    override suspend fun execute(request: Request): HttpResponse {
      val url = request.url.toString()
      urls += url
      return respond(url)
    }
  }

  private fun ok(body: String): HttpResponse = HttpResponse(200, body, emptyMap(), "")

  /**
   * A `room/web/enter` body in the shape the API reads: the room under `data.data[0]` with its
   * internal `id_str`, the anchor under `data.user`. An empty `stream_url` is what an offline
   * room returns — the same state the room in the report was in.
   */
  private fun enter(
    idStr: String = "7703900142109592362",
    title: String = "我的红眼看了不烧心！！",
    nickname: String = "红眼阿旺",
  ): String = """
    {
      "status_code": 0,
      "data": {
        "user": {"nickname": "$nickname", "avatar_thumb": {"url_list": ["https://a/avatar.jpg"]}},
        "data": [{
          "id_str": "$idStr",
          "title": "$title",
          "cover": {"url_list": ["https://a/cover.jpg"]},
          "stats": {"total_user": 0},
          "stream_url": {}
        }]
      }
    }
  """.trimIndent()

  /** An `enter` answer for an id nobody is streaming under, plus the empty feed it falls back to. */
  private fun missingRoom(): String = """{"status_code": 0, "data": {"data": []}}"""

  private fun api(http: HttpClient) =
    DouyinApi(http, InMemoryDouyinCookieStore().apply { save("ttwid=seeded") })

  @Test
  fun `a room number resolves to exactly that room`() = runBlocking {
    val http = FakeDouyinHttp { ok(enter()) }

    val (rooms, hasMore) = api(http).search("1002683794", page = 1)

    assertEquals(1, rooms.size, "one id must produce one card")
    assertEquals("1002683794", rooms.single().roomId, "the card keeps the id the user typed")
    assertEquals("红眼阿旺", rooms.single().streamerName)
    assertEquals("我的红眼看了不烧心！！", rooms.single().title)
    assertFalse(rooms.single().isLive, "an empty stream_url means offline, not a failed search")
    assertFalse(hasMore, "one id cannot have a second page")
    assertTrue(http.urls.single().contains("/webcast/room/web/enter/"), http.urls.single())
    assertTrue(http.urls.single().contains("web_rid=1002683794"), http.urls.single())
  }

  @Test
  fun `a keyword is refused with the login reason instead of a 404 url`() {
    val http = FakeDouyinHttp { ok(enter()) }

    val error = assertThrows<PlatformAuthRequiredException> {
      runBlocking { api(http).search("红眼阿旺", page = 1) }
    }

    assertTrue(error.message.orEmpty().contains("登录"), error.message)
    assertTrue(error.message.orEmpty().contains("房间号"), error.message)
    assertTrue(http.urls.isEmpty(), "a keyword must not reach the network at all: ${http.urls}")
  }

  @Test
  fun `a second page asks for nothing`() = runBlocking {
    val http = FakeDouyinHttp { ok(enter()) }

    val (rooms, hasMore) = api(http).search("1002683794", page = 2)

    assertTrue(rooms.isEmpty())
    assertFalse(hasMore)
    assertTrue(http.urls.isEmpty(), "one id has no second page: ${http.urls}")
  }

  @Test
  fun `an unknown room is an empty result, not an error`() = runBlocking {
    val http = FakeDouyinHttp { url ->
      ok(if (url.contains("/enter/")) missingRoom() else """{"data": []}""")
    }

    val (rooms, hasMore) = api(http).search("999999999999999", page = 1)

    assertTrue(rooms.isEmpty())
    assertFalse(hasMore)
  }
}
