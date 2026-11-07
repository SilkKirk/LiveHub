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
    /**
     * The id the caller searched with, echoed where `enter` puts it.
     *
     * Unused by the parser — `toStreamer` keeps the caller's id on purpose (round 42: the
     * internal `id_str` cannot be used to reopen a room) — but kept in the fixture so the body
     * describes what Douyin actually returns for that lookup.
     */
    webRid: String = "1002683794",
  ): String = """
    {
      "status_code": 0,
      "data": {
        "user": {"nickname": "$nickname", "avatar_thumb": {"url_list": ["https://a/avatar.jpg"]}},
        "data": [{
          "id_str": "$idStr",
          "web_rid": "$webRid",
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
    // The promise the message makes: an id needs no login. Asserted on 直播间号 rather than a
    // specific word, because the wording moved from 房间号 to 直播间号 when vanity ids
    // (AYM0707) turned out to be ids too.
    assertTrue(error.message.orEmpty().contains("直播间号"), error.message)
    assertTrue("the message must say letters are fine too", error.message.orEmpty().contains("字母"), error.message)
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
      assertEquals(1, rooms.size, "[$raw] must resolve to the room")
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

  /**
   * The reported case: a **vanity** web_rid.
   *
   * Douyin hands creators ids like `AYM0707` (measured 2026-11-07: it resolves to 房间
   * 「来啦来啦，开搞开搞」/ DNF王大枪 and was live). The previous classifier required *digits*, so
   * this — the exact string in the user's screenshot — fell through to keyword search and was
   * told to log in.
   */
  @Test
  fun `a vanity room id with letters resolves without a login`() = runBlocking {
    val http = FakeDouyinHttp { ok(enter(webRid = "AYM0707", nickname = "DNF王大枪")) }

    val (rooms, _) = api(http).search("AYM0707", page = 1)

    assertEquals(1, rooms.size, "a letter id must resolve, not throw the login message")
    assertEquals("AYM0707", rooms.single().roomId)
    assertTrue(http.urls.single().contains("web_rid=AYM0707"), http.urls.single())
  }

  /**
   * An id that does not resolve, while anonymous, is "没有找到" — not "需要登录".
   *
   * An ASCII word can also be a nickname (`uzi`, `PDD`), but without a session there is no
   * keyword search to fall back to, and claiming a login is needed for a room id is precisely
   * the misreport being fixed.
   */
  @Test
  fun `an unknown id returns no results instead of the login message`() = runBlocking {
    val http = FakeDouyinHttp { url ->
      ok(if (url.contains("/enter/")) missingRoom() else """{"data": []}""")
    }

    val (rooms, _) = api(http).search("NOSUCHROOM99", page = 1)

    assertTrue(rooms.isEmpty())
  }

  /**
   * With a session, an ASCII word that is not a room id falls back to keyword search — that is
   * how a one-word nickname can still be found.
   */
  @Test
  fun `a non-resolving word falls back to keyword search when logged in`() {
    val body = """{"status_code":0,"data":[]}"""
    val http = FakeDouyinHttp { url ->
      ok(if (url.contains("/enter/")) missingRoom() else body)
    }

    runBlocking { api(http, session = true).search("uzi", page = 1) }

    assertTrue(
      http.urls.any { it.startsWith("https://www.douyin.com/aweme/v1/web/live/search/") },
      "the fallback must hit the keyword endpoint: ${http.urls}",
    )
  }

  @Test
  fun `the id parser accepts the documented shapes and refuses names`() {
    val api = api(FakeDouyinHttp { ok(enter()) })

    assertEquals("1002683794", api.webRidFrom("1002683794"))
    assertEquals("1002683794", api.webRidFrom("  1002683794  "))
    assertEquals("1002683794", api.webRidFrom("https://live.douyin.com/1002683794"))
    assertEquals("1002683794", api.webRidFrom("live.douyin.com/1002683794?from=share"))
    // A CJK keyboard produces full-width digits; they are Unicode category Nd, so they are
    // digits, but they must be folded back to ASCII before being put in a query string.
    assertEquals("1002683794", api.webRidFrom("１００２６８３７９４"))
    assertEquals("1002683794", api.webRidFrom("1002\u200B683794"))
    // The label a user types in front of the id is a word, not punctuation.
    assertEquals("1002683794", api.webRidFrom("房间号：1002683794"))

    // Vanity ids: letters are part of the id and must survive untouched (case included).
    assertEquals("AYM0707", api.webRidFrom("AYM0707"))
    assertEquals("AYM0707", api.webRidFrom("https://live.douyin.com/AYM0707"))
    assertEquals("AYM0707", api.webRidFrom("房间号：AYM0707"))
    assertEquals("AYM-0707", api.webRidFrom("AYM-0707"))

    assertNull(api.webRidFrom("红眼阿旺"))
    assertNull(api.webRidFrom("看看这个"))
    assertNull(api.webRidFrom(""))
    // CJK plus digits is a name, not an id: no room id has CJK in it.
    assertNull(api.webRidFrom("红眼阿旺2024"))
    // A sentence is not an id either.
    assertNull(api.webRidFrom("DNF 深渊日常"))
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
