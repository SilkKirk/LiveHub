package com.livehub.platform.huya

import com.livehub.core.network.Crypto
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

class HuyaSignerTest {

  /** A realistic `fm` value: base64 of `PREFIX_timestamp_random`. */
  private fun fm(prefix: String = "abcdef"): String =
    Base64.getEncoder().encodeToString("${prefix}_1699999999999_abcdef".toByteArray())

  private fun antiCode(fm: String = fm(), ctype: String = "huya_live", fs: String = "bgct"): String =
    "fm=${java.net.URLEncoder.encode(fm, "UTF-8")}&ctype=$ctype&fs=$fs"

  @Test
  fun `parseAntiCode reads all three required fields`() {
    val parsed = HuyaSigner.parseAntiCode(antiCode())
    assertNotNull(parsed)
    assertEquals("huya_live", parsed!!.ctype)
    assertEquals("bgct", parsed.fs)
  }

  @Test
  fun `parseAntiCode decodes the HTML entity ampersand`() {
    val parsed = HuyaSigner.parseAntiCode("fm=x%3D%3D&amp;ctype=c&amp;fs=f")
    assertNotNull(parsed)
    assertEquals("c", parsed!!.ctype)
    assertEquals("f", parsed.fs)
  }

  @Test
  fun `parseAntiCode returns null when required fields are missing`() {
    assertNull(HuyaSigner.parseAntiCode(""))
    assertNull(HuyaSigner.parseAntiCode("ctype=c&fs=f"))
    assertNull(HuyaSigner.parseAntiCode("fm=x&fs=f"))
    assertNull(HuyaSigner.parseAntiCode("fm=x&ctype=c"))
  }

  @Test
  fun `signQuery emits the parameters in the order the CDN expects`() {
    val query = HuyaSigner.signQuery(
      antiCode = HuyaSigner.parseAntiCode(antiCode())!!,
      streamName = "stream1",
      t13 = 1_700_000_000_000,
      uid = 1_400_000_000_001,
      randomTail = 42,
    )
    val keys = query.split('&').map { it.substringBefore('=') }
    assertEquals(
      listOf(
        "wsSecret", "wsTime", "seqid", "ctype", "ver", "fs",
        "uuid", "u", "t", "sv", "sdk_sid", "codec",
      ),
      keys,
    )
    assertTrue(query.contains("&ver=1"))
    assertTrue(query.contains("&codec=264"))
    assertTrue(query.contains("&sv=${HuyaSigner.SDK_VERSION}"))
  }

  @Test
  fun `wsTime is the hex of the shifted timestamp`() {
    val t13 = 1_700_000_000_000
    val query = HuyaSigner.signQuery(
      antiCode = HuyaSigner.parseAntiCode(antiCode())!!,
      streamName = "s",
      t13 = t13,
      uid = 1L,
    )
    val expected = java.lang.Long.toHexString((t13 + HuyaSigner.WS_TIME_OFFSET) / 1000)
    assertEquals(expected, query.substringAfter("wsTime=").substringBefore('&'))
  }

  @Test
  fun `wsSecret is the md5 of the documented plain string`() {
    val t13 = 1_700_000_000_000L
    val uid = 1_400_000_000_001L
    val parsed = HuyaSigner.parseAntiCode(antiCode(prefix = "prefix"))!!
    val query = HuyaSigner.signQuery(
      antiCode = parsed,
      streamName = "stream1",
      t13 = t13,
      uid = uid,
    )

    val fmPlain = String(Crypto.base64Decode(Crypto.urlDecodeOnce(parsed.fm)), Charsets.UTF_8)
    val wsPrefix = fmPlain.substringBefore('_')
    val wsTime = java.lang.Long.toHexString((t13 + HuyaSigner.WS_TIME_OFFSET) / 1000)
    val hash = Crypto.md5Hex("${uid + t13}|${parsed.ctype}|${HuyaSigner.PARAMS_T}")
    val expected = Crypto.md5Hex("${wsPrefix}_${uid}_stream1_${hash}_$wsTime")

    assertEquals(expected, query.substringAfter("wsSecret=").substringBefore('&'))
  }

  @Test
  fun `buildStreamUrl joins the base, stream name and suffix`() {
    val url = HuyaSigner.buildStreamUrl(
      flvUrl = "https://cdn.huya.com/src/",
      streamName = "abc",
      suffix = "flv",
      antiCode = HuyaSigner.parseAntiCode(antiCode())!!,
      nowMillis = 1_700_000_000_000,
    )
    assertTrue(url.startsWith("https://cdn.huya.com/src/abc.flv?"))
    assertTrue(url.contains("wsSecret="))
  }

  @Test
  fun `cdnRank prefers al then hs then tx`() {
    assertEquals(0, HuyaSigner.cdnRank("AL"))
    assertEquals(1, HuyaSigner.cdnRank("hs"))
    assertEquals(2, HuyaSigner.cdnRank("tx"))
    assertEquals(3, HuyaSigner.cdnRank("other"))
  }

  @Test
  fun `postProcess downgrades to http and rewrites tx parameters`() {
    val url = "https://h.huya.com/s.flv?ctype=tars_mp&fs=bhct"
    val rewritten = HuyaSigner.postProcess(url, "tx")

    assertTrue(rewritten.startsWith("http://"))
    assertTrue(rewritten.contains("ctype=huya_webh5"))
    assertTrue(rewritten.contains("fs=bgct"))
  }

  @Test
  fun `postProcess keeps other cdn parameters intact`() {
    val url = "https://h.huya.com/s.flv?ctype=tars_mp&fs=bhct"
    val rewritten = HuyaSigner.postProcess(url, "al")

    assertTrue(rewritten.startsWith("http://"))
    assertTrue(rewritten.contains("ctype=tars_mp"))
  }
}
