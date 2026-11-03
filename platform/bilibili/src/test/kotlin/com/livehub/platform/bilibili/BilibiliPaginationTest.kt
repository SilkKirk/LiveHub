package com.livehub.platform.bilibili

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
 * When a Bilibili list reports another page.
 *
 * All three lists decided the cursor from a room count, and all three were wrong, in
 * different directions:
 *
 *  * **Category** (`second/getList`): `rooms.size >= 20` assumed a fixed page size. The
 *    server picks it and it varies per area — measured 20 rooms on `1:145`, 27 on `2:249`,
 *    40 on `2:86` — and a quiet area answers a *single* room together with `has_more = 1`
 *    (`2:1048`, `2:1096`). One card is below any threshold, so those categories ended after
 *    the first page and the rest of the rooms stayed unreachable.
 *  * **Hot tab** (`index/getList`): `rooms.size >= pageSize` was true on every response
 *    because the endpoint ignores `page_size` and always answers 110 rooms in 11 modules;
 *    page 2 of the same call overlaps page 1 by 97 of those 110. The grid therefore kept
 *    appending a rotating copy of itself and never showed an end.
 *  * **Search** (`search/type`): the old rule was applied to `result.live_user`, which
 *    answers 6 users per page *regardless of the page number*, so it was permanently false.
 *
 * The rules being pinned here are the flags the responses actually carry: `data.has_more`
 * for a category, `pageinfo.live_room.numPages` for search, and "this endpoint has no
 * second page" for the hot tab.
 */
class BilibiliPaginationTest {

  /** Answers from [respond] and records every URL it was asked for. */
  private class FakeBilibiliHttp(private val respond: (String) -> String) : HttpClient {
    val urls = mutableListOf<String>()

    override suspend fun execute(request: Request): HttpResponse {
      val url = request.url.toString()
      urls += url
      return HttpResponse(code = 200, body = respond(url), headers = emptyMap(), url = url)
    }
  }

  /** The page `/lol` that carries the `w_webid` JWT the list endpoint wants. */
  private val accessIdPage =
    """<html><head><script>window._render_data_ = {"access_id":"eyJ.test.token"}</script></head></html>"""

  private fun room(id: String): String = """
    {"roomid":"$id","title":"标题$id","uname":"主播$id","user_cover":"https://img/$id.jpg",
     "live_status":1}
  """.trimIndent()

  private fun secondList(vararg rooms: String, hasMore: String): String =
    """{"code":0,"message":"OK","data":{"list":[${rooms.joinToString(",")}],"count":0$hasMore}}"""

  private fun adapter(http: HttpClient): BilibiliAdapter = BilibiliAdapter(
    http = http,
    webSocketClient = OkHttpClient(),
    cookieStore = InMemoryCookieStore().apply { save("buvid3=seeded; buvid4=seeded") },
    assets = AssetReader.Empty,
  )

  @Test
  fun `a category page with a single room still offers the next page`() = runBlocking {
    // Measured shape of area 2:1048: one room on the page, and the server says has_more = 1.
    val http = FakeBilibiliHttp { url ->
      if (url.contains("live.bilibili.com/lol")) {
        accessIdPage
      } else {
        secondList(room("6963590"), hasMore = ""","has_more":1""")
      }
    }

    val page = adapter(http).rooms(categoryId = "2:1048", cursor = null, slug = null)

    assertEquals(1, page.rooms.size)
    assertEquals("2", page.nextCursor, "has_more = 1 means there is a second page")
  }

  @Test
  fun `a category page the server says is the last one ends the list`() = runBlocking {
    // 40 rooms — more than any threshold the app could pick — but has_more = 0.
    val rooms = (1..40).map { room("100$it") }
    val http = FakeBilibiliHttp { url ->
      if (url.contains("live.bilibili.com/lol")) {
        accessIdPage
      } else {
        secondList(*rooms.toTypedArray(), hasMore = ""","has_more":0""")
      }
    }

    val page = adapter(http).rooms(categoryId = "2:86", cursor = null, slug = null)

    assertEquals(40, page.rooms.size)
    assertNull(page.nextCursor, "the server's own flag decides, not the room count")
  }

