package com.livehub.app.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livehub.app.di.AppContainer
import com.livehub.core.sync.TrustedPeer
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * When the sync screen's LAN server is allowed to be listening.
 *
 * **The conclusion this class was written for has been deliberately reversed.** It used to assert
 * that the server "must not outlive the screen": the old contract was "only while the sync screen is
 * open", which had a real cost — two devices could only ever meet while both users were parked on
 * that page, so the feature was a manual errand instead of something the app does. The lifetime is
 * now "the app is in the foreground, at least one device is paired, and 自动同步 is on" (see
 * `MainViewModel.autoSyncWanted`), which means the server is *supposed* to outlive the page.
 *
 * What did survive the reversal, and is what these tests now pin:
 *
 *  * the matching code is still the page's own credential — it dies with the page, because a code
 *    nobody is showing must not be accepted;
 *  * leaving the page still stops the server when nothing wants it (nothing paired, or the switch
 *    off), which is why the old "leaving stops it" assertions below still hold answers for the
 *    unpaired case — evaluated for the new reason;
 *  * the server never outlives the *app*: `onAppBackground` is what takes it down (see
 *    `AutoSyncTest`).
 *
 * `isRunning` reads `SyncHttpServer.isRunning`, the flag its own accept loop checks, so these are
 * the same property the KDoc promises rather than a proxy for it. Two earlier versions of the
 * "stopped" assertion were wrong and both failed on CI, so they are recorded rather than quietly
 * deleted:
 *
 *  * **rebinding the port** — `BindException: Address already in use`. `ServerSocket.close()`
 *    leaves the port in `TIME_WAIT` on Linux and `SO_REUSEADDR` does not override that for a
 *    socket that had accepted connections. Normal TCP behaviour, not a leak.
 *  * **connecting to the port and expecting a refusal** — no precedent in this repo for opening
 *    real sockets from a Robolectric test, and the one place sockets are exercised
 *    (`SyncOffMainThreadTest`) is an *instrumented* test for exactly that reason.
 *
 * The flag is the honest assertion here; a real-socket probe belongs on the emulator.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SyncServerLifecycleTest {

  private lateinit var container: AppContainer
  private lateinit var viewModel: MainViewModel

  @Before
  fun setUp() {
    container = AppContainer(ApplicationProvider.getApplicationContext())
    container.preferences.update { com.livehub.app.di.UserSettings() }
    container.preferences.saveTrustedPeers(emptyList())
    viewModel = MainViewModel(container)
    idle()
  }

  /**
   * Leaves no port — and no paired device — behind for the next test.
   *
   * A `ServerSocket` outliving its test is not a failure in itself (the preferred port falls back
   * to an ephemeral one), but it does make the *next* test's port surprising, and `isRunning` is
   * what several assertions here read. The peer list matters more: it is its own DataStore key, so
   * a test that pairs a device would hand every later class a non-empty list to explain.
   */
  @After
  fun tearDown() {
    if (::viewModel.isInitialized) viewModel.onAppBackground()
    if (::container.isInitialized) {
      container.syncController.stop()
      container.preferences.saveTrustedPeers(emptyList())
      // Nothing may still be in the write queue either: a settings write issued by this test must
      // not land in the middle of the next one.
      container.preferences.awaitWrites()
    }
  }

  private fun idle() {
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun awaitCondition(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
      idle()
      if (condition()) return
      Thread.sleep(10)
    }
    idle()
    assertTrue("condition not met within ${timeoutMillis}ms", condition())
  }

  /**
   * Pairs one device, the way the flow does, and mirrors it into the state.
   *
   * `UiAction.RefreshPeers` rather than poking `_state`: the policy reads `UiState.peers`, so the
   * list has to arrive through the same path the screen uses, or the test would be asserting on a
   * state the app can never be in.
   */
  private fun pairDevice() {
    container.preferences.saveTrustedPeers(
      listOf(TrustedPeer(2, "小米平板 6", "secret", 1_700_000_000_000)),
    )
    viewModel.onAction(UiAction.RefreshPeers)
    idle()
  }

  /**
   * The reversal, stated as a test.
   *
   * In the foreground with a paired device and 自动同步 on, walking off the sync page leaves the
   * server listening — that is the whole point of the round: the other device no longer has to
   * catch this one parked on a settings screen.
   */
  @Test
  fun `leaving the sync screen keeps the server when automatic sync wants it`() {
    pairDevice()
    viewModel.onAppForeground()
    awaitCondition { container.syncController.isRunning }

    viewModel.onAction(UiAction.Navigate(Screen.Sync))
    awaitCondition { viewModel.state.value.pairingCode.isNotBlank() }
    assertTrue(
      "the server must be listening while the screen is open",
      container.syncController.listeningPort > 0,
    )

    viewModel.onAction(UiAction.Navigate(Screen.Home))
    idle()

    assertTrue(
      "with a device paired and the app in the foreground, the server must outlive the page",
      container.syncController.isRunning,
    )
    // And the page's own credential still dies with it, even though the socket survives.
    assertTrue(
      "the code must be gone from the controller",
      container.syncController.pairingCode.isNullOrBlank(),
    )
    assertTrue(
      "the code must be gone from the state the screen renders",
      viewModel.state.value.pairingCode.isBlank(),
    )
  }

  /**
   * With nothing paired the old behaviour is still what happens — for the policy's reason now.
   *
   * An unpaired installation has nobody to talk to, so the server has no business listening once
   * the page that was using it is gone. This is the row of the new contract that keeps the old
   * assertion true, and writing it down matters: it is the difference between "the reversal applies
   * to everyone" and "the reversal applies once you have paired something".
   */
  @Test
  fun `leaving the sync screen stops the server when nothing is paired`() {
    viewModel.onAction(UiAction.Navigate(Screen.Sync))
    awaitCondition { container.syncController.isRunning }

    viewModel.onAction(UiAction.Navigate(Screen.Home))
    idle()

    assertFalse(
      "an unpaired installation must not keep a port open behind the sync page",
      container.syncController.isRunning,
    )
  }

  /**
   * The pairing code dies with the screen.
   *
   * The window is a live credential: a code still accepted by a server that is no longer showing it
   * is the worst of both worlds. It is revoked through `SyncController.cancelPairing()` now, which
   * exists precisely because `stop()` is no longer the only thing that has to revoke it. Both the
   * controller's code and the copy in `UiState` have to go, because the latter is what the screen
   * renders on the next visit before `beginPairing()` runs.
   */
  @Test
  fun `leaving the sync screen invalidates the matching code`() {
    viewModel.onAction(UiAction.Navigate(Screen.Sync))
    awaitCondition { viewModel.state.value.pairingCode.isNotBlank() }

    assertTrue(
      "the screen must be showing a code to begin with",
      viewModel.state.value.pairingCode.length == 6,
    )

    viewModel.onAction(UiAction.Navigate(Screen.Settings))
    idle()

    assertTrue(
      "the code must be gone from the controller",
      container.syncController.pairingCode.isNullOrBlank(),
    )
    assertTrue(
      "the code must be gone from the state the screen renders",
      viewModel.state.value.pairingCode.isBlank(),
    )
    assertTrue(
      "an expired countdown must not be left running",
      viewModel.state.value.pairingCodeSecondsLeft == 0L,
    )
  }

  /** The back gesture is the other way off the screen, so it releases the same things. */
  @Test
  fun `backing out of the sync screen stops the server when nothing is paired`() {
    viewModel.onAction(UiAction.Navigate(Screen.Sync))
    awaitCondition { container.syncController.isRunning }

    viewModel.onAction(UiAction.Back)
    idle()

    assertFalse(
      "back must stop the server when no device is paired",
      container.syncController.isRunning,
    )
    assertTrue(
      "back must invalidate the code too",
      container.syncController.pairingCode.isNullOrBlank(),
    )
  }

  /**
   * Re-entering the screen starts a fresh server and issues a fresh code.
   *
   * The stop above is only safe if nothing was cached: a `start()` that early-returned because
   * `isRunning` was stale, or a screen that reused the cancelled code, would leave the sync page
   * permanently dead after one visit.
   */
  @Test
  fun `returning to the sync screen starts a new server and code`() {
    viewModel.onAction(UiAction.Navigate(Screen.Sync))
    awaitCondition { container.syncController.isRunning }
    val firstCode = viewModel.state.value.pairingCode

    viewModel.onAction(UiAction.Navigate(Screen.Home))
    idle()
    assertFalse(container.syncController.isRunning)

    viewModel.onAction(UiAction.Navigate(Screen.Sync))
    awaitCondition { container.syncController.isRunning && viewModel.state.value.pairingCode.isNotBlank() }

    assertTrue("the server must be listening again", container.syncController.isRunning)
    assertTrue(
      "a fresh code must be issued ($firstCode)",
      viewModel.state.value.pairingCode.length == 6,
    )
    assertTrue(
      "the new code must have time on it",
      viewModel.state.value.pairingCodeSecondsLeft > 0,
    )
  }
}
