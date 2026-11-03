package com.livehub.platform.huya

import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Huya anchor search: what the results carry and how the pager ends.
 *
 * Two defects are pinned here, both verified against the live endpoint
 * (`search.cdn.huya.com/?m=Search&do=getSearchContent`):
 *
 * 1. **Popularity was thrown away.** Documents carry `game_activityCount` (17,863,363 for
 *    room 660000 in the captured fixture) — the same 人气 number `getLiveList` reports as
 *    `lUserCount`. The mapper hard-coded `viewerCount = 0`, and `StreamerCard` only draws
 *    the viewer badge when the count is positive, so every Huya search card lost its
 *    popularity while category and follow cards showed theirs.
 * 2. **Paging was guessed from the row count.** `rows` does not describe the page: the
 *    endpoint answers 20 documents for `start=0` and 40 for any `start > 0`, whatever
 *    `rows` says. `rooms.size >= 20` was therefore true for every page and only stopped
 *    when the server happened to return nothing — and it measured the *mapped* rooms, so a
 *    document dropped for having no room id shortened the page and could end the list
 *    early. The response carries `numFound` and echoes `start`, which decide it exactly.
 */
class HuyaSearchTest {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  private fun fixture(name: String): String =
    HuyaSearchTest::class.java.getResourceAsStream("/fixtures/$name")!!
      .bufferedReader()
      .use { it.readText() }

  private val searchRoot: JsonObject by lazy {
    json.parseToJsonElement(fixture("huya-search.json")).jsonObject
  }

  private val bucket: JsonObject by lazy {
    searchRoot["response"]!!.jsonObject["1"]!!.jsonObject
  }

  private val docs: JsonArray by lazy { bucket["docs"]!!.jsonArray }

  /** A `HuyaApi` whose transport fails loudly: these tests are all offline. */
  private val api = HuyaApi(
    object : HttpClient {
      override suspend fun execute(request: Request): HttpResponse =
        error("this test must not perform network calls")
    },
  )

  // ------------------------------------------------------------------ mapping

  @Test
  fun `a captured search result keeps the popularity the payload provides`() {
    val first = api.parseSearchRoom(docs.first())

    assertNotNull(first)
    assertEquals("660000", first!!.roomId)
    assertEquals("虎牙英雄联盟赛事", first.streamerName)
    assertTrue(first.title.contains("TES vs iG"), "title came from live_intro: ${first.title}")
    // The number the endpoint reported for this document, not a placeholder zero.
    assertEquals(17_863_363L, first.viewerCount)
    assertTrue(first.isLive)
    assertTrue(first.avatarUrl!!.startsWith("http"))
  }

  /** Without a popularity field the card must stay at 0 rather than invent a number. */
  @Test
  fun `a document without a popularity field reports zero viewers`() {
    val bare = json.parseToJsonElement(
      """{"room_id":42,"game_nick":"某人","live_intro":"标题","gameLiveOn":true}""",
    )

    assertEquals(0L, api.parseSearchRoom(bare)!!.viewerCount)
  }

  /**
   * `gameLiveOn` arrives as a JSON boolean; a string "false" must not read as live.
   *
   * Both encodings exist across Huya endpoints, so the mapper has to accept either — but
   * an offline anchor shown with a LIVE badge is the failure that matters. Only 6 of the
   * capture's 20 anchors were live, so reading this field wrongly would mislabel most of
   * the page.
   */
  @Test
  fun `the live flag is read as a boolean for every captured document`() {
    val rooms = docs.mapNotNull { api.parseSearchRoom(it) }

    assertEquals(20, rooms.size, "no captured document should be dropped")
    assertEquals(6, rooms.count { it.isLive }, "6 of the 20 captured anchors were live")

    val asString = json.parseToJsonElement(
      """{"room_id":7,"gameLiveOn":"false","game_nick":"n"}""",
    )
    assertFalse(api.parseSearchRoom(asString)!!.isLive)
  }

  // ------------------------------------------------------------------ paging

  /**
   * The advance is the number of documents consumed, which is not the requested page size.
   *
   * Measured live on one query: `start=0` → 20 documents, `start=20` → 40 documents. A
   * page-number cursor multiplied by 20 would ask for `start=40` after the second page and
   * skip the 20 results the server had already handed over.
   */
  @Test
  fun `the next start advances by the documents the server returned`() {
    assertEquals(20, api.nextSearchStart(bucket, requestedStart = 0, docCount = 20))
    assertEquals(60, api.nextSearchStart(bucket, requestedStart = 20, docCount = 40))
  }

