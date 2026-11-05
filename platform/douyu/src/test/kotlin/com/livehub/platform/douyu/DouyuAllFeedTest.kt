package com.livehub.platform.douyu

import com.livehub.core.model.Category
import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The 全部直播 entry that leads Douyu's category row.
 *
 * `mixListV1/0_0/<page>` is the feed douyu.com/directory/all itself calls for 全部分类, and
 * it paginates like a real category: measured live, pages 1/2/3 of `0_0` share zero room ids,
 * page 50 still answers 40 rooms and page 200 is empty. Before this, the home screen opened
 * straight onto 英雄联盟 — the first `cate2Id` the catalogue happens to list — with no way to
 * browse without picking a partition first.
 *
 * The endpoint segment is the whole point, so the assertions are on the outgoing URL: a wrong
 * prefix (`2_all`) answers `code: 0` with an empty `data.rl`, i.e. it looks exactly like a
 * category nobody is streaming in.
 */
class DouyuAllFeedTest {

  /** Answers one canned body and records every URL it was asked for. */
  private class RecordingHttp(private val body: String) : HttpClient {
    val urls = mutableListOf<String>()

    override suspend fun execute(request: Request): HttpResponse {
      val url = request.url.toString()
      urls += url
      return HttpResponse(code = 200, body = body, headers = emptyMap(), url = url)
    }
  }

  private fun page(vararg ids: String): String =
    """{"code":0,"data":{"rl":[${ids.joinToString(",") { """{"rid":"$it","rn":"标题$it","nn":"主播$it","ol":1}""" }}]}}"""

  private fun adapter(http: HttpClient) =
    DouyuAdapter(http = http, webSocketClient = OkHttpClient())

  @Test
  fun `the aggregate entry leads the category row`() = runBlocking {
    val http = RecordingHttp(
      """{"code":0,"data":{"cate1Info":[{"cate1Id":1,"cate1Name":"网游"}],
         "cate2Info":[{"cate1Id":1,"cate2Id":1,"cate2Name":"英雄联盟","shortName":"LOL"}]}}""",
    )

    val categories = adapter(http).categories()

    assertEquals(Category.ALL_ID, categories.first().id)
    assertEquals("全部直播", categories.first().name)
    assertTrue(categories.first().isAggregate, "the marker is what keeps it out of the picker")
    // The real tree still follows, unchanged and still in catalogue order.
    assertEquals(listOf("all", "1"), categories.map { it.id })
  }

  @Test
  fun `the aggregate feed is requested on the zero zero segment`() = runBlocking {
    val http = RecordingHttp(page("1", "2"))

    adapter(http).rooms(categoryId = Category.ALL_ID, cursor = null, slug = null)

    val url = http.urls.single()
    assertTrue(url.contains("/mixListV1/0_0/1"), "got $url")
    // `2_all` is what the old code would have built, and it answers an empty list rather
    // than an error — the failure mode this test exists to catch.
    assertTrue(!url.contains("2_all"), "got $url")
  }

  @Test
  fun `the aggregate feed paginates like a category`() = runBlocking {
    val http = RecordingHttp(page("1"))

    val page = adapter(http).rooms(categoryId = Category.ALL_ID, cursor = "3", slug = null)

    assertTrue(http.urls.single().contains("/mixListV1/0_0/3"), "got ${http.urls.single()}")
    assertEquals("4", page.nextCursor, "a non-empty page always has a successor")
  }

  @Test
  fun `the aggregate feed has no third level`() = runBlocking {
    val http = RecordingHttp(page("1"))

    val children = adapter(http).subCategories(Category.ALL_ID)

    assertTrue(children.isEmpty())
    // The feed spans every 大区, so a getThreeCate lookup would be meaningless — and the
    // empty result has to come from the adapter, not from a failed request.
    assertTrue(http.urls.none { it.contains("getThreeCate") }, "got ${http.urls}")
  }

  @Test
  fun `a real category still uses its own two level segment`() = runBlocking {
    val http = RecordingHttp(page("1"))

    adapter(http).rooms(categoryId = "1", cursor = null, slug = null)

    assertTrue(http.urls.single().contains("/mixListV1/2_1/1"), "got ${http.urls.single()}")
  }
}
