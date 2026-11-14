package com.livehub.app

import android.os.NetworkOnMainThreadException
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.livehub.app.di.AppContainer
import com.livehub.app.di.UserSettings
import com.livehub.app.sync.SyncController
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.atomic.AtomicReference

/**
 * The sync path must never open its sockets on the main thread.
 *
 * Regression test for the defect behind a bare "同步失败" that appeared the moment a user
 * confirmed a pairing code: `pair()` was a blocking function called from `viewModelScope`,
 * so `Socket.connect` ran on the UI thread and Android threw
 * [NetworkOnMainThreadException]. That exception carries a **null** message, so the error
 * handler collapsed it into a generic failure string with no cause in it.
 *
 * Every other test missed it because they drive the transport from a JVM test thread or a
 * plain instrumentation thread, neither of which enforces the policy. Only the main thread
 * does.
 *
 * Method names are camelCase rather than backticked sentences on purpose: Kotlin turns a
 * backticked name into a class name containing spaces, and D8 refuses those below DEX
 * version 040, so `assembleDebugAndroidTest` fails outright.
 */
@RunWith(AndroidJUnit4::class)
class SyncOffMainThreadTest {

  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  private lateinit var container: AppContainer
  private lateinit var controller: SyncController

  @Before
  fun setUp() {
    container = AppContainer(context)
    container.preferences.update { UserSettings() }
    // Peers live under their own key, so resetting UserSettings leaves stale ones behind;
    // a leftover peer would make syncNow attempt a real connection here.
    container.preferences.saveTrustedPeers(emptyList())
    controller = container.syncController
    // The transport only exists once the LAN server is running, which is what navigating to
    // the sync screen does.
    controller.start()
    controller.beginPairing()
  }

  @After
  fun tearDown() {
    controller.stop()
  }

