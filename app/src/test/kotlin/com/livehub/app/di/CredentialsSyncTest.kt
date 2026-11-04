package com.livehub.app.di

import androidx.test.core.app.ApplicationProvider
import com.livehub.core.model.Platform
import com.livehub.core.sync.Hlc
import com.livehub.core.sync.SyncRecord
import com.livehub.core.sync.SyncScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Login state as a synchronised record.
 *
 * The cookie stores stay the single place a request reads credentials from; the record adds
 * the clock and the tombstone so "logged in on the phone" and "logged out on the tablet" have
 * one winner instead of both sides quietly restoring their own copy.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CredentialsSyncTest {

  private lateinit var container: AppContainer

  private val now = 1_700_000_000_000L

  @Before
  fun setUp() {
    container = AppContainer(ApplicationProvider.getApplicationContext())
    container.preferences.update { UserSettings() }
    // The record table is reset above; the two cookie stores live in their own files.
    container.bilibiliCookieStore.clear()
    container.douyinCookieStore.clear()
  }

  private fun credentialTarget() =
    requireNotNull(container.syncTargets.targetFor(SyncScope.CREDENTIALS))

  private fun record(key: String, header: String?, deleted: Boolean, physical: Long): SyncRecord<JsonObject> =
    SyncRecord(
      key = key,
      value = header?.let { buildJsonObject { put("header", JsonPrimitive(it)) } },
      deleted = deleted,
      hlc = Hlc(physical, 0),
      deviceId = container.preferences.deviceId,
      updatedAt = physical,
    )

  @Test
  fun `a login is recorded and reaches the cookie store requests read`() {
    container.credentialStore.set(
      platform = Platform.BILIBILI,
      header = "SESSDATA=abc; bili_jct=def",
      hlc = Hlc(now, 0),
      deviceId = container.preferences.deviceId,
      now = now,
    )

    assertEquals("SESSDATA=abc; bili_jct=def", container.bilibiliCookieStore.header())
    assertTrue(container.credentialStore.isLoggedIn(Platform.BILIBILI))
    assertEquals("login:bilibili", container.credentialStore.records().single().key)
  }

  /** A logout must be a record, or the peer restores the login on the next exchange. */
  @Test
  fun `logging out leaves a tombstone instead of dropping the record`() {
    container.credentialStore.set(
      platform = Platform.BILIBILI,
      header = "SESSDATA=abc",
      hlc = Hlc(now, 0),
      deviceId = container.preferences.deviceId,
      now = now,
    )
    container.credentialStore.set(
      platform = Platform.BILIBILI,
      header = null,
      hlc = Hlc(now + 1_000, 0),
      deviceId = container.preferences.deviceId,
      now = now + 1_000,
    )

    val record = container.credentialStore.records().single()
    assertTrue("the removal must be a tombstone", record.deleted)
    assertEquals("", container.bilibiliCookieStore.header())
    assertFalse(container.credentialStore.isLoggedIn(Platform.BILIBILI))
  }

  @Test
  fun `an incoming login from the peer fills the local cookie store`() {
    val applied = credentialTarget().apply(
      listOf(record("login:douyin", "ttwid=t; sessionid=s; sid_tt=s", deleted = false, physical = now)),
    )

    assertEquals(1, applied)
    assertTrue(container.douyinCookieStore.header().contains("sessionid=s"))
    assertTrue(container.credentialStore.isLoggedIn(Platform.DOUYIN))
  }

  @Test
  fun `an incoming tombstone clears the local login`() {
    container.credentialStore.set(
      platform = Platform.DOUYIN,
      header = "ttwid=t; sessionid=s",
      hlc = Hlc(now, 0),
      deviceId = container.preferences.deviceId,
      now = now,
    )

    credentialTarget().apply(
      listOf(record("login:douyin", header = null, deleted = true, physical = now + 5_000)),
    )

    assertEquals("", container.douyinCookieStore.header())
    assertFalse(container.credentialStore.isLoggedIn(Platform.DOUYIN))
  }
}
