package com.livehub.platform.bilibili

import com.livehub.core.model.Category
import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import com.livehub.platform.api.AssetReader
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The 热门 entry that leads Bilibili's category row.
 *
 * The hot list was already implemented — `BilibiliApi.hotRooms`, `BilibiliArea.HOT` and
 * `BilibiliCategories.HOT_ID = "0:0"` — but nothing in the UI could produce that id: `0:0`
 * is not one of the 456 partitions in the shipped table, so only `BilibiliPaginationTest`
 * ever called it. It is reachable now, and this pins the three things that make it work:
 *
 *  * the aggregate id has no colon, which is exactly what [BilibiliArea.decode] maps to HOT;
 *  * the hot list is `index/getList`, a **single page** — `page` and `page_size` are ignored,
 *    page 2 repeats 97 of page 1's 110 rooms;
 *  * the entry is labelled 热门 rather than 全部, because Bilibili exposes no whole-site feed
 *    (`second/getList` with `parent_area_id=0` answers `code = -400`).
 */
class BilibiliAllFeedTest {

  /** Answers from [respond] and records every URL it was asked for. */
  private class RecordingHttp(private val respond: (String) -> String) : HttpClient {
    val urls = mutableListOf<String>()

    override suspend fun execute(request: Request): HttpResponse {
      val url = request.url.toString()
      urls += url
      return HttpResponse(code = 200, body = respond(url), headers = emptyMap(), url = url)
    }
  }

  private fun room(id: String): String = """
    {"roomid":"$id","title":"标题$id","uname":"主播$id","cover":"//i0.hdslb.com/$id.jpg",
     "online":$id,"status":true}
  """.trimIndent()

  private fun hotPage(vararg ids: String): String {
    val list = ids.joinToString(",") { room(it) }
    return """{"code":0,"message":"OK","data":{"room_list":[{"module_title":"模块","list":[$list]}]}}"""
  }

  private fun adapter(http: HttpClient) = BilibiliAdapter(
    http = http,
    webSocketClient = OkHttpClient(),
    cookieStore = InMemoryCookieStore(),
    assets = AssetReader.Empty,
  )

  @Test
  fun `the hot entry leads the category row`() = runBlocking {
    val categories = adapter(RecordingHttp { hotPage("1") }).categories()

    assertEquals(Category.ALL_ID, categories.first().id)
    assertEquals("热门", categories.first().name)
    assertTrue(categories.first().isAggregate)
  }

  @Test
  fun `the aggregate entry is requested on the hot list endpoint`() = runBlocking {
    val http = RecordingHttp { hotPage("100", "200") }

    val page = adapter(http).rooms(categoryId = Category.ALL_ID, cursor = null, slug = null)

    val url = http.urls.single()
    assertTrue(url.contains("index/getList"), "got $url")
    assertTrue(url.contains("area_id=0"), "got $url")
    assertEquals(2, page.rooms.size)
    assertNull(page.nextCursor, "the hot tab has no second page")
  }

  /**
   * The aggregate id must keep decoding to HOT even though it is not a `parent:area` pair.
   *
   * `BilibiliArea.decode` falls back to HOT for anything without a colon, which is what makes
   * the shared `Category.ALL_ID` work here without a special case in `rooms`.
   */
  @Test
  fun `the aggregate id decodes to the hot area`() {
    assertEquals(BilibiliArea.HOT, BilibiliArea.decode(Category.ALL_ID))
  }

  @Test
  fun `a real partition still goes to the signed second list`() = runBlocking {
    val http = RecordingHttp { url ->
      if (url.contains("live.bilibili.com/lol")) {
        """<html><script>window._render_data_ = {"access_id":"eyJ.test.token"}</script></html>"""
      } else {
        """{"code":0,"message":"OK","data":{"list":[${room("7")}],"has_more":0}}"""
      }
    }

    adapter(http).rooms(categoryId = "2:86", cursor = null, slug = null)

    assertTrue(
      http.urls.any { it.contains("second/getList") },
      "a partition must not be served from the hot list, got ${http.urls}",
    )
    assertTrue(http.urls.none { it.contains("index/getList") }, "got ${http.urls}")
  }
}
