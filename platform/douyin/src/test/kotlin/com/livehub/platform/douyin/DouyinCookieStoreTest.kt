package com.livehub.platform.douyin

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What counts as "logged in" for Douyin, and what survives a second write.
 *
 * Two cookies matter and they arrive at different times: `ttwid` from the anonymous
 * negotiation that every request needs, `sessionid` only after a QR login. The store used to
 * replace its whole value on write, so whichever arrived second silently removed the other —
 * "logged in, but search still asks me to log in".
 */
class DouyinCookieStoreTest {

  @Test
  fun `the negotiated ttwid is not a login`() {
    val store = DouyinCookieStore.inMemory()
    store.save("ttwid=abc123")

    assertFalse(store.isLoggedIn(), "ttwid is the anonymous cookie, not a session")
  }

  @Test
  fun `a session cookie is a login`() {
    listOf("sessionid=x", "sessionid_ss=x", "sid_tt=x").forEach { cookie ->
      val store = DouyinCookieStore.inMemory()
      store.save("ttwid=abc123; $cookie")

      assertTrue(store.isLoggedIn(), "$cookie must count as a session")
    }
  }

  @Test
  fun `saving a login keeps the negotiated ttwid`() {
    val store = DouyinCookieStore.inMemory()
    store.save("ttwid=abc123")
    store.save("sessionid=def456")

    val header = store.header()
    assertTrue(header.contains("ttwid=abc123"), header)
    assertTrue(header.contains("sessionid=def456"), header)
    assertTrue(store.isLoggedIn())
  }

  /** The later value wins, so a re-login refreshes the session without dropping the rest. */
  @Test
  fun `merging overwrites duplicate names`() {
    assertEquals(
      "ttwid=old; sessionid=new",
      mergeDouyinCookies("ttwid=old; sessionid=old", "sessionid=new"),
    )
  }

  @Test
  fun `clearing removes the session as well as the ttwid`() {
    val store = DouyinCookieStore.inMemory()
    store.save("ttwid=abc123; sessionid=def456")
    store.clear()

    assertEquals("", store.header())
    assertFalse(store.isLoggedIn())
  }
}
