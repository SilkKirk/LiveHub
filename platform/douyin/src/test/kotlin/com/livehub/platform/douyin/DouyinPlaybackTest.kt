package com.livehub.platform.douyin

import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import com.livehub.core.network.NetworkException
import com.livehub.platform.api.AssetReader
import com.livehub.platform.api.PlatformOfflineException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * How many times opening a Douyin room downloads its payload, and what happens when a
 * download fails.
 *
 * The bug this pins: `DouyinAdapter.playback` called `api.playback(roomId)` for the stream
 * URLs and then `roomDetail(roomId)` for the `Streamer`. Both go through
 * `webcast/room/web/enter`, so a single tap on a room card fetched the same room object
 * **twice** — and the second fetch was the only call on that path not wrapped in
 * `runCatching`, so a transient failure on the redundant retry threw
 * 抖音房间信息获取失败 and a room whose URLs had already resolved never played. Huya had the
 * identical shape and was fixed in the twenty-fourth round; this is the same fix, verified
 * by request count rather than by the shape of the code.
 *
 * `api.playback` now returns the parsed payload ([DouyinPlayback]) so the adapter rebuilds
 * the `Streamer` from the same response that produced the URLs.
 */
class DouyinPlaybackTest {

  /**
   * Serves canned payloads and records every request.
   *
   * [respond] receives the zero-based request index, so a test can make exactly the second
   * request fail — the scenario the old double fetch turned into a dead room.
   */
  private class FakeDouyinHttp(
    private val respond: (index: Int, url: String) -> HttpResponse,
  ) : HttpClient {
    val urls = mutableListOf<String>()

    override suspend fun execute(request: Request): HttpResponse {
      val url = request.url.toString()
      urls += url
      return respond(urls.size - 1, url)
    }
  }

  private fun ok(body: String): HttpResponse = HttpResponse(200, body, emptyMap(), "")

  private fun urls(map: Map<String, String>): String =
    map.entries.joinToString(",") { (key, url) -> "\"$key\":\"$url\"" }

  /**
   * A `room/web/enter` body in the shape `DouyinApi.roomInfo` reads: the room object under
   * `data.data[0]` and the anchor under `data.user`.
   */
  private fun enter(
    roomId: String = "7380123456789012345",
    title: String = "王者荣耀职业联赛",
    nickname: String = "KPL赛事",
    viewers: Long = 12_345,
    hls: Map<String, String> = mapOf(
      "FULL_HD1" to "https://pull-flv-l1.douyincdn.com/stage/full.m3u8",
      "HD1" to "https://pull-flv-l1.douyincdn.com/stage/hd.m3u8",
    ),
    flv: Map<String, String> = mapOf(
      "FULL_HD1" to "https://pull-flv-l1.douyincdn.com/stage/full.flv",
    ),
  ): String = """
    {
      "status_code": 0,
      "data": {
        "user": {"nickname": "$nickname", "avatar_thumb": {"url_list": ["https://a/avatar.jpg"]}},
        "data": [{
          "id_str": "$roomId",
          "title": "$title",
          "cover": {"url_list": ["https://a/cover.jpg"]},
          "stats": {"total_user": $viewers},
          "stream_url": {"hls_pull_url_map": {${urls(hls)}}, "flv_pull_url": {${urls(flv)}}}
        }]
      }
    }
  """.trimIndent()

  /** A feed body in the shape `findInFeed` reads, used by the offline-fallback test. */
  private fun feed(roomId: String, nickname: String = "推荐主播"): String = """
    {
      "data": [
        {"data": {
          "id_str": "$roomId",
          "title": "推荐房间",
          "owner": {"nickname": "$nickname"},
          "stats": {"total_user": 999},
          "stream_url": {"hls_pull_url": "https://pull-flv-l1.douyincdn.com/feed/index.m3u8"}
        }}
      ]
    }
  """.trimIndent()

  /**
   * The cookie is pre-seeded so the ttwid negotiation does not add a request of its own:
   * these tests count requests, and a negotiation is not what they are about.
   */
  private fun adapter(http: HttpClient): DouyinAdapter = DouyinAdapter(
    http = http,
    webSocketClient = OkHttpClient(),
    api = DouyinApi(http, InMemoryDouyinCookieStore().apply { save("ttwid=seeded") }),
    assets = AssetReader.Empty,
  )

  @Test
  fun `opening a room downloads its payload once`() = runBlocking {
    val http = FakeDouyinHttp { _, _ -> ok(enter()) }

    adapter(http).playback(roomId = "164485212", preferredQuality = null, preferredLine = null)

    assertEquals(1, http.urls.size, "the enter payload must not be fetched twice: ${http.urls}")
    assertTrue(http.urls.single().contains("/webcast/room/web/enter/"), http.urls.single())
  }

  /**
   * The regression that mattered: a room failed to play when the *second*, redundant fetch
   * failed, even though the first one had already returned the pull URLs.
   */
  @Test
  fun `a failing second request cannot break a playback that already resolved`() = runBlocking {
    val http = FakeDouyinHttp { index, _ ->
      if (index == 0) ok(enter()) else throw NetworkException(500, "redundant retry failed")
    }

    val source = adapter(http).playback(roomId = "164485212", preferredQuality = null, preferredLine = null)

    assertEquals(3, source.variants.size, "every rendition from the first response must survive")
    assertEquals(1, http.urls.size, "no second fetch may happen at all: ${http.urls}")
  }

