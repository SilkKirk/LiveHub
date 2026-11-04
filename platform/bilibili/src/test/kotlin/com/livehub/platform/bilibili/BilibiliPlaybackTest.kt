package com.livehub.platform.bilibili

import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import kotlinx.coroutines.runBlocking
import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `getRoomPlayInfo` parsing, against a captured response.
 *
 * `fixtures/bilibili-playinfo.json` is a live answer for room 545068 (2026-11-04): six codec
 * entries, all `qn=250`, each with **two** CDN hosts and a different host pair per container —
 * `flv` on `d1--cn-gotcha09`, `ts` on `…104`, `fmp4` on `…209`. That shape is what makes the
 * line axis tractable, and it is also why the old code could not offer one: it read only
 * `url_info[0]`, so every quality had exactly one host and `preferredLine` had nothing to
 * select.
 */
private class FixedHttp(private val body: String) : HttpClient {
  var requestedUrl: String = ""
    private set

  override suspend fun execute(request: Request): HttpResponse {
    requestedUrl = request.url.toString()
    return HttpResponse(200, body, emptyMap(), request.url.toString())
  }
}

class BilibiliPlaybackTest {

  private fun fixture(name: String): String =
    BilibiliPlaybackTest::class.java.getResourceAsStream("/fixtures/$name")!!
      .bufferedReader()
      .use { it.readText() }

  private var http = FixedHttp(fixture("bilibili-playinfo.json"))

  private fun api() = BilibiliApi(http, InMemoryCookieStore())

  private suspend fun playback(preferredQn: Int? = null, preferredLine: String? = null) =
    api().playback(roomId = ROOM_ID, preferredQn = preferredQn, preferredLine = preferredLine)

  @Test
  fun `six codec entries collapse into one quality`() = runBlocking {
    val source = playback()

    assertEquals(1, source.variants.size)
    assertEquals(BilibiliQuality.QN_HD, source.variants.single().qualityCode)
    assertEquals("高清", source.variants.single().qualityLabel)
    // fmp4 is the best-ranked container (index.m3u8) and its avc flavour is listed first.
    assertTrue(source.defaultVariant!!.isHls, source.defaultVariant!!.url)
    assertTrue(source.defaultVariant!!.url.contains("index.m3u8"))
  }

  @Test
  fun `the line axis lists both CDNs of the chosen container`() = runBlocking {
    val source = playback()

    assertEquals(
      listOf("d1--cn-gotcha209.bilivideo.com", "d1--cn-gotcha209b.bilivideo.com"),
      source.lines,
    )
    assertEquals("d1--cn-gotcha209.bilivideo.com", source.selectedLine)
  }

  /** The defect this test exists for: the second line used to resolve to the first host again. */
  @Test
  fun `a preferred line switches the url to that CDN`() = runBlocking {
    val source = playback(preferredLine = "d1--cn-gotcha209b.bilivideo.com")

    assertEquals("d1--cn-gotcha209b.bilivideo.com", source.selectedLine)
    assertEquals("d1--cn-gotcha209b.bilivideo.com", source.defaultVariant!!.lineName)
    assertTrue(source.defaultVariant!!.url.startsWith("https://d1--cn-gotcha209b.bilivideo.com/"))
  }

  @Test
  fun `an unknown or stale line falls back to the first CDN`() = runBlocking {
    val source = playback(preferredLine = "d1--cn-gotcha09.bilivideo.com")

    // That host belongs to the flv container, which loses to fmp4; the request must still
    // produce a playable URL rather than an error.
    assertEquals("d1--cn-gotcha209.bilivideo.com", source.selectedLine)
    assertNotNull(source.defaultVariant)
  }

  @Test
  fun `a quality the room does not offer falls back to the best one`() = runBlocking {
    val source = playback(preferredQn = BilibiliQuality.QN_ULTRA_HD)

    assertEquals(BilibiliQuality.QN_HD, source.defaultVariant!!.qualityCode)
  }

  @Test
  fun `the url carries the host the path and the signed query`() = runBlocking {
    val url = playback().defaultVariant!!.url

    assertTrue(url.startsWith("https://d1--cn-gotcha209.bilivideo.com/live-bvc/690443/"), url)
    assertTrue(url.contains("expires="), url)
    assertTrue(url.contains("&qn=250") || url.contains("qn=250"), url)
  }

  @Test
  fun `the request asks for the captured room`() = runBlocking {
    playback()

    assertTrue(http.requestedUrl.contains("room_id=$ROOM_ID"), http.requestedUrl)
    assertTrue(http.requestedUrl.contains("qn=10000"), http.requestedUrl)
  }

  private companion object {
    const val ROOM_ID = "545068"
  }
}
