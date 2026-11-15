package com.livehub.app.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.livehub.core.sync.DiscoveredPeer
import com.livehub.core.sync.TrustedPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The peer list and the address book are read from disk once, not once per caller.
 *
 * Both used to be a bare `runBlocking { dataStore.data.first() }` in the getter, and both are on hot
 * paths: `SyncEngine` asks for the peer list several times per exchange, `SyncHttpServer.peerById`
 * asks on **every** authenticated request, `PeerAddressBook` re-reads the address book through its
 * `onChange` callback, and `MainViewModel.refreshPeers` asks from the **main** thread on every visit
 * to the sync page and after every pair and unpair. The main-thread one is the only user-visible
 * cost, and it is the same defect round 60 fixed for `deviceId`.
 *
 * **The tradeoff is pinned deliberately**: a second instance over the same file does not see a write
 * made by the first one after that first one has already read. Only tests build two instances, and
 * `PeerAddressPersistenceTest` covers the case that matters ("a restarted app still sees its data")
 * by reading through a fresh instance whose cache is cold. Documenting the narrowing here keeps the
 * next reader from being surprised by it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PeerListCacheTest {

  private val context get() = ApplicationProvider.getApplicationContext<Context>()

  private lateinit var preferences: UserPreferences

  private val tablet = TrustedPeer(deviceId = 2, deviceName = "平板", sharedSecret = "s", pairedAt = 1_000)
  private val address = DiscoveredPeer(deviceId = 2, deviceName = "平板", host = "192.168.1.9", port = 47653)

  @Before
  fun setUp() {
    preferences = UserPreferences(context)
    preferences.saveTrustedPeers(emptyList())
    preferences.savePeerAddresses(emptyList())
  }

  @Test
  fun `saving the peer list republishes the cache`() {
    // Warm the cache with the empty list written in setUp.
    assertTrue(preferences.trustedPeers().isEmpty())

    preferences.saveTrustedPeers(listOf(tablet))

    assertEquals(
      "a write must be visible on the next read without going back to disk",
      listOf(tablet),
      preferences.trustedPeers(),
    )
  }

  /** The regression: the second read must not go to disk. */
  @Test
  fun `the peer list is read once and then cached`() {
    preferences.saveTrustedPeers(listOf(tablet))
    assertEquals(listOf(tablet), preferences.trustedPeers())

    // Change the file behind this instance's back. A getter that re-read would return the new
    // value; a cached one keeps returning what it already resolved.
    UserPreferences(context).saveTrustedPeers(emptyList())

    assertEquals(
      "a second read must come from the cache, not another DataStore round trip",
      listOf(tablet),
      preferences.trustedPeers(),
    )
  }

  @Test
  fun `saving the address book republishes its cache`() {
    assertTrue(preferences.peerAddresses().isEmpty())

    preferences.savePeerAddresses(listOf(address))

    assertEquals(listOf(address), preferences.peerAddresses())
  }

  @Test
  fun `the address book is read once and then cached`() {
    preferences.savePeerAddresses(listOf(address))
    assertEquals(listOf(address), preferences.peerAddresses())

    UserPreferences(context).savePeerAddresses(emptyList())

    assertEquals(
      "a second read must come from the cache",
      listOf(address),
      preferences.peerAddresses(),
    )
  }

  /**
   * A caller that mutates the list it passed in cannot reach back into the cache.
   *
   * `saveTrustedPeers` stores a copy; without one, `SyncEngine`'s habit of building
   * `existing + peer` into a mutable list and then editing it would silently rewrite what every
   * reader sees, before the write that was supposed to define it.
   */
  @Test
  fun `the cached list cannot be mutated by the caller`() {
    val mutable = mutableListOf(tablet)
    preferences.saveTrustedPeers(mutable)

    mutable.clear()

    assertEquals("the cache must hold a copy", listOf(tablet), preferences.trustedPeers())
  }
}