  @Test
  fun `the room metadata comes from the payload that produced the streams`() = runBlocking {
    val http = FakeDouyinHttp { _, _ ->
      ok(enter(title = "KPL 总决赛", nickname = "KPL赛事", viewers = 8_888))
    }

    val source = adapter(http).playback(roomId = "164485212", preferredQuality = null, preferredLine = null)

    assertEquals("7380123456789012345", source.room.roomId, "the numeric id, not the web_rid")
    assertEquals("KPL赛事", source.room.streamerName)
    assertEquals("KPL 总决赛", source.room.title)
    assertEquals(8_888L, source.room.viewerCount)
    assertEquals("https://a/cover.jpg", source.room.coverUrl)
    assertTrue(source.room.isLive)
  }

  @Test
  fun `hls renditions come before flv ones`() = runBlocking {
    val http = FakeDouyinHttp { _, _ -> ok(enter()) }

    val source = adapter(http).playback(roomId = "164485212", preferredQuality = null, preferredLine = null)

    assertEquals(listOf("原画", "蓝光", "原画"), source.variants.map { it.qualityLabel })
    assertEquals(listOf(true, true, false), source.variants.map { it.isHls })
    assertEquals("原画", source.defaultVariant?.qualityLabel)
    assertTrue(source.defaultVariant!!.isHls, "the default rendition should be the best hls one")
  }

  @Test
  fun `the preferred quality is moved to the front instead of dropped`() = runBlocking {
    val http = FakeDouyinHttp { _, _ -> ok(enter()) }

    // HD1 ranks second, so without the preference it would never be the default variant.
    val source = adapter(http).playback(roomId = "164485212", preferredQuality = 1, preferredLine = null)

    assertEquals(3, source.variants.size, "a preference reorders, it does not filter")
    assertEquals("蓝光", source.defaultVariant?.qualityLabel)
  }

  /**
   * A rendition key nobody has mapped yet still sorts ahead of every flv rendition.
   *
   * This is not hypothetical ordering taste: the reference resolver prefers hls because
   * "some Douyin FLV streams may be H.265-in-FLV which Media3/ExoPlayer may not render —
   * audio-only on many devices". Before the ranks were clamped, an unmapped hls key took
   * `10 + index` and therefore landed *after* the flv renditions, so a single new key would
   * have made the player default to the format that can render as audio only.
   */
  @Test
  fun `an unmapped hls key still sorts ahead of every flv rendition`() = runBlocking {
    val http = FakeDouyinHttp { _, _ ->
      ok(
        enter(
          hls = mapOf(
            "FULL_HD1" to "https://pull-flv-l1.douyincdn.com/stage/full.m3u8",
            "HD2" to "https://pull-flv-l1.douyincdn.com/stage/hd2.m3u8",
          ),
        ),
      )
    }

    val source = adapter(http).playback(roomId = "164485212", preferredQuality = null, preferredLine = null)

    assertEquals(listOf(true, true, false), source.variants.map { it.isHls })
    assertEquals("HD2", source.variants[1].qualityLabel, "the unmapped key keeps its own label")
    assertTrue(source.defaultVariant!!.isHls)
  }

  @Test
  fun `a room with no pull url is offline rather than unplayable`() {
    val http = FakeDouyinHttp { _, _ ->
      ok(
        """
        {"status_code": 0, "data": {"data": [{"id_str": "7380123456789012345", "title": "已下播"}]}}
        """.trimIndent(),
      )
    }

    val error = assertThrows<PlatformOfflineException> {
      runBlocking { adapter(http).playback(roomId = "164485212", preferredQuality = null, preferredLine = null) }
    }

    assertTrue(error.message!!.contains("未开播"), "got ${error.message}")
  }

  /**
   * A room the `enter` endpoint does not know falls back to the feed — once, not twice.
   *
   * The fallback is inside `roomInfo`, so the old double fetch paid it twice: two `enter`
   * calls plus two 50-item feed downloads before the first frame.
   */
  @Test
  fun `a room missing from enter falls back to the feed exactly once`() = runBlocking {
    val http = FakeDouyinHttp { index, _ ->
      if (index == 0) ok("""{"status_code":0,"data":{"data":[]}}""") else ok(feed("7380123456789012345"))
    }

    // `findInFeed` matches the feed's `id_str` against the requested id, so this path only
    // resolves for a numeric id — which is what the room list carries.
    val source = adapter(http).playback(
      roomId = "7380123456789012345",
      preferredQuality = null,
      preferredLine = null,
    )

    assertEquals(2, http.urls.size, "one enter plus one feed request: ${http.urls}")
    assertTrue(http.urls[1].contains("/webcast/feed/"), http.urls[1])
    assertEquals("推荐主播", source.room.streamerName)
    assertEquals("7380123456789012345", source.room.roomId)
    assertFalse(source.variants.isEmpty(), "the feed rendition must reach the player")
  }
}
