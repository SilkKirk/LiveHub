package com.livehub.platform.douyin

import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import com.livehub.core.network.NetworkException
import com.livehub.platform.api.AssetReader
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * When a partition answers "nothing on air", and when it is actually broken.
 *
 * The two used to be the same branch. `rooms()` fell back to the anonymous recommendation feed
 * whenever the partition call returned no rooms *or* failed, so:
 *
 *  * a quiet partition showed 推荐 content under the partition's own label;
 *  * a page past the end of a longer list did the same, and the grid *appends* pages — so the
 *    recommendation rooms were added to the end of the partition list, then the cursor went null
 *    and the list claimed to be complete.
 *
 * An empty answer is information, not a failure: it is the only way the grid can be told "this
 * partition is done". The fallback has to stay for the signature-rejected case, which is what the
 * last test pins.
 *
 * JUnit 5, so every message goes **last** (the `:app` module's JUnit 4 tests are the other way
 * round — the runbook warns about exactly this mix-up).
 */
class DouyinPartitionFallbackTest {

  private class FakeHttp(private val respond: (index: Int, url: String) -> HttpResponse) : HttpClient {
    val urls = mutableListOf<String>()

    override suspend fun execute(request: Request): HttpResponse {
      val url = request.url.toString()
      urls += url
      return respond(urls.size - 1, url)
    }
  }

  private fun ok(body: String): HttpResponse = HttpResponse(200, body, emptyMap(), "")

  /** A partition response with [count] rooms and the server's own "more" flag. */
  private fun partition(count: Int, hasMore: Boolean): String {
    val rooms = (0 until count).joinToString(",") { index ->
      """
      {"web_rid":"room$index","room":{"id_str":"room$index","title":"标题$index",
        "owner":{"nickname":"主播$index"},
        "stats":{"total_user":${100 + index}},
        "cover":{"url_list":["https://a/$index.jpg"]},
        "stream_url":{"hls_pull_url":"https://pull-flv-l1.douyincdn.com/$index/index.m3u8"}}}
      """.trimIndent()
    }
    return """{"status_code":0,"data":{"data":[$rooms],"has_more":$hasMore}}"""
  }

  /** The recommendation feed, which is what a broken partition call must not be confused with. */
  private fun feed(): String = """
    {"status_code":0,"data":[{"data":{"id_str":"feed1","title":"推荐房间",
      "owner":{"nickname":"推荐主播"},
      "stats":{"total_user":999},
      "stream_url":{"hls_pull_url":"https://pull-flv-l1.douyincdn.com/feed/index.m3u8"}}}]}
  """.trimIndent()

  private fun adapter(http: HttpClient) = DouyinAdapter(
    http = http,
    webSocketClient = OkHttpClient(),
    assets = AssetReader.Empty,
  )

  /**
   * An empty page is returned as an empty page.
   *
   * The assertion that fails on the old code is on the request count as well as on the rooms: the
   * feed request must not happen at all, because on the page-after-the-end path it appended
   * recommendation rooms to a partition list that had just legitimately ended.
   */
  @Test
  fun `an empty partition page does not fall back to the feed`() = runBlocking {
    val http = FakeHttp { _, _ -> ok(partition(count = 0, hasMore = false)) }

    val page = adapter(http).rooms(categoryId = "1:1010023", cursor = "4")

    assertTrue(
      http.urls.none { it.contains("/webcast/feed/") },
      "no feed request may be made: ${http.urls}",
    )
    assertTrue(page.rooms.isEmpty(), "the page is empty, not filled from another source: ${page.rooms}")
    assertEquals(null, page.nextCursor, "and the list is told it is done")
  }

  @Test
  fun `a partition page with rooms and no more is passed through`() = runBlocking {
    val http = FakeHttp { _, _ -> ok(partition(count = 2, hasMore = false)) }

    val page = adapter(http).rooms(categoryId = "1:1010023")

    assertEquals(listOf("room0", "room1"), page.rooms.map { it.roomId })
    assertEquals(null, page.nextCursor)
    assertTrue(http.urls.none { it.contains("/webcast/feed/") })
  }

  @Test
  fun `the server's own more flag decides the cursor`() = runBlocking {
    val http = FakeHttp { _, _ -> ok(partition(count = 2, hasMore = true)) }

    val page = adapter(http).rooms(categoryId = "1:1010023")

    assertEquals("2", page.nextCursor)
  }

  /**
   * A rejected signature still degrades to the feed — that part of the old behaviour was right.
   *
   * Documented as a real limitation in the README: the list is 推荐 content under a partition
   * label, which is why it is only reached when the partition request actually failed.
   */
  @Test
  fun `a failed partition request still falls back to the feed`() = runBlocking {
    val http = FakeHttp { _, url ->
      if (url.contains("/webcast/feed/")) {
        ok(feed())
      } else {
        throw NetworkException(444, "signature rejected")
      }
    }

    val page = adapter(http).rooms(categoryId = "1:1010023")

    assertEquals(listOf("feed1"), page.rooms.map { it.roomId })
    assertEquals(null, page.nextCursor, "the feed serves one page")
    assertTrue(
      http.urls.any { it.contains("/webcast/feed/") },
      "the fallback really was requested: ${http.urls}",
    )
  }
}