  /** The `start` the server echoes wins over the one that was requested. */
  @Test
  fun `the reported start is used when present`() {
    val echoing = json.parseToJsonElement(
      """{"start":80,"numFound":1507}""",
    ).jsonObject

    // Asked for 0 but the server says this window began at 80: the next request must
    // continue from 80 + 20, not from 0 + 20.
    assertEquals(100, api.nextSearchStart(echoing, requestedStart = 0, docCount = 20))
  }

  @Test
  fun `the last page ends when numFound is reached`() {
    val nearEnd = json.parseToJsonElement(
      """{"start":1480,"numFound":1507,"docs":[]}""",
    ).jsonObject

    assertNull(api.nextSearchStart(nearEnd, requestedStart = 1480, docCount = 40))
  }

  /**
   * An empty page ends the sequence even though `numFound` still promises results.
   *
   * Verified live: a query reporting `numFound: 1507` answers `numFound: 0` with no
   * documents once `start` passes roughly 1520. Trusting the total alone would keep asking
   * for pages the server will never serve.
   */
  @Test
  fun `an empty page ends even when the total promises more`() {
    assertNull(api.nextSearchStart(bucket, requestedStart = 1540, docCount = 0))
    assertNull(api.nextSearchStart(bucket, requestedStart = 0, docCount = 0))
  }

  @Test
  fun `a response without numFound ends instead of looping`() {
    val noTotal = json.parseToJsonElement("""{"start":0,"docs":[]}""").jsonObject

    assertNull(api.nextSearchStart(noTotal, requestedStart = 0, docCount = 20))
    assertNull(api.nextSearchStart(null, requestedStart = 0, docCount = 20))
  }

  /**
   * Dropped documents still count as consumed.
   *
   * A document with no room id cannot be shown, so it is not in [HuyaSearchPage.rooms] —
   * but the server still spent one result on it. Advancing by the mapped count re-requested
   * the same window forever, which is why the rule takes the raw document count.
   */
  @Test
  fun `documents dropped by the mapper still advance the cursor`() {
    val withGarbage = json.parseToJsonElement(
      """{"start":0,"numFound":1507,"docs":[{"room_id":0},{"room_id":0}]}""",
    ).jsonObject

    val mapped = withGarbage["docs"]!!.jsonArray.mapNotNull { api.parseSearchRoom(it) }
    assertTrue(mapped.isEmpty(), "documents with room_id 0 must not become rooms")
    assertEquals(2, api.nextSearchStart(withGarbage, requestedStart = 0, docCount = 2))
  }

  // --------------------------------------------------------------- end to end

  /**
   * The adapter turns that rule into a cursor: `null` ends the list, otherwise it is the
   * next `start` — not a page number, because there is no page size to multiply.
   */
  @Test
  fun `the adapter hands out the next start as the cursor`() = runBlocking {
    val http = object : HttpClient {
      var calls = 0

      override suspend fun execute(request: Request): HttpResponse {
        calls += 1
        val body = if (request.url.queryParameter("start") == "0") {
          """{"response":{"1":{"start":0,"numFound":1507,"docs":[
               {"room_id":660000,"game_nick":"虎牙英雄联盟赛事","live_intro":"t",
                "gameLiveOn":true,"game_activityCount":17863363}]}}}"""
        } else {
          """{"response":{"1":{"start":40,"numFound":42,"docs":[
               {"room_id":1,"game_nick":"a"},{"room_id":2,"game_nick":"b"}]}}}"""
        }
        return HttpResponse(code = 200, body = body, headers = emptyMap(), url = request.url.toString())
      }
    }
    val adapter = HuyaAdapter(http = http, webSocketClient = okhttp3.OkHttpClient())

    // The first page reports 1507 results, so the cursor is the offset just past the one
    // document it returned — a page number would have been "2".
    val first = adapter.search("英雄联盟", cursor = null)
    assertEquals("1", first.nextCursor)
    assertEquals(1, first.rooms.size)

    val last = adapter.search("英雄联盟", cursor = "40")
    assertNull(last.nextCursor, "42 results ending at 42 must stop")
    assertEquals(2, last.rooms.size)
  }
}
