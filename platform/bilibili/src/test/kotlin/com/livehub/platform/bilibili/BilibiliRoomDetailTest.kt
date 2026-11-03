package com.livehub.platform.bilibili

import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Where a Bilibili room's name, avatar, title and viewer count come from.
 *
 * The bug this pins: `roomInfo` read `room/v1/Room/get_info`, and that endpoint stopped
 * carrying the fields the player header shows. Its live response has `title`,
 * `live_status`, `online` and `user_cover`, but **no `uname`, no `anchor_info` and no
 * `watched_show`** — so every lookup fell through and a Bilibili room opened with a blank
 * streamer name, a blank avatar and a viewer count stuck at 0.
 *
 * `fixtures/bilibili-h5-info.json` is a captured `getH5InfoByRoom` response (room 6963590,
 * live at capture time) and `fixtures/bilibili-room-get-info.json` a captured `Room/get_info`
 * response for the same room, so the split below is checked against what the two endpoints
 * really answer rather than against what they were documented to answer.
 */
class BilibiliRoomDetailTest {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  private fun fixture(name: String): String =
    BilibiliRoomDetailTest::class.java.getResourceAsStream("/fixtures/$name")!!
      .bufferedReader()
      .use { it.readText() }

  /** Serves one canned body per URL fragment and records the URLs it was asked for. */
  private class FakeBilibiliHttp(
    private val bodies: Map<String, String>,
  ) : HttpClient {
    val urls = mutableListOf<String>()

    override suspend fun execute(request: Request): HttpResponse {
      val url = request.url.toString()
      urls += url
      val body = bodies.entries.firstOrNull { url.contains(it.key) }?.value
        ?: error("unexpected request: $url")
      return HttpResponse(code = 200, body = body, headers = emptyMap(), url = url)
    }
  }

  private fun detail() = fixture("bilibili-h5-info.json")

  private fun getInfo() = fixture("bilibili-room-get-info.json")

  @Test
  fun `room detail maps the anchor title avatar and live state`() = runBlocking {
    val http = FakeBilibiliHttp(mapOf("getH5InfoByRoom" to detail()))
    val api = BilibiliApi(http, InMemoryCookieStore())

    val info = api.roomInfo("6963590")

    // Expected values come from the fixture, so the assertions cannot drift from the capture.
    val data = json.parseToJsonElement(detail()).jsonObject["data"]!!.jsonObject
    val room = data["room_info"]!!.jsonObject
    val anchor = data["anchor_info"]!!.jsonObject["base_info"]!!.jsonObject

    assertEquals(room["title"]!!.jsonPrimitive.content, info.title)
    assertEquals(anchor["uname"]!!.jsonPrimitive.content, info.streamerName)
    assertEquals(anchor["face"]!!.jsonPrimitive.content, info.avatarUrl)
    assertEquals(room["keyframe"]!!.jsonPrimitive.content, info.coverUrl)
    assertTrue(info.isLive)
    assertTrue(info.streamerName.isNotBlank(), "a blank anchor name was the bug")
  }

  @Test
  fun `the viewer count is the live online figure not the cumulative watched figure`() = runBlocking {
    val http = FakeBilibiliHttp(mapOf("getH5InfoByRoom" to detail()))
    val api = BilibiliApi(http, InMemoryCookieStore())

    val info = api.roomInfo("6963590")

    val data = json.parseToJsonElement(detail()).jsonObject["data"]!!.jsonObject
    val online = data["room_info"]!!.jsonObject["online"]!!.jsonPrimitive.longOrNull
    val watched = data["watched_show"]!!.jsonObject["num"]!!.jsonPrimitive.longOrNull

    assertEquals(online, info.viewerCount)
    assertNotEquals(watched, info.viewerCount, "watched_show.num is the cumulative 看过 figure")
    assertTrue(info.viewerCount > 0, "a live room with 0 viewers was the bug")
  }

  @Test
  fun `room detail comes from getH5InfoByRoom and the follow list from get_info`() = runBlocking {
    // The two endpoints are not interchangeable: only the second is cheap enough to poll for
    // every followed room, and only the first carries the anchor.
    val http = FakeBilibiliHttp(
      mapOf(
        "getH5InfoByRoom" to detail(),
        "room/v1/Room/get_info" to getInfo(),
      ),
    )
    val api = BilibiliApi(http, InMemoryCookieStore())

    api.roomInfo("6963590")
    val status = api.liveStatus("6963590")

    assertTrue(http.urls[0].contains("getH5InfoByRoom"), http.urls[0])
    assertTrue(http.urls[1].contains("room/v1/Room/get_info"), http.urls[1])

    val getInfoData = json.parseToJsonElement(getInfo()).jsonObject["data"]!!.jsonObject
    assertEquals(getInfoData["live_status"]!!.jsonPrimitive.content, if (status.isLive) "1" else "0")
    assertEquals(getInfoData["title"]!!.jsonPrimitive.content, status.title)
    assertEquals(getInfoData["online"]!!.jsonPrimitive.longOrNull, status.viewerCount)
    assertEquals(
      getInfoData["user_cover"]!!.jsonPrimitive.content,
      status.coverUrl,
      "the cheap endpoint does return a cover, which is all the follow list needs",
    )
  }

  @Test
  fun `the captured get_info payload really has no anchor name`() = runBlocking {
    // Guards the reason for the split above: if `uname` ever comes back, this test says so
    // and the extra round trip in roomInfo can be reconsidered.
    val data = json.parseToJsonElement(getInfo()).jsonObject["data"]!!.jsonObject

    assertFalse(data.containsKey("uname"), "Room/get_info carries no anchor name")
    assertFalse(data.containsKey("anchor_info"), "Room/get_info carries no anchor_info")
    assertFalse(data.containsKey("watched_show"), "Room/get_info carries no watched_show")
  }
}
