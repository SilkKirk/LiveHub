package com.livehub.app.ui.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The `Referer` each CDN demands.
 *
 * Every platform rejects a stream request that arrives without the right Referer — Douyu
 * answers HTTP 403, which is exactly the failure that made every Douyu room look
 * unplayable even after the signature was fixed. The origin is therefore derived from the
 * stream URL so no caller can forget it.
 */
class RefererForTest {

  @Test
  fun `douyu cdn urls get the douyu referer`() {
    assertEquals(
      "https://www.douyu.com/",
      refererFor("https://hls3-akm.douyucdn.cn/live/226037rXXX_2000.m3u8?wsAuth=abc"),
    )
    assertEquals(
      "https://www.douyu.com/",
      refererFor("http://tc-tct.douyucdn2.cn/dyliveflv1/1234r.flv?token=x"),
    )
  }

  @Test
  fun `huya cdn urls get the huya referer`() {
    assertEquals(
      "https://www.huya.com/",
      refererFor("http://al.hls.huya.com/src/1234-0-0-0-0-0.m3u8"),
    )
    assertEquals(
      "https://www.huya.com/",
      refererFor("http://ws-h5.huya.com/abc.flv"),
    )
  }

  @Test
  fun `bilibili cdn urls get the bilibili referer`() {
    assertEquals(
      "https://live.bilibili.com/",
      refererFor("https://cn-hbxy-cu-01-12.bilivideo.com/live-bvc/1234/live_1.m3u8"),
    )
  }

  @Test
  fun `douyin cdn urls get the douyin referer`() {
    assertEquals(
      "https://live.douyin.com/",
      refererFor("http://pull-hls-l11.douyincdn.com/thirdgame/stream-123_hd.m3u8"),
    )
  }

  @Test
  fun `matching is case insensitive`() {
    assertEquals(
      "https://www.douyu.com/",
      refererFor("HTTPS://HLS3-AKM.DOUYUCDN.CN/LIVE/X.M3U8"),
    )
  }

  @Test
  fun `an unknown host gets no referer rather than a wrong one`() {
    assertNull(refererFor("https://example.com/stream.m3u8"))
    assertNull(refererFor(""))
  }

  @Test
  fun `huya is checked before the generic game cdn names`() {
    // Huya streams are served from hosts that mention no platform; the referer has to be
    // right on the first try because a wrong one silently 403s.
    assertEquals(
      "https://www.huya.com/",
      refererFor("http://al.hls.huya.com/src/1.m3u8"),
    )
  }
}
