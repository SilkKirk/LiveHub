package com.livehub.platform.douyu

import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import com.livehub.platform.api.PlatformProtocolException
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * When `DouyuApi.rooms` reports another page.
 *
 * The bug this pins: the cursor was decided by `parsedRooms.size >= limit`, i.e. "I got
 * back as many rooms as I asked for". `mixListV1` gives no total and no page count, so
 * that guess was the only signal the app had — and it was wrong in both directions:
 *
 *  * Douyu chooses the page size itself. The recorded capture in
 *    `fixtures/douyu-mixlistv1.json` is a `?limit=20` request that answered with **40**
 *    rooms, so `limit` was never the page size being compared against.
 *  * `rooms` is the *mapped* list. [DouyuParser.mixListRoom] drops a row whose `rid` is
 *    blank or `0`, so a single bad row on an otherwise full page made the page look
 *    short and ended the category. The user saw the grid stop with "没有更多了" while
 *    the rest of the category was still unread, and pull-to-refresh was the only way
 *    to see anything past it.
 *
 * `douyu_spec.md` §1.4 states the real rule for this endpoint: no total is returned and
 * the end of the list is an **empty `data.rl`**. That is what these tests assert, and
 * they go through the real [DouyuApi] rather than a parser helper so the rule cannot
 * drift away from the code that actually runs.
 */
class DouyuPaginationTest {

  /** Replays one canned body and records the URLs it was asked for. */
  private class FakeDouyuHttp(private val body: String) : HttpClient {
    val urls = mutableListOf<String>()

    override suspend fun execute(request: Request): HttpResponse {
      val url = request.url.toString()
      urls += url
      return HttpResponse(code = 200, body = body, headers = emptyMap(), url = url)
    }
  }

  private fun page(vararg rooms: String): String =
    """{"code":0,"msg":"success","data":{"rl":[${rooms.joinToString(",")}]}}"""

  private fun room(id: String): String =
    """{"rid":$id,"rn":"标题$id","nn":"主播$id","ol":10,"rs16":"https://img/$id.jpg"}"""

  /** A page of [count] rooms, of which [broken] carry `rid: 0` and are dropped by the mapper. */
  private fun pageOf(count: Int, broken: Int = 0): String = page(
    *(1..count).map { index -> if (index <= broken) """{"rid":0,"rn":"坏行","nn":"无"}""" else room(index.toString()) }.toTypedArray(),
  )

  @Test
  fun `a short page still offers the next page`() = runBlocking {
    // Three rooms for a requested 30. The server is entitled to answer with any page size.
    val http = FakeDouyuHttp(pageOf(3))
    val api = DouyuApi(http)

    val result = api.rooms(categoryId = "1", page = 1, limit = 30)

    assertEquals(3, result.rooms.size)
    assertEquals("2", result.nextOffset, "a non-empty page always has a successor")
  }

  @Test
  fun `a page the mapper drops rooms from still offers the next page`() = runBlocking {
    // A full page of 30 where 10 rows are unusable: 20 mapped rooms, still not the end.
    val http = FakeDouyuHttp(pageOf(30, broken = 10))
    val api = DouyuApi(http)

    val result = api.rooms(categoryId = "1", page = 2, limit = 30)

    assertEquals(20, result.rooms.size, "the broken rows are dropped")
    assertEquals("3", result.nextOffset, "dropping rows must not end the list")
  }

  @Test
  fun `an empty page ends the list`() = runBlocking {
    val http = FakeDouyuHttp("""{"code":0,"data":{"rl":[]}}""")
    val api = DouyuApi(http)

    val result = api.rooms(categoryId = "1", page = 4, limit = 30)

    assertTrue(result.rooms.isEmpty())
    assertNull(result.nextOffset, "an empty data.rl is the end of the category")
  }

  @Test
  fun `a page zero cursor is requested as page one`() = runBlocking {
    val http = FakeDouyuHttp(pageOf(1))
    val api = DouyuApi(http)

    api.rooms(categoryId = "1", page = 0, limit = 30)

    assertTrue(http.urls.single().contains("/mixListV1/2_1/1"), "got ${http.urls.single()}")
  }

  @Test
  fun `a third level category is requested on the three prefix`() = runBlocking {
    val http = FakeDouyuHttp(pageOf(1))
    val api = DouyuApi(http)

    api.rooms(categoryId = "3683", page = 2, limit = 30, level = 3)

    val url = http.urls.single()
    assertTrue(url.contains("/mixListV1/3_3683/2"), "got $url")
    assertTrue(url.contains("limit=30"), "the page size is still requested, got $url")
  }

  @Test
  fun `a non zero code is a protocol error, not an empty category`() {
    val http = FakeDouyuHttp("""{"code":1,"msg":"参数错误","data":null}""")
    val api = DouyuApi(http)

    val error = assertThrows<PlatformProtocolException> {
      runBlocking { api.rooms(categoryId = "1", page = 1, limit = 30) }
    }

    assertTrue(error.message!!.contains("code=1"), "got ${error.message}")
  }
}
