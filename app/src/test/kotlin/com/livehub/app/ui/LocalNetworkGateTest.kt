package com.livehub.app.ui

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livehub.app.di.AppContainer
import com.livehub.app.di.UserSettings
import com.livehub.app.sync.LocalNetworkAccess
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The local-network permission gate: Android 17 refuses LAN traffic without it, and it refuses by
 * *timing out*, so nothing in the sync surface can be allowed to start and hope.
 *
 * From API 37 the platform enforces local network protection for apps targeting 37 or higher. Every
 * socket call this feature makes — the `ServerSocket` bind, the transport's raw sockets, the subnet
 * sweep, `NsdManager` — is behind the grant. Without it they do not fail with a permission error;
 * they hang until the socket timeout, which the app would have reported as "对方拒绝连接" or
 * "不在线" on a network that is working perfectly.
 *
 * The two decisions are kept apart on purpose:
 *
 *  * [LocalNetworkAccess] is a pure rule and is tested without any Android at all;
 *  * `MainViewModel` takes the API level and the grant as lambdas, so this test can drive the
 *    API 37 branch while Robolectric itself runs on 34 (where the permission does not exist).
 */
class LocalNetworkAccessTest {

  @Test
  fun `nothing is enforced below API 37`() {
    assertFalse(LocalNetworkAccess.enforced(26))
    assertFalse(LocalNetworkAccess.enforced(36))
    assertFalse(
      "below 37 the permission does not exist, so a missing grant must not block anything",
      LocalNetworkAccess.blocked(sdk = 36, granted = false),
    )
  }

  @Test
  fun `API 37 enforces it, grant or no grant`() {
    assertTrue(LocalNetworkAccess.enforced(37))
    assertTrue(LocalNetworkAccess.enforced(38))
    assertFalse(
      "with the grant the LAN surface may run",
      LocalNetworkAccess.blocked(sdk = 37, granted = true),
    )
    assertTrue(
      "without it, nothing may touch the LAN",
      LocalNetworkAccess.blocked(sdk = 37, granted = false),
    )
  }

  @Test
  fun `the permission name is the platform's, not a guess`() {
    // A literal rather than `Manifest.permission.…` because that constant only exists in the API 37
    // stubs; the string is what the platform actually checks.
    assertEquals(
      "android.permission.ACCESS_LOCAL_NETWORK",
      LocalNetworkAccess.PERMISSION,
    )
  }
}

/** The same rule, seen from the ViewModel: does the sync surface come up, and what does the page say. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LocalNetworkGateTest {

  private lateinit var container: AppContainer

  @Before
  fun setUp() {
    container = AppContainer(ApplicationProvider.getApplicationContext())
    container.preferences.update { UserSettings() }
    container.preferences.saveTrustedPeers(emptyList())
  }

  @After
  fun tearDown() {
    container.syncController.stop()
    container.preferences.awaitWrites()
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

  /** The regression: on API 37 a sync page without the grant used to bind a socket it could not use. */
  @Test
  fun `on API 37 without the permission the sync surface stays down`() {
    val viewModel = MainViewModel(container, localNetworkSdk = { 37 }, localNetworkGranted = { false })

    viewModel.onAction(UiAction.Navigate(Screen.Sync))
    idle()

    assertTrue("the page has to know why sync cannot run", viewModel.state.value.localNetworkBlocked)
    assertFalse("no socket may be bound without the grant", container.syncController.isRunning)
    assertFalse("mDNS must not be started either", viewModel.state.value.discovering)
  }

  /** Granting it while the page is open brings the server up without another visit. */
  @Test
  fun `granting starts the server that was waiting`() {
    var granted = false
    val viewModel = MainViewModel(container, localNetworkSdk = { 37 }, localNetworkGranted = { granted })
    viewModel.onAction(UiAction.Navigate(Screen.Sync))
    idle()
    assertFalse(container.syncController.isRunning)

    granted = true
    viewModel.onLocalNetworkPermissionResult(true)

    awaitCondition { container.syncController.isRunning }
    assertFalse(viewModel.state.value.localNetworkBlocked)
  }

  /** Below API 37 the permission does not exist, so a "denied" answer must not gate anything. */
  @Test
  fun `below API 37 nothing is gated`() {
    val viewModel = MainViewModel(container, localNetworkSdk = { 34 }, localNetworkGranted = { false })

    viewModel.onAction(UiAction.Navigate(Screen.Sync))
    awaitCondition { container.syncController.isRunning }

    assertFalse(viewModel.state.value.localNetworkBlocked)
  }

  /** A denial is explained in the app, not left as a page that silently does nothing. */
  @Test
  fun `a denial is explained`() {
    val viewModel = MainViewModel(container, localNetworkSdk = { 37 }, localNetworkGranted = { false })

    viewModel.onLocalNetworkPermissionResult(false)
    idle()

    val message = viewModel.state.value.message.orEmpty()
    assertTrue("the refusal has to be named: $message", message.contains("本地网络权限"))
  }
}
