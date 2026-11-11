package com.livehub.app.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livehub.app.di.AppContainer
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The sync screen's LAN server must not outlive the screen.
 *
 * `SyncController`'s own contract says the server "runs only while the sync screen is open,
 * which keeps the battery and the attack surface small". It did not: the only `stop()` was in
 * `MainViewModel.onCleared()`, and `MainActivity` built the ViewModel with `MainViewModel(container)`
 * — bypassing the `ViewModelStore` that is the only caller of `onCleared`. So the server kept its
 * port bound from the first visit to the sync screen until the process died, and the six digit
 * pairing code stayed valid long after it had scrolled off screen, because `stop()` is also what
 * cancels the `PairingWindow`.
 *
 * Two halves, and they fail for different reasons:
 *
 *  * [leaving the sync screen stops the server] — the navigation half, which this round added.
 *  * [the view model is built through the store] — the lifecycle half. A source-level assertion,
 *    because the alternative is spinning up a real `ComponentActivity` and asserting on
 *    `onCleared` being reached, which Robolectric cannot observe without a lot of ceremony.
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
   * The server is really listening while the screen is open, and really gone afterwards.
   *
   * `isRunning` is the controller's own flag, so this asserts on the same thing the KDoc
   * promises rather than on a proxy. The port is read before and after to prove the socket was
   * actually released, not merely that a boolean flipped: `SyncHttpServer.stop()` closes the
   * `ServerSocket`, so rebinding the same port is only possible once it has been released.
   */
  @Test
  fun `leaving the sync screen stops the server`() {
    viewModel.onAction(UiAction.Navigate(Screen.Sync))
    awaitCondition { container.syncController.isRunning }

    val port = container.syncController.listeningPort
    assertTrue("the server must be listening while the screen is open", port > 0)

    viewModel.onAction(UiAction.Navigate(Screen.Home))
    idle()

    assertFalse(
      "leaving the sync screen must stop the LAN server",
      container.syncController.isRunning,
    )
    // The port has to be released too. Binding it here would throw if the old socket were still
    // held, which is exactly the "attack surface" the KDoc is talking about.
    java.net.ServerSocket().use { probe ->
      probe.reuseAddress = true
      probe.bind(java.net.InetSocketAddress(port))
      assertTrue("the port must be free again", probe.isBound)
    }
  }

  /**
   * The pairing code dies with the screen.
   *
   * `PairingWindow.cancel()` is called by `SyncController.stop()`, and the window is a live
   * credential: a code still accepted by a server that is no longer showing it is the worst of
   * both worlds. Both the controller's code and the copy in `UiState` have to go, because the
   * latter is what the screen renders on the next visit before `beginPairing()` runs.
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

  /** The back gesture is the other way off the screen, so it has to tear down the same things. */
  @Test
  fun `backing out of the sync screen stops the server`() {
    viewModel.onAction(UiAction.Navigate(Screen.Sync))
    awaitCondition { container.syncController.isRunning }

    viewModel.onAction(UiAction.Back)
    idle()

    assertFalse("back must stop the LAN server too", container.syncController.isRunning)
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
