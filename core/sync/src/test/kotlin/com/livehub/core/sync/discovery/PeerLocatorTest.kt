package com.livehub.core.sync.discovery

import com.livehub.core.sync.DiscoveredPeer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Finding a paired device again after its address moved.
 *
 * Pairing could always sweep the subnet; sync could only use the one address mDNS had
 * advertised. These tests pin the sweep half: identify the peer by the device id its server
 * answers with, and never confuse it with another LiveHub installation on the same network.
 */
class PeerLocatorTest {

  private fun candidate(host: String, port: Int = 47653) =
    DiscoveredPeer(deviceId = 0, deviceName = "", host = host, port = port)

  @Test
  fun `the address that answers with the wanted device id wins`() = runBlocking {
    val found = PeerLocator().locate(
      deviceId = 7L,
      candidates = listOf(candidate("192.168.1.2"), candidate("192.168.1.9")),
    ) { peer -> if (peer.host == "192.168.1.9") 7L else null }

    assertEquals("192.168.1.9", found?.host)
  }

  @Test
  fun `another livehub device is not mistaken for the peer`() = runBlocking {
    val found = PeerLocator().locate(7L, listOf(candidate("192.168.1.4"))) { 8L }

    assertNull(found, "a different device id must not be returned as the pair")
  }

  @Test
  fun `a probe that throws does not abort the sweep`() = runBlocking {
    val found = PeerLocator().locate(
      deviceId = 7L,
      candidates = listOf(candidate("192.168.1.4"), candidate("192.168.1.5")),
    ) { peer ->
      if (peer.host == "192.168.1.4") error("connection reset") else 7L
    }

    assertEquals("192.168.1.5", found?.host)
  }

  /**
   * Nothing answering must return, not hang.
   *
   * The deadlines are what keep the sync button responsive: a sweep of dead addresses finishes
   * as soon as every attempt has failed, instead of sitting out the whole budget.
   */
  @Test
  fun `nothing answering returns null without waiting out the budget`() = runBlocking {
    val started = System.currentTimeMillis()

    val found = PeerLocator().locate(7L, (1..8).map { candidate("192.168.1.$it") }) { null }

    val elapsed = System.currentTimeMillis() - started
    assertNull(found)
    assertTrue(elapsed < 2_000, "the sweep waited ${elapsed}ms for eight empty answers")
  }
}
