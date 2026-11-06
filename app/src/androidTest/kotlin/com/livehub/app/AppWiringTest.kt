package com.livehub.app

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.livehub.app.di.AppContainer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests that run on a device/emulator.
 *
 * They verify the wiring that plain JVM tests cannot reach: that the container builds
 * every platform adapter, that DataStore persistence works against the real Android
 * framework, and that the follow list survives a store round trip.
 */
@RunWith(AndroidJUnit4::class)
class AppWiringTest {

  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  @Test
  fun containerCreatesAdaptersForEveryPlatform() {
    val container = AppContainer(context)

    val platforms = container.platformRegistry.supportedPlatforms().map { it.id }.sorted()
    assertEquals(listOf("bilibili", "douyin", "douyu", "huya"), platforms)

    container.adapters.forEach { adapter ->
      assertNotNull("adapter for ${adapter.platform} must be registered", adapter)
    }
  }

  @Test
  fun deviceIdIsStableAndPositive() {
    val container = AppContainer(context)
    val first = container.preferences.deviceId
    val second = container.preferences.deviceId

    assertTrue("device id must be positive", first > 0)
    assertEquals(first, second)
  }

  @Test
  fun followListRoundTripsThroughDataStore() {
    val container = AppContainer(context)
    val store = container.libraryStore
    store.replaceAll(emptyList())

    val hlc = container.syncEngine.nextHlc()
    val deviceId = container.preferences.deviceId
    val now = System.currentTimeMillis()
    val streamer = com.livehub.core.model.Streamer(
      platform = com.livehub.core.model.Platform.DOUYU,
      roomId = "999999",
      streamerName = "测试主播",
      title = "测试直播间",
      isLive = true,
    )

    store.toggle(streamer, hlc, deviceId, now)
    val followed = store.followed()
    assertEquals(1, followed.size)
    assertEquals("测试主播", followed.first().streamerName)
    assertTrue(store.isFollowed(com.livehub.core.model.Platform.DOUYU, "999999"))

    store.toggle(streamer, container.syncEngine.nextHlc(), deviceId, now + 1)
    assertTrue(store.followed().isEmpty())

    store.replaceAll(emptyList())
  }

  @Test
  fun syncEngineProducesAPayloadForFollows() {
    val container = AppContainer(context)
    val payload = container.syncEngine.buildPayload(com.livehub.core.sync.SyncScope.FOLLOWS)
    assertNotNull(payload)
    assertEquals("follows", payload!!.scope)
    assertTrue(payload.sequence > 0)
  }
}
