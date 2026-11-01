package com.livehub.platform.huya

import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Pins Huya's pagination to the metadata the endpoint actually returns.
 *
 * Verified live against `live.huya.com/liveHttpUI/getLiveList`: `iPageSize` is **ignored** —
 * requesting 10, 20, 30 or 60 all answer 120 rooms and report `iPageSize: 120` — while the
 * response carries `iPageNo`, `iTotal` and `iTotalPage`. Pagination therefore has to come
 * from those fields. The previous `rooms.size >= pageSize` test was true for the first five
 * pages whatever `pageSize` was, and only stopped because page 6 happens to be empty; a
 * category with a repeating page would have looped forever.
 */
class HuyaPaginationTest {

  /**
   * A `HuyaApi` whose transport fails loudly.
   *
   * `hasMorePages` is pure, so the test only needs an instance; anything that actually
   * reaches the network is a bug in the test.
   */
  private val api = HuyaApi(
    object : HttpClient {
      override suspend fun execute(request: okhttp3.Request): HttpResponse =
        error("this test must not perform network calls")
    },
  )

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  private fun meta(raw: String) = json.parseToJsonElement(raw) as kotlinx.serialization.json.JsonObject

  @Test
  fun `a middle page reports another page`() {
    val root = meta("""{"iPageNo":2,"iPageSize":120,"iTotal":581,"iTotalPage":5}""")
    assertTrue(api.hasMorePages(root, roomCount = 120, requestedPage = 2))
  }

  @Test
  fun `the last real page stops`() {
    val root = meta("""{"iPageNo":5,"iPageSize":120,"iTotal":579,"iTotalPage":5}""")
    assertFalse(api.hasMorePages(root, roomCount = 99, requestedPage = 5))
  }

  /**
   * An exhausted category answers `iTotal: 0, iTotalPage: 1`.
   *
   * The page count cannot be trusted in that state — `1 > 5` is false, but on page 1 it
   * would read as "more to come" — so the empty room list is what ends the sequence.
   */
  @Test
  fun `an exhausted category ends even though iTotalPage is 1`() {
    val root = meta("""{"iPageNo":6,"iPageSize":120,"iTotal":0,"iTotalPage":1}""")
    assertFalse(api.hasMorePages(root, roomCount = 0, requestedPage = 6))
  }

  @Test
  fun `missing metadata ends the sequence instead of looping`() {
    assertFalse(api.hasMorePages(meta("""{}"""), roomCount = 120, requestedPage = 1))
  }

  @Test
  fun `a single short page stops`() {
    val root = meta("""{"iPageNo":1,"iTotal":7,"iTotalPage":1}""")
    assertFalse(api.hasMorePages(root, roomCount = 7, requestedPage = 1))
  }

  /** Without a total there is no way to know; ending is safer than looping forever. */
  @Test
  fun `a full page with no total does not report more`() {
    assertFalse(api.hasMorePages(meta("""{"iPageNo":3}"""), roomCount = 120, requestedPage = 3))
  }

  @Test
  fun `a large total does not overflow`() {
    val root = meta("""{"iPageNo":1,"iTotal":5000000000,"iTotalPage":2}""")
    assertTrue(api.hasMorePages(root, roomCount = 120, requestedPage = 1))
  }

  /** The page number the server echoes wins over the one that was requested. */
  @Test
  fun `the reported page number is used when present`() {
    val root = meta("""{"iPageNo":5,"iTotal":579,"iTotalPage":5}""")
    // Requested page 4 would still say "more"; the echoed 5 is what decides.
    assertFalse(api.hasMorePages(root, roomCount = 99, requestedPage = 4))
  }

  @Test
  fun `cursors are stringified page numbers`() {
    val root = meta("""{"iPageNo":3,"iTotal":581,"iTotalPage":5}""")
    val page = 3
    val more = api.hasMorePages(root, roomCount = 120, requestedPage = page)
    val nextCursor: String? = if (more) (page + 1).toString() else null
    assertEquals("4", nextCursor)
  }
}
