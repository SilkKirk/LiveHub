package com.livehub.platform.huya

import com.livehub.core.model.Category
import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import com.livehub.platform.api.AssetReader
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The 全部直播 entry that leads Huya's category row.
 *
 * `getLiveList?iGid=0` is what huya.com/l ("全部直播") calls. Measured live: 120 rooms per
 * page, `iTotalPage: 73`, page 1 and page 2 sharing zero rooms, page 73 answering ~90 rooms,
 * page 74 answering none and collapsing `iTotalPage` back to 1. Of the four platforms it is
 * the only feed that really is the whole site.
 *
 * It also exposed a bug in [HuyaApi.hasMorePages]: that feed reports **`iTotal: 0`** with a
 * real `iTotalPage: 73`, and the old rule required `total > 0` before it would look at the
 * page count, so the list stopped after the first 120 rooms. Both halves are pinned here.
 */
class HuyaAllFeedTest {

  /** Answers from [respond] and records every URL it was asked for. */
  private class RecordingHttp(private val respond: (String) -> String) : HttpClient {
    val urls = mutableListOf<String>()

    override suspend fun execute(request: Request): HttpResponse {
      val url = request.url.toString()
      urls += url
      return HttpResponse(code = 200, body = respond(url), headers = emptyMap(), url = url)
    }
  }

  private fun page(vararg ids: String, extra: String = ""): String {
    val rooms = ids.joinToString(",") {
      """{"lProfileRoom":$it,"sNick":"主播$it","sIntroduction":"标题$it","lUserCount":10}"""
    }
    return """{"vList":[$rooms]$extra}"""
  }

  private fun adapter(http: HttpClient) =
    HuyaAdapter(http = http, webSocketClient = OkHttpClient(), assets = AssetReader.Empty)

  /** A [HuyaApi] whose transport fails loudly: [HuyaApi.hasMorePages] is pure. */
  private val meta = HuyaApi(
    object : HttpClient {
      override suspend fun execute(request: Request): HttpResponse =
        error("this test must not perform network calls")
    },
  )

  private fun metaOf(raw: String) =
    Json { ignoreUnknownKeys = true; isLenient = true }.parseToJsonElement(raw) as JsonObject

  @Test
  fun `the aggregate entry leads the category row`() = runBlocking {
    val categories = adapter(RecordingHttp { page() }).categories()

    assertEquals(Category.ALL_ID, categories.first().id)
    assertEquals("全部直播", categories.first().name)
    assertTrue(categories.first().isAggregate)
    // With no shipped asset the real tree is empty, so the aggregate is all that is left —
    // and it must still be offered rather than degrading to "no categories".
    assertEquals(1, categories.size)
  }

  @Test
  fun `the aggregate feed is requested on gid zero`() = runBlocking {
    val http = RecordingHttp { page("1", "2") }

    adapter(http).rooms(categoryId = Category.ALL_ID, cursor = null, slug = null)

    val url = http.urls.single()
    assertTrue(url.contains("iGid=0"), "got $url")
    // `iGid=all` answers an empty vList, so the wrong id would look like an idle site rather
    // than an error — the failure mode this test exists to catch.
    assertTrue(!url.contains("iGid=all"), "got $url")
  }

  @Test
  fun `a real category is still requested on its own gid`() = runBlocking {
    val http = RecordingHttp { page("1") }

    adapter(http).rooms(categoryId = "1", cursor = "2", slug = null)

    val url = http.urls.single()
    assertTrue(url.contains("iGid=1"), "got $url")
    assertTrue(url.contains("iPageNo=2"), "got $url")
  }

  @Test
  fun `a feed with no iTotal still paginates on its page count`() {
    // The measured shape of iGid=0 page 1: 120 rooms, iTotal 0, iTotalPage 73.
    val root = metaOf("""{"iPageNo":1,"iPageSize":120,"iTotal":0,"iTotalPage":73}""")

    assertTrue(
      meta.hasMorePages(root, roomCount = 120, requestedPage = 1),
      "iTotal: 0 with iTotalPage: 73 means 73 pages, not one",
    )
  }

  @Test
  fun `the last page of a feed with no iTotal stops`() {
    val root = metaOf("""{"iPageNo":73,"iPageSize":120,"iTotal":0,"iTotalPage":73}""")

    assertFalse(meta.hasMorePages(root, roomCount = 90, requestedPage = 73))
  }

  /** Page 74 answers no rooms at all, and the metadata collapses to a single page. */
  @Test
  fun `an empty page past the end stops even though iTotalPage reads 1`() {
    val root = metaOf("""{"iPageNo":74,"iPageSize":120,"iTotal":0,"iTotalPage":1}""")

    assertFalse(meta.hasMorePages(root, roomCount = 0, requestedPage = 74))
  }
}
