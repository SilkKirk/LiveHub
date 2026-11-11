package com.livehub.app.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.livehub.core.sync.DiscoveredPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * A paired address has to outlive the process.
 *
 * `TrustedPeer` stores the secret but never the address, so this table is the only thing that
 * knows where the other device is. It used to live in memory alone: after a restart the app
 * could only re-learn an address through mDNS, and on a network that blocks multicast every
 * sync reported "不在线" while the peer was perfectly reachable over TCP.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeerAddressPersistenceTest {

  private val context get() = ApplicationProvider.getApplicationContext<Context>()

  private lateinit var preferences: UserPreferences

  @Before
  fun setUp() {
    preferences = UserPreferences(context)
    // DataStore is a process-wide file, so a previous test's table would leak in.
    preferences.savePeerAddresses(emptyList())
  }

  @Test
  fun `an address learned while pairing is still there on the next launch`() {
    val address = DiscoveredPeer(
      deviceId = 7,
      deviceName = "小米平板 6",
      host = "192.168.1.9",
      port = 47653,
      discovered = false,
    )
    preferences.savePeerAddresses(listOf(address))

    // `savePeerAddresses` still writes on the calling thread, so the file already holds it; this
    // is the seam that would need `awaitWrites()` if that ever moved to the write worker.
    preferences.awaitWrites()

    // A cold start shares nothing with the old process except the file on disk.
    val restarted = UserPreferences(context)

    assertEquals(listOf(address), restarted.peerAddresses())
  }

  @Test
  fun `an empty table reads back as no addresses`() {
    preferences.savePeerAddresses(emptyList())

    assertTrue(preferences.peerAddresses().isEmpty())
  }
}
