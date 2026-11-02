package com.livehub.app

import android.os.StrictMode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.livehub.app.di.AppContainer
import com.livehub.app.sync.SyncController
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicReference

/**
 * The sync path must never open its sockets on the main thread.
 *
 * This is a regression test for the defect behind a bare "同步失败" that appeared the
 * moment a user confirmed a pairing code (v0.9.1 and earlier): `pair()` was a blocking
 * function called from `viewModelScope`, so `Socket.connect` ran on the UI thread and
 * Android threw `NetworkOnMainThreadException`. That exception carries a **null** message,
 * so the error handler collapsed it into a generic failure string with no cause in it.
 *
 * Every existing test missed it for the same reason: they drive the transport from a JVM
 * test thread or a plain instrumentation thread, neither of which enforces the network
 * policy. Only the main thread does.
 *
 * The policy is installed explicitly rather than assumed, so the test reproduces the
 * production conditions deterministically instead of depending on whatever mask the
 * framework happens to set for the main looper.
 */
@RunWith(AndroidJUnit4::class)
class SyncOffMainThreadTest {

  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  private lateinit var container: AppContainer
  private lateinit var controller: SyncController

  @Before
  fun setUp() {
    container = AppContainer(context)
    container.preferences.update { com.livehub.app.di.UserSettings() }
    // Peers live under their own key, so resetting UserSettings leaves stale ones behind;
    // a leftover peer would make syncNow attempt a real connection here.
    container.preferences.saveTrustedPeers(emptyList())
    controller = container.syncController
    // The transport only exists once the LAN server is running, which is what navigating
    // to the sync screen does.
    controller.start()
    controller.beginPairing()
  }

  @After
  fun tearDown() {
    controller.stop()
  }

  /**
   * Runs [block] on the real main thread with the network-on-main-thread guard armed.
   *
   * `runOnMainSync` posts to the main looper, so the block executes on the actual UI
   * thread — the one that has the policy in production. The guard is saved and restored
   * so the mask cannot leak into other tests.
   *
   * [block] is suspending and the production code dispatches to IO internally, so
   * `runBlocking` parks the main thread while the socket work happens elsewhere and
   * resumes it on completion. If that dispatch were removed the socket would be opened
   * here and throw, which is exactly the failure this test is meant to catch.
   */
  private fun <T> onMainThreadWithNetworkGuard(block: suspend () -> T): T {
    val result = AtomicReference<Result<T>>()
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      val original = StrictMode.getThreadPolicy()
      StrictMode.setThreadPolicy(
        StrictMode.ThreadPolicy.Builder()
          .detectNetwork()
          .penaltyDeathOnNetwork()
          .build(),
      )
      try {
        result.set(runCatching { runBlocking { block() } })
      } finally {
        StrictMode.setThreadPolicy(original)
      }
    }
    return result.get().getOrThrow()
  }

  @Test
  fun `pairing from the main thread fails for a network reason, not the thread guard`() {
    // Port 1 on loopback is closed everywhere, so this exercises the real socket path and
    // fails fast with ECONNREFUSED instead of waiting out the 8s timeout.
    val outcome = onMainThreadWithNetworkGuard {
      controller.pair(peerName = "对端", address = "127.0.0.1:1", code = "123456")
    }

    assertTrue("a closed port must not pair successfully", outcome.isFailure)
    val reason = outcome.exceptionOrNull()?.message.orEmpty()

    assertFalse("pairing opened a socket on the main thread: $reason", reason.contains("NetworkOnMainThread"))
    // The old code produced "无法连接到该设备" from a null message; a blank or generic
    // string means the cause was lost the same way it was in production.
    assertFalse("the failure carries no cause: '$reason'", reason.isBlank() || reason == "同步失败")
    assertTrue("unexpected failure reason: $reason", reason.contains("拒绝连接"))
  }

  /**
   * A full exchange is safe to start from the UI thread, which is where the sync button
   * calls it. With no paired peers it returns before opening a socket, but the call still
   * has to cross into IO without tripping the guard on the way.
   */
  @Test
  fun `syncing from the main thread completes without a thread violation`() {
    val summary = onMainThreadWithNetworkGuard { controller.syncNow() }
    assertTrue(summary.pulled == 0 && summary.pushed == 0)
    assertTrue(summary.message.contains("尚未配对"))
  }
}
