package com.livehub.platform.huya

import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import com.livehub.core.network.NetworkException
import com.livehub.platform.api.AssetReader
import com.livehub.platform.api.PlatformOfflineException
import java.net.URLEncoder
import java.util.Base64
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * How many times opening a Huya room downloads its page, and what happens when a download
 * fails.
 *
 * The bug this pins: `HuyaAdapter.playback` called `api.playback(roomId)` for the stream
 * URLs and then `api.roomInfo(roomId)` again for the `Streamer`, so a single tap on a room
 * card downloaded `https://www.huya.com/{roomId}` **twice** — the page is the whole player
 * config plus the room markup, so that is the bulk of the wait before the first frame.
 *
 * Worse, the second download was the only one not wrapped in `runCatching`: the first one
 * already had the URLs in hand, yet a transient failure on the redundant retry threw
 * `虎牙房间页加载失败` and the room never played. The other adapters do not have this shape —
 * Douyu resolves the whole `PlaybackSource` in one call, and Bilibili wraps its extra
 * `roomDetail` in `runCatching` — so Huya was the only platform where a redundant request
 * could take down a playback that had already succeeded.
 *
 * `api.playback` now returns the page it parsed ([HuyaPlayback]) so the adapter rebuilds
 * the `Streamer` from the same response. These tests assert the request count and the
 * failure mode, not the shape of the fix, so they stay honest if the code is reorganised
 * again.
 */
class HuyaPlaybackTest {

  /**
   * Serves canned room pages and records every request.
   *
   * [respond] receives the zero-based request index so a test can make exactly the second
   * request fail, which is the scenario the old double fetch turned into a dead room.
   */
  private class FakeHuyaHttp(
    private val respond: (index: Int, url: String) -> HttpResponse,
  ) : HttpClient {
    val urls = mutableListOf<String>()

    override suspend fun execute(request: Request): HttpResponse {
      val url = request.url.toString()
      urls += url
      return respond(urls.size - 1, url)
    }
  }

  /** Base64 of `PREFIX_timestamp_suffix`, as Huya ships it inside `sFlvAntiCode`. */
  private val fm: String = Base64.getEncoder()
    .encodeToString("abcdef_1699999999999_abc".toByteArray())
    .let { URLEncoder.encode(it, "UTF-8") }

  private fun stream(
    cdn: String,
    name: String = "660000-abc",
    suffix: String = "flv",
    ctype: String = "huya_live",
    fs: String = "bgct",
  ): String = """{"sCdnType":"$cdn","sFlvUrl":"https://$cdn.flv.huya.com/src",""" +
    """"sStreamName":"$name","sFlvUrlSuffix":"$suffix",""" +
    """"sFlvAntiCode":"fm=$fm&ctype=$ctype&fs=$fs"}"""

  /**
   * A room page in the shape [HuyaApi.extractStreamJson] accepts.
   *
   * Two details of that regex are load-bearing and easy to get wrong in a fixture:
   *
   *  * the key is the bare JS identifier `stream:` — quoting it (`"stream":`) does not
   *    match, which is why the page writes `{stream: {...}}` like the real player config;
   *  * it stops at the literal `,"iWebDefaultBitRate"` and re-appends one closing brace,
   *    so that key has to follow the `data` array immediately, with no line break and no
   *    extra brace in between.
   *
   * The reference resolver (`HuyaStreamUrlResolverAndroid`) uses the identical regex and
   * the identical trailing `+ "}"`, so this is the real page layout, not a test convenience.
   */
  private fun page(
    streams: List<String>,
    title: String = "测试直播间 - 虎牙直播",
    nick: String = "主播甲",
    activity: Long = 12_345,
  ): String {
    val streamList = streams.joinToString(",")
    return """
      <html><head><title>$title</title></head><body>
      <script>
      var hyPlayerConfig = {stream: {"data":[{"gameStreamInfoList":[$streamList]}],"iWebDefaultBitRate":4000}, "nick":"$nick", "avatar180":"https://a.msstatic.com/avatar.jpg", "screenshot":"https://a.msstatic.com/cover.jpg", "activityCount":$activity, "gameFullName":"英雄联盟"};
      </script></body></html>
    """.trimIndent()
  }

  private fun adapter(http: HttpClient): HuyaAdapter = HuyaAdapter(
    http = http,
    webSocketClient = OkHttpClient(),
    assets = AssetReader.Empty,
  )