  /**
   * Runs [block] on the real main thread and returns whatever it returns.
   *
   * The value is returned as-is, not wrapped in a `Result`: several callers already return
   * a `Result` of their own, and wrapping it again (`Result<Result<T>>`) made
   * `outcome.isFailure` report whether the *harness* threw rather than whether the call
   * failed — which is how an earlier version of this test passed a pairing that actually
   * succeeded. A throw from [block] is rethrown here, on the calling thread.
   */
  private fun <T> onMainThread(block: suspend () -> T): T {
    val value = AtomicReference<T>()
    val failure = AtomicReference<Throwable>()
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      try {
        value.set(runBlocking { block() })
      } catch (error: Throwable) {
        failure.set(error)
      }
    }
    failure.get()?.let { throw it }
    @Suppress("UNCHECKED_CAST")
    return value.get() as T
  }

  /**
   * Proves this process really does forbid main-thread sockets.
   *
   * Without it the other assertions would be vacuous: if the platform guard were not armed,
   * a regressed `pair()` would still report a connection error and silently pass. Asserting
   * the guard first means a failure here reads as "this environment cannot test the
   * regression", not as a false green.
   */
  @Test
  fun mainThreadForbidsNetwork() {
    val outcome = AtomicReference<Result<Unit>>()
    InstrumentationRegistry.getInstrumentation().runOnMainSync {
      outcome.set(
        runCatching {
          Socket().use { it.connect(InetSocketAddress("127.0.0.1", 1), 500) }
        },
      )
    }

    assertTrue(
      "the main thread did not reject a raw socket, so this test cannot detect the " +
        "regression: ${outcome.get().exceptionOrNull()}",
      outcome.get().exceptionOrNull() is NetworkOnMainThreadException,
    )
  }

  /**
   * Pairing from the UI thread must fail for a network reason, not for the thread guard.
   *
   * The sweep runs against this device's own /24, so nothing answers and the result is a
   * reported "not found" rather than a successful pairing. What matters is *where* the
   * socket work happened: on the main thread the platform throws
   * [NetworkOnMainThreadException] with a null message, and the failure text then has no
   * cause in it.
   */
  @Test
  fun pairingFromMainThreadReportsNetworkReason() {
    val outcome = onMainThread {
      controller.pairByCode("123456")
    }

    assertTrue("pairing with no device showing that code must not succeed", outcome.isFailure)
    val reason = outcome.exceptionOrNull()?.message.orEmpty()

    assertFalse(
      "pairing opened a socket on the main thread: $reason",
      reason.contains(NetworkOnMainThreadException::class.java.simpleName),
    )
    assertFalse("the failure carries no cause: '$reason'", reason.isBlank())
    // One of the two real outcomes: nothing found, or a device refused the code. Both are
    // produced by the socket path actually running; neither is a generic "同步失败".
    assertTrue(
      "unexpected failure reason: $reason",
      reason.contains("没有找到") || reason.contains("配对码") || reason.contains("匹配码"),
    )
  }

  /**
   * A full exchange is safe to start from the UI thread, which is where the sync button
   * calls it. With no paired peers it returns before opening a socket, but the call still
   * has to cross into IO without tripping the guard on the way.
   */
  @Test
  fun syncingFromMainThreadCompletes() {
    val summary = onMainThread { controller.syncNow() }

    assertTrue(summary.pulled == 0 && summary.pushed == 0)
    assertTrue(summary.message, summary.message.contains("尚未配对"))
  }

  /**
   * `stop()` leaves the controller in a state where it will not serve anything.
   *
   * A real-socket probe was attempted here and **does not work**, which is worth recording so
   * nobody re-adds it: after `ServerSocket.close()` a connect to the same port still succeeded on
   * the emulator, twice — once as a rebind (`BindException: Address already in use`, the port sits
   * in `TIME_WAIT`) and once as a connect probe (`nothing may accept a connection after stop()`
   * failed). The listener is closed, but a connection that was already queued in the backlog, or
   * completed during the race, still lands. Neither observation is a bug in `stop()`.
   *
   * What `stop()` actually guarantees is that the accept loop exits and the transport is dropped,
   * so that is what is asserted — through the same flag the loop reads.
   */
  @Test
  fun stoppingLeavesTheControllerNotRunning() {
    assertTrue("the server must be running to begin with", controller.isRunning)
    assertTrue("and it must be listening on a real port", controller.listeningPort > 0)

    controller.stop()

    assertFalse("stop() must leave the controller not running", controller.isRunning)
    assertTrue("and no code may be left showing", controller.pairingCode.isNullOrBlank())
  }

  /**
   * `startAsync()` survives being called from the main thread — which is where it is called from.
   *
   * Every arrival in the foreground asks for the server from `MainActivity.onStart`, i.e. on the UI
   * thread, so this is the entry point a regression would break first.
   *
   * **What this proves, and what it does not.** Android's main-thread network guard is enforced per
   * socket operation inside libcore, and `mainThreadForbidsNetwork` above only establishes that
   * `connect` is one of the operations it covers. Whether it also covers `ServerSocket.bind` varies
   * by API level and is **not** verified here. So a green run means exactly this: calling the
   * function on the main thread returned an address and left the server listening. The thread
   * attribution — the whole point of moving the bind — rests on `SyncController.startAsync`'s
   * `withContext(Dispatchers.IO)` and on nothing else. Do not read this test as a direct proof of
   * the hop; if the guard happens to cover `bind` on some API level it is a stronger net, not a
   * documented one.
   *
   * `stop()` first is load-bearing rather than tidiness: `setUp` already left a server running, and
   * a second start against a live one early-returns with the existing address — proving nothing
   * about binding. Stopping forces this call to bind a fresh socket.
   *
   * The address assertion assumes at least one non-loopback IPv4 interface. Every emulator and
   * normal phone has one (the emulator's is `10.0.2.15`); a device with none gets an empty string by
   * design, and this fails loudly rather than papering over it.
   */
  @Test
  fun startAsyncFromMainThreadCompletes() {
    controller.stop()
    assertFalse("the controller must be stopped for this to test a real bind", controller.isRunning)

    val outcome = runCatching { onMainThread { controller.startAsync() } }

    assertTrue(
      "startAsync must be callable from the main thread: ${outcome.exceptionOrNull()}",
      outcome.isSuccess,
    )
    assertTrue("startAsync must leave the server listening", controller.isRunning)
    val address = outcome.getOrNull().orEmpty()
    assertTrue(
      "startAsync must return the address peers can use, but returned '$address'",
      address.isNotEmpty(),
    )
  }
}
