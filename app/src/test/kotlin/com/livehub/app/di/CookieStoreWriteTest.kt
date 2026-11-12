package com.livehub.app.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Credential writes: visible immediately, durable a moment later.
 *
 * Both halves are load-bearing. `save` used to be a `runBlocking { dataStore.edit { … } }` on the
 * caller's thread — which for a 退出登录 tap is the main thread — so now that the write is queued the
 * new cookie has to be published to memory *before* the hand-off, or the next request would carry
 * the old one. And a queued write that never reached disk would quietly log the user out on the next
 * cold start.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CookieStoreWriteTest {

  private val context get() = ApplicationProvider.getApplicationContext<Context>()

  private lateinit var bilibili: DataStoreBilibiliCookies
  private lateinit var douyin: DataStoreDouyinCookies

  @Before
  fun setUp() {
    bilibili = DataStoreBilibiliCookies(context)
    douyin = DataStoreDouyinCookies(context)
    bilibili.clear()
    douyin.clear()
    bilibili.awaitWrites()
    douyin.awaitWrites()
  }

  @Test
  fun `a saved bilibili cookie is readable straight away`() {
    bilibili.save("SESSDATA=abc; bili_jct=def")

    assertEquals("SESSDATA=abc; bili_jct=def", bilibili.header())
  }

  @Test
  fun `a saved bilibili cookie survives a restart`() {
    bilibili.save("SESSDATA=abc")
    bilibili.awaitWrites()

    // A new instance over the same file reads from disk rather than from the cache above.
    assertEquals("SESSDATA=abc", DataStoreBilibiliCookies(context).header())
  }

  @Test
  fun `a cleared bilibili cookie is gone for the next instance`() {
    bilibili.save("SESSDATA=abc")
    bilibili.awaitWrites()
    bilibili.clear()
    bilibili.awaitWrites()

    assertEquals("", DataStoreBilibiliCookies(context).header())
  }

  @Test
  fun `a saved douyin session survives a restart and keeps the negotiated ttwid`() {
    douyin.save("ttwid=t; sessionid=s")
    douyin.awaitWrites()

    val restarted = DataStoreDouyinCookies(context).header()
    assertTrue("got $restarted", restarted.contains("sessionid=s"))
    assertTrue("the negotiated ttwid must not be dropped, got $restarted", restarted.contains("ttwid=t"))
  }
}
