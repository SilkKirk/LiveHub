package com.livehub.core.sync.transport

import com.livehub.core.sync.DiscoveredPeer
import com.livehub.core.sync.TrustedPeer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The table that remembers where a paired device lives.
 *
 * `TrustedPeer` carries the secret but no address, so this is the only thing that knows how to
 * reach the other device. Keeping it in memory alone is why a restarted app could only find a
 * peer through mDNS — and a router that blocks multicast left sync reporting "不在线" forever.
 */
class PeerAddressBookTest {

  private fun address(deviceId: Long = 7, host: String = "192.168.1.9", port: Int = 47653) =
    DiscoveredPeer(deviceId = deviceId, deviceName = "tablet", host = host, port = port)

  private fun trusted(deviceId: Long = 7) =
    TrustedPeer(deviceId = deviceId, deviceName = "tablet", sharedSecret = "s", pairedAt = 0)

  @Test
  fun `an address read back from disk is used without discovery`() {
    val book = PeerAddressBook(initial = listOf(address()))

    assertEquals("192.168.1.9", book.addressFor(trusted())?.host)
    assertEquals(47653, book.addressFor(trusted())?.port)
  }

  @Test
  fun `what pairing learned is handed over for persistence`() {
    val written = mutableListOf<List<DiscoveredPeer>>()
    val book = PeerAddressBook(onChange = { written += it })

    book.addManual(deviceId = 7, name = "tablet", host = "10.0.0.4", port = 5000)

    assertEquals(1, written.size)
    // A cold start is exactly this: a new book seeded with whatever was written.
    val restarted = PeerAddressBook(initial = written.single())
    assertEquals("10.0.0.4", restarted.addressFor(trusted())?.host)
    assertEquals(5000, restarted.addressFor(trusted())?.port)
  }

  @Test
  fun `re-seeing the same address does not rewrite it`() {
    val written = mutableListOf<List<DiscoveredPeer>>()
    val book = PeerAddressBook(onChange = { written += it })

    // mDNS re-announces the same peer every few seconds while the screen is open.
    book.remember(address())
    book.remember(address())

    assertEquals(1, written.size)
  }

  @Test
  fun `forgetting a peer clears its address from the stored table`() {
    val written = mutableListOf<List<DiscoveredPeer>>()
    val book = PeerAddressBook(initial = listOf(address()), onChange = { written += it })

    book.forget(7)

    assertNull(book.addressFor(trusted()))
    assertTrue(written.single().isEmpty())
  }

  @Test
  fun `a peer that was never seen has no address`() {
    val book = PeerAddressBook()

    assertNull(book.addressFor(trusted(deviceId = 99)))
  }
}