  @Test
  fun `opening a room downloads its page once`() = runBlocking {
    val http = FakeHuyaHttp { _, _ -> HttpResponse(200, page(listOf(stream("al"))), emptyMap(), "") }

    adapter(http).playback(roomId = "660000", preferredQuality = null, preferredLine = null)

    assertEquals(1, http.urls.size, "the room page must not be fetched twice: ${http.urls}")
  }

  /**
   * The regression that mattered: the room used to fail to play when the *second*,
   * redundant page load failed.
   */
  @Test
  fun `a failing second request cannot break a playback that already resolved`() = runBlocking {
    val http = FakeHuyaHttp { index, _ ->
      if (index == 0) {
        HttpResponse(200, page(listOf(stream("al"), stream("tx"))), emptyMap(), "")
      } else {
        throw NetworkException(500, "redundant retry failed")
      }
    }

    val source = adapter(http).playback(roomId = "660000", preferredQuality = null, preferredLine = null)

    assertEquals(2, source.variants.size, "both CDNs from the first response must survive")
    assertEquals(1, http.urls.size, "no second page load may happen at all: ${http.urls}")
  }

  @Test
  fun `the room metadata comes from the page that produced the streams`() = runBlocking {
    val http = FakeHuyaHttp { _, _ ->
      HttpResponse(200, page(listOf(stream("al")), title = "TES vs iG - 虎牙直播", nick = "虎牙赛事"), emptyMap(), "")
    }

    val source = adapter(http).playback(roomId = "660000", preferredQuality = null, preferredLine = null)

    assertEquals("660000", source.room.roomId)
    assertEquals("虎牙赛事", source.room.streamerName)
    assertTrue(source.room.title.contains("TES vs iG"), "got '${source.room.title}'")
    assertEquals(12_345L, source.room.viewerCount)
    assertEquals("https://a.msstatic.com/cover.jpg", source.room.coverUrl)
    assertTrue(source.room.isLive)
  }

  @Test
  fun `variants are ordered by cdn preference and downgraded to http`() = runBlocking {
    // Declared worst-first on purpose: the resolver has to reorder, not preserve.
    val http = FakeHuyaHttp { _, _ ->
      HttpResponse(200, page(listOf(stream("tx"), stream("hs"), stream("al"))), emptyMap(), "")
    }

    val source = adapter(http).playback(roomId = "660000", preferredQuality = null, preferredLine = null)

    assertEquals(listOf("al", "hs", "tx"), source.variants.map { it.lineName })
    assertTrue(source.variants.all { it.url.startsWith("http://") }, "CDN hosts break Android TLS")
    assertEquals("al", source.defaultVariant?.lineName)
  }

  @Test
  fun `the preferred quality is moved to the front instead of dropped`() = runBlocking {
    val http = FakeHuyaHttp { _, _ ->
      HttpResponse(200, page(listOf(stream("al"), stream("hs"), stream("tx"))), emptyMap(), "")
    }

    // tx ranks last, so without the preference it would never be the default variant.
    val preferred = HuyaSigner.cdnRank("tx")
    val source = adapter(http).playback(roomId = "660000", preferredQuality = preferred, preferredLine = null)

    assertEquals(3, source.variants.size, "a preference reorders, it does not filter")
    assertEquals("tx", source.defaultVariant?.lineName)
  }

  @Test
  fun `a room with no stream list is offline rather than unplayable`() {
    val http = FakeHuyaHttp { _, _ -> HttpResponse(200, page(emptyList()), emptyMap(), "") }

    val error = assertThrows<PlatformOfflineException> {
      runBlocking { adapter(http).playback(roomId = "660000", preferredQuality = null, preferredLine = null) }
    }

    assertTrue(error.message!!.contains("未开播"), "got ${error.message}")
  }

  /**
   * A page without any embedded `stream` config falls through to the mobile user agent,
   * and that fallback is still exactly one extra request — not two.
   */
  @Test
  fun `a page with no stream config retries once with the mobile user agent`() = runBlocking {
    val http = FakeHuyaHttp { index, _ ->
      if (index == 0) {
        HttpResponse(200, "<html><body>no player config here</body></html>", emptyMap(), "")
      } else {
        HttpResponse(200, page(listOf(stream("al"))), emptyMap(), "")
      }
    }

    val source = adapter(http).playback(roomId = "660000", preferredQuality = null, preferredLine = null)

    assertEquals(2, http.urls.size, "one desktop attempt plus one mobile attempt: ${http.urls}")
    assertEquals(1, source.variants.size)
    assertFalse(source.variants.single().url.isBlank())
  }
}
