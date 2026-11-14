package com.livehub.app.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livehub.app.di.AppContainer
import com.livehub.core.sync.TrustedPeer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Automatic sync: one silent exchange per foreground entry, and a server that lives as long as the
 * app is on screen.
 *
 * The old contract — "the LAN server runs only while the sync page is open" — made two devices able
 * to meet only when both users were parked on a settings screen. The replacement is three
 * conditions (foreground, ≥1 paired device, the switch on) and this file pins all of them,
 * including the two negative cases that make the positive one mean something: no port before
 * anything is paired, and no second exchange inside the throttle window.
 *
 * The throttle is tested twice on purpose. [shouldAutoSync]'s table covers the arithmetic without
 * depending on a clock, and the end-to-end test covers the part the table cannot: that the
 * timestamp is actually stamped after a run, so **two** foreground entries in a row do not both
 * dial out.
 *
 * A note on what these tests cost: a foreground entry with a paired device really does run an
 * exchange, and an unreachable peer really does cost the locator's 3 second budget. That is the
 * feature working, not a stub — the assertion that follows is about the *outcome* (no snackbar, a
 * log row) rather than about the absence of a call.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AutoSyncTest {

  private lateinit var container: AppContainer
  private lateinit var viewModel: MainViewModel

  @Before
  fun setUp() {
    container = AppContainer(ApplicationProvider.getApplicationContext())
    // DataStore is a process-wide file and the peer list is its own key, so both have to be
    // reset: a device paired by an earlier test would otherwise decide this one's policy.
    container.preferences.update { com.livehub.app.di.UserSettings() }
    container.preferences.saveTrustedPeers(emptyList())
    viewModel = MainViewModel(container)
    idle()
  }

  @After
  fun tearDown() {
    // Leaves no listening socket behind for the next test; the port would fall back to an
    // ephemeral one rather than fail, which makes the *next* test's port the surprising thing.
    if (::viewModel.isInitialized) viewModel.onAppBackground()
    if (::container.isInitialized) {
      container.syncController.stop()
      // The peer list is a DataStore key of its own, so resetting the settings record (which every
      // ViewModel test does in its own `setUp`) does not clear it: a device paired here would hand
      // the next class a non-empty list, and `MainViewModelTest` asserts an empty one. Written
      // synchronously by `saveTrustedPeers`, so this really has landed before the test ends.
      container.preferences.saveTrustedPeers(emptyList())
      // And nothing may still be in the write queue either: a settings write issued by this test
      // must not land in the middle of the next one (the settings record is process-wide too).
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

  /** Pairs one device and mirrors it into the state, through the path the screen uses. */
  private fun pairDevice() {
    container.preferences.saveTrustedPeers(
      listOf(TrustedPeer(2, "小米平板 6", "secret", 1_700_000_000_000)),
    )
    viewModel.onAction(UiAction.RefreshPeers)
    idle()
  }

  // ------------------------------------------------------------------ throttle

  /**
   * The interval, as a literal on purpose.
   *
   * Importing the constant would let the tests keep passing after somebody shortened it to thirty
   * seconds; five minutes is the promise made to the user's battery and to the other device, so it
   * is written down here as well.
   */
  private val fiveMinutes = 5 * 60 * 1000L

  @Test
  fun `the switch turns automatic sync off`() {
    assertFalse(
      "a switched-off feature must not dial out",
      shouldAutoSync(enabled = false, peerCount = 1, nowMillis = 1_000, lastAutoSyncAtMillis = null),
    )
  }

  @Test
  fun `nothing paired means nothing to do`() {
    assertFalse(
      "there is nobody to exchange with",
      shouldAutoSync(enabled = true, peerCount = 0, nowMillis = 1_000, lastAutoSyncAtMillis = null),
    )
  }

  @Test
  fun `a run that just happened is not repeated`() {
    val lastRun = 1_000L
    assertFalse(
      "four minutes and fifty-nine seconds is inside the window",
      shouldAutoSync(
        enabled = true,
        peerCount = 1,
        nowMillis = lastRun + fiveMinutes - 1_000L,
        lastAutoSyncAtMillis = lastRun,
      ),
    )
  }

  @Test
  fun `the first foreground entry of the process syncs`() {
    assertTrue(
      "never synced in this process means the app was just opened",
      shouldAutoSync(enabled = true, peerCount = 1, nowMillis = 1_000, lastAutoSyncAtMillis = null),
    )
  }

  @Test
  fun `the window closes at exactly five minutes`() {
    val lastRun = 1_000L
    assertTrue(
      "five minutes exactly is due",
      shouldAutoSync(
        enabled = true,
        peerCount = 1,
        nowMillis = lastRun + fiveMinutes,
        lastAutoSyncAtMillis = lastRun,
      ),
    )
  }

  // ------------------------------------------------------------ server lifetime

  @Test
  fun `the foreground opens the port and the background closes it`() {
    pairDevice()

    viewModel.onAppForeground()
    idle()

    assertTrue(
      "with a device paired and the switch on, the foreground must open the port",
      container.syncController.isRunning,
    )

    viewModel.onAppBackground()
    idle()

    assertFalse(
      "a socket in the background is battery and attack surface for nobody",
      container.syncController.isRunning,
    )
  }

  @Test
  fun `no port is opened before anything is paired`() {
    viewModel.onAppForeground()
    idle()

    assertFalse(
      "an unpaired installation must expose no port at all",
      container.syncController.isRunning,
    )
  }

  /**
   * The switch is not only a preference: it decides whether a socket is open right now.
   *
   * Turning it off while the user is elsewhere in the app used to leave the server up until the
   * next restart, because the setting was only read at startup.
   */
  @Test
  fun `the switch takes the port down and puts it back`() {
    pairDevice()
    viewModel.onAppForeground()
    idle()
    assertTrue(container.syncController.isRunning)

    viewModel.onAction(UiAction.SetAutoSync(false))
    idle()

    assertFalse(
      "switching it off must close the socket now, not at the next launch",
      container.syncController.isRunning,
    )
    assertFalse("the state the screen renders must follow", viewModel.state.value.autoSyncEnabled)
    assertFalse("and the choice must be persisted", container.preferences.settings.autoSync)

    viewModel.onAction(UiAction.SetAutoSync(true))
    idle()

    assertTrue(
      "switching it back on must reopen the port",
      container.syncController.isRunning,
    )
  }

  /**
   * Off is not "this page stops working".
   *
   * Pairing a new device needs a listening socket *before* any device is paired, so the sync page
   * keeps its own server alive while it is on screen — and lets go of it when the page closes,
   * because by then nothing wants it: the switch is off. The device is paired and the app is in
   * the foreground here so that the *page* is the only thing keeping the socket up.
   */
  @Test
  fun `the sync page still listens with automatic sync switched off`() {
    pairDevice()
    viewModel.onAppForeground()
    idle()
    assertTrue("the policy must have opened the port to begin with", container.syncController.isRunning)

    viewModel.onAction(UiAction.Navigate(Screen.Sync))
    awaitCondition { viewModel.state.value.pairingCode.isNotBlank() }

    viewModel.onAction(UiAction.SetAutoSync(false))
    idle()

    assertTrue(
      "pairing needs a socket, so the page keeps the server up even with the switch off",
      container.syncController.isRunning,
    )

    viewModel.onAction(UiAction.Navigate(Screen.Home))
    idle()

    assertFalse(
      "once the page is gone and the switch is off, nothing keeps the server up",
      container.syncController.isRunning,
    )
  }

  /**
   * Coming back to the app on the sync page has to show a usable code again.
   *
   * Backgrounding stops the server, and `SyncController.stop()` cancels the pairing window with it,
   * so the page cannot simply be left alone: it would render a code that nothing accepts until the
   * user thought to press 刷新匹配码.
   */
  @Test
  fun `returning to the app on the sync page re-issues the code`() {
    viewModel.onAction(UiAction.Navigate(Screen.Sync))
    awaitCondition { viewModel.state.value.pairingCode.isNotBlank() }

    viewModel.onAppBackground()
    idle()
    assertTrue(
      "backgrounding takes the code down with the server",
      viewModel.state.value.pairingCode.isBlank(),
    )

    viewModel.onAppForeground()
    idle()

    assertTrue("the page must be listening again", container.syncController.isRunning)
    assertEquals(
      "a new six digit code must be on screen",
      6,
      viewModel.state.value.pairingCode.length,
    )
    assertTrue(
      "and it must have time on it",
      viewModel.state.value.pairingCodeSecondsLeft > 0,
    )
  }

  // -------------------------------------------------------------- silent exchange

  /**
   * An automatic run reports through the log, not through a snackbar.
   *
   * The user pressed nothing, so a banner appearing because they unlocked their phone would be
   * noise. It still has to be *visible* somewhere, or the exchange would be untraceable: that is
   * what [UiState.syncLog] is for, and this asserts both halves of the trade.
   */
  @Test
  fun `an automatic sync says nothing but is recorded`() {
    pairDevice()

    viewModel.onAppForeground()
    // The exchange hops to `Dispatchers.IO` for its sockets, so `syncing` is the honest signal that
    // it is both started and finished — the assertion is about the settled outcome. The budget is
    // generous because an unreachable peer costs the locator's own three second sweep.
    assertTrue("the automatic exchange must start", viewModel.state.value.syncing)
    awaitCondition(timeoutMillis = 20_000) { !viewModel.state.value.syncing }

    assertNull(
      "an automatic run must not raise a snackbar",
      viewModel.state.value.message,
    )
    assertTrue(
      "the exchange must still be in the log, otherwise it never happened",
      viewModel.state.value.syncLog.isNotEmpty(),
    )
  }

  /**
   * The stamp is what makes the throttle real.
   *
   * `shouldAutoSync` is pure and tested above; this is the half it cannot see — that a run actually
   * records *when* it ran. Without the stamp every foreground entry would dial out, which for a
   * user who switches apps a lot is a socket per switch.
   */
  @Test
  fun `a second foreground entry does not start another exchange`() {
    pairDevice()
    viewModel.onAppForeground()
    awaitCondition(timeoutMillis = 20_000) { !viewModel.state.value.syncing }
    val entriesAfterFirstRun = viewModel.state.value.syncLog.size
    assertTrue("the first entry must have exchanged something", entriesAfterFirstRun > 0)

    // Seconds later, same process: the window is nowhere near closed.
    viewModel.onAppForeground()
    idle()

    assertFalse(
      "a second foreground entry inside the window must not start an exchange",
      viewModel.state.value.syncing,
    )
    assertEquals(
      "and it must not add rows to the log",
      entriesAfterFirstRun,
      viewModel.state.value.syncLog.size,
    )
  }
}
