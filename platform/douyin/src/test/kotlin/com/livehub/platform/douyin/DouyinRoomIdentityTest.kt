package com.livehub.platform.douyin

import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Which id a Douyin room is addressed by outside the player.
 *
 * `room/web/enter` answers with two ids: the internal `id_str` (`7703900142109592362`) and the
 * `web_rid` the caller asked with (`1002683794`, the number in the room's URL and in search
 * results). Only the web_rid is accepted back: `enter?web_rid=<id_str>` answers
 * `status_code 4001038` with no rooms.
 *
 * `DouyinRoomInfo.toStreamer()` used to default to the payload's internal id, so a room followed
 * from the player was stored under an id nothing could resolve again — the card stayed 未开播,
 * and tapping it could not open the room.
 */
class DouyinRoomIdentityTest {

  private class FakeDouyinHttp(private val body: String) : HttpClient {
    val urls = mutableListOf<String>()

    override suspend fun execute(request: Request): HttpResponse {
      urls += request.url.toString()
      return HttpResponse(200, body, emptyMap(), "")
    }
  }

  /** An `enter` payload whose internal id differs from the web_rid on purpose. */
  private fun enter(idStr: String = "7703900142109592362", title: String = "我的红眼看了不烧心！！") = """
    {
      "status_code": 0,
      "data": {
        "user": {"nickname": "红眼阿旺", "avatar_thumb": {"url_list": ["https://a/avatar.jpg"]}},
        "data": [{
          "id_str": "$idStr",
          "title": "$title",
          "cover": {"url_list": ["https://a/cover.jpg"]},
          "stats": {"total_user": 0},
          "stream_url": {"hls_pull_url_map": {"FULL_HD1": "https://pull/full.m3u8"}}
        }]
      }
    }
  """.trimIndent()

  private fun adapter(http: HttpClient) = DouyinAdapter(
    http = http,
    webSocketClient = OkHttpClient(),
    api = DouyinApi(http, InMemoryDouyinCookieStore().apply { save("ttwid=seeded") }),
  )

  @Test
  fun `roomDetail keeps the id the caller asked with`() = runBlocking {
    val http = FakeDouyinHttp(enter())

    val detail = adapter(http).roomDetail("1002683794")

    assertNotNull(detail)
    val room = detail!!
    assertEquals("1002683794", room.roomId, "the web_rid, not the payload's id_str")
    assertEquals("红眼阿旺", room.streamerName)
    assertTrue(http.urls.single().contains("web_rid=1002683794"), http.urls.single())
  }

  @Test
  fun `the room a playback resolves can be reopened with its own id`() = runBlocking {
    val http = FakeDouyinHttp(enter())

    val source = adapter(http).playback("1002683794", preferredQuality = null, preferredLine = null)
    // What the player's follow button stores, and what the follow list later asks about.
    val followedId = source.room.roomId
    val reopened = adapter(http).roomDetail(followedId)

    assertEquals("1002683794", followedId, "the follow button must store the web_rid")
    assertEquals(followedId, reopened?.roomId, "the stored id must resolve again")
    assertTrue(
      http.urls.all { it.contains("web_rid=1002683794") },
      "every request must address the room by its web_rid: ${http.urls}",
    )
  }

  /** The internal id is still what the danmaku handshake needs, so it stays on the payload. */
  @Test
  fun `the payload still exposes the internal room id`() = runBlocking {
    val http = FakeDouyinHttp(enter())
    val api = DouyinApi(http, InMemoryDouyinCookieStore().apply { save("ttwid=seeded") })

    val info = api.roomInfo("1002683794")

    assertEquals("7703900142109592362", info.roomId)
    assertEquals("1002683794", info.toStreamer("1002683794").roomId)
  }
}
