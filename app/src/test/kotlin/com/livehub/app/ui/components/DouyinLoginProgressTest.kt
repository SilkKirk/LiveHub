package com.livehub.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The cookie handoff the Douyin login sheet depends on.
 *
 * Login happens on `sso.douyin.com` (host-scoped cookies), while every REST call reads
 * `www.douyin.com`. Treating a passport session as "done" would save a header the API cannot
 * use; treating it as "waiting" would leave the sheet spinning forever after a successful scan.
 */
class DouyinLoginProgressTest {

  @Test
  fun `nothing yet means waiting`() {
    assertEquals(DouyinLoginProgress.Waiting, douyinLoginProgress("ttwid=abc", "ttwid=abc"))
    assertEquals(DouyinLoginProgress.Waiting, douyinLoginProgress("", ""))
  }

  @Test
  fun `a session on the passport host asks for the site handoff`() {
    assertEquals(
      DouyinLoginProgress.Handoff("https://www.douyin.com"),
      douyinLoginProgress(siteCookie = "ttwid=abc", ssoCookie = "sessionid=abc; sid_tt=abc"),
    )
  }

  @Test
  fun `a session on the site finishes the login`() {
    assertEquals(
      DouyinLoginProgress.Done("ttwid=abc; sessionid=abc"),
      douyinLoginProgress(
        siteCookie = "ttwid=abc; sessionid=abc",
        ssoCookie = "sessionid=abc",
      ),
    )
  }
}
