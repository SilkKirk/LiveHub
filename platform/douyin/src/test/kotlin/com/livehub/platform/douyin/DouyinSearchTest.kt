package com.livehub.platform.douyin

import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import com.livehub.platform.api.PlatformAuthRequiredException
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
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

  private fun api(http: HttpClient, session: Boolean = false) =
    DouyinApi(
      http,
      InMemoryDouyinCookieStore().apply {
        save(if (session) "ttwid=seeded; sessionid=seeded-session" else "ttwid=seeded")
      },
    )

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

  /**
   * The keyword path, with a session stored.
   *
   * The response shape (`data[].lives.rawdata` as a JSON string) is the one
   * `dart_simple_live` parses for this endpoint; without a logged-in account there is no way
   * to capture a live sample, so the test pins the parser instead of the wire.
   */
  @Test
  fun `a keyword finds rooms once a session is stored`() = runBlocking {
    val room = """
      {"owner":{"web_rid":"1002683794","nickname":"红眼阿旺"},
       "title":"我的红眼看了不烧心！！",
       "cover":{"url_list":["https://a/cover.jpg"]},
       "stats":{"total_user":1234}}
    """.trimIndent().replace("\n", "")
    val body = """{"status_code":0,"data":[{"lives":{"rawdata":${JsonPrimitive(room)}}}]}"""
    val http = FakeDouyinHttp { ok(body) }

    val (rooms, hasMore) = api(http, session = true).search("红眼阿旺", page = 1)

    assertEquals(1, rooms.size)
    assertEquals("1002683794", rooms.single().roomId)
    assertEquals("红眼阿旺", rooms.single().streamerName)
    assertEquals("我的红眼看了不烧心！！", rooms.single().title)
    assertEquals(1234L, rooms.single().viewerCount)
    assertFalse(hasMore, "ten or fewer hits means there is no next page")
    val url = http.urls.single()
    assertTrue(url.startsWith("https://www.douyin.com/aweme/v1/web/live/search/"), url)
    // The keyword travels percent-encoded, which is what the web client sends.
    assertTrue(url.contains("keyword=%E7%BA%A2%E7%9C%BC%E9%98%BF%E6%97%BA"), url)
  }

  /**
   * The shapes that used to be misread as a keyword.
   *
   * The reported symptom was "抖音的关键词搜索需要登录, 接口返回 2483" while searching by id —
   * that message is thrown *before any network call* whenever the input is not all digits, so
   * the user was told to log in for a room number they had typed. The commonest way to supply
   * an id is to paste the live-room link, which is exactly what fell through.
   */
  @Test
  fun `a pasted live-room link is treated as a room id, not a keyword`() = runBlocking {
    val http = FakeDouyinHttp { ok(enter()) }

    val (rooms, _) = api(http).search("https://live.douyin.com/1002683794", page = 1)

    assertEquals(1, rooms.size, "a link must resolve, not throw the login message")
    assertEquals("1002683794", rooms.single().roomId)
    assertTrue(http.urls.single().contains("web_rid=1002683794"), http.urls.single())
  }

  @Test
  fun `a share text around a link still resolves the id`() = runBlocking {
    val http = FakeDouyinHttp { ok(enter()) }

    val (rooms, _) = api(http).search("看看这个直播间 https://live.douyin.com/1002683794 复制打开", page = 1)

    assertEquals(1, rooms.size)
    assertEquals("1002683794", rooms.single().roomId)
  }

  @Test
  fun `an id with a zero-width space or a full-width prefix still resolves`() = runBlocking {
    listOf("1002\u200B683794", "房间号：1002683794").forEach { raw ->
      val http = FakeDouyinHttp { ok(enter()) }
      val (rooms, _) = api(http).search(raw, page = 1)
      assertEquals("[$raw] must resolve to the room", 1, rooms.size)
      assertEquals("1002683794", rooms.single().roomId)
    }
  }

  /**
   * A real nickname must still reach the keyword path — the id parsing must not swallow it.
   * With no session that path refuses with the login message, which is the correct answer here.
   */
  @Test
  fun `a nickname is not mistaken for an id`() {
    val http = FakeDouyinHttp { ok(enter()) }

    assertThrows<PlatformAuthRequiredException> {
      runBlocking { api(http).search("红眼阿旺", page = 1) }
    }
    assertTrue(http.urls.isEmpty(), "a nickname must not reach the network: ${http.urls}")
  }

  @Test
  fun `the room id parser accepts the documented shapes and refuses names`() {
    val api = api(FakeDouyinHttp { ok(enter()) })

    assertEquals("1002683794", api.roomIdFrom("1002683794"))
    assertEquals("1002683794", api.roomIdFrom("  1002683794  "))
    assertEquals("1002683794", api.roomIdFrom("https://live.douyin.com/1002683794"))
    assertEquals("1002683794", api.roomIdFrom("live.douyin.com/1002683794?from=share"))
    // A CJK keyboard produces full-width digits; they are Unicode category Nd, so they are
    // digits, but they must be folded back to ASCII before being put in a query string.
    assertEquals("1002683794", api.roomIdFrom("１００２６８３７９４"))
    assertEquals("1002683794", api.roomIdFrom("1002\u200B683794"))
    // The label a user types in front of the number is a word, not punctuation.
    assertEquals("1002683794", api.roomIdFrom("房间号：1002683794"))

    assertNull(api.roomIdFrom("红眼阿旺"))
    assertNull(api.roomIdFrom("看看这个"))
    assertNull(api.roomIdFrom(""))
    assertNull("a name with digits in it is still a name", api.roomIdFrom("红眼阿旺2024"))
  }

  /** A session that Douyin no longer accepts must say so, not look like "no results". */
  @Test
  fun `a login wall during search is reported as needing a login`() {
    val http = FakeDouyinHttp { ok("""{"status_code":2483,"status_msg":"请先登录，再继续搜索吧"}""") }

    val error = assertThrows<PlatformAuthRequiredException> {
      runBlocking { api(http, session = true).search("红眼阿旺", page = 1) }
    }

    assertTrue(error.message.orEmpty().contains("请先登录"), error.message)
  }
}