  @Test
  fun `an empty category page without has_more ends the list`() = runBlocking {
    // The last page of a category answers an empty list and omits has_more entirely.
    val http = FakeBilibiliHttp { url ->
      if (url.contains("live.bilibili.com/lol")) accessIdPage else secondList(hasMore = "")
    }

    val page = adapter(http).rooms(categoryId = "2:86", cursor = "10", slug = null)

    assertTrue(page.rooms.isEmpty())
    assertNull(page.nextCursor)
  }

  @Test
  fun `the hot tab never reports a second page`() = runBlocking {
    // 11 modules of 10 rooms is what the endpoint answers for every page and every page_size.
    val modules = (1..11).joinToString(",") { module ->
      val rooms = (1..10).joinToString(",") { room("$module$it") }
      """{"module_title":"模块$module","list":[$rooms]}"""
    }
    val http = FakeBilibiliHttp { """{"code":0,"message":"OK","data":{"room_list":[$modules]}}""" }

    val page = adapter(http).rooms(categoryId = "0:0", cursor = null, slug = null)

    assertEquals(110, page.rooms.size)
    assertNull(
      page.nextCursor,
      "index/getList ignores page and page_size; paging it appends duplicates forever",
    )
  }

  private fun searchRoom(id: String): String = """
    {"roomid":"$id","title":"<em class=\"keyword\">英雄联盟</em>直播$id","uname":"主播$id",
     "cover":"//i0.hdslb.com/bfs/live/$id.jpg","uface":"//i0.hdslb.com/bfs/face/$id.jpg",
     "online":$id,"live_status":1,"type":"live_room"}
  """.trimIndent()

  private fun searchPage(vararg rooms: String, roomPages: Int): String = """
    {"code":0,"data":{"result":{"live_room":[${rooms.joinToString(",")}],
     "live_user":[]},
     "pageinfo":{"live_room":{"numResults":874,"numPages":$roomPages}},
     "numPages":25,"pagesize":40,"page":1}}
  """.trimIndent()

  @Test
  fun `a search page maps the live_room bucket and continues while pages remain`() = runBlocking {
    // 40 rooms on page 1 of 22: what a real `search_type=live` response carries.
    val rooms = (1..40).map { searchRoom("696359$it") }
    val http = FakeBilibiliHttp { searchPage(*rooms.toTypedArray(), roomPages = 22) }

    val page = adapter(http).search(keyword = "英雄联盟", cursor = null)

    assertEquals(40, page.rooms.size, "live_room carries 40 rooms per page")
    assertEquals("主播6963591", page.rooms.first().streamerName)
    assertEquals("英雄联盟直播6963591", page.rooms.first().title, "the <em> highlight is stripped")
    assertEquals("2", page.nextCursor)
  }

  @Test
  fun `the last search page ends the list even though data_numPages is larger`() = runBlocking {
    // `data.numPages` (25) counts every bucket; the rooms end at page 22.
    val http = FakeBilibiliHttp { searchPage(searchRoom("1"), searchRoom("2"), roomPages = 22) }

    val page = adapter(http).search(keyword = "英雄联盟", cursor = "22")

    assertEquals(2, page.rooms.size)
    assertNull(page.nextCursor, "page 22 is the last page of the room bucket")
  }

  @Test
  fun `a search response without page counts falls back to the room list`() = runBlocking {
    val http = FakeBilibiliHttp { """
      {"code":0,"data":{"result":{"live_room":[${searchRoom("9")}]}}}
    """.trimIndent() }

    val page = adapter(http).search(keyword = "原神", cursor = "1")

    assertEquals(1, page.rooms.size)
    assertEquals("2", page.nextCursor, "no page count: a non-empty page implies a successor")
  }
}
