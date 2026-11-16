package com.livehub.core.sync.discovery

import com.livehub.core.sync.DiscoveredPeer
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNotSame
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

  /**
   * A hanging address cannot hang the sync button.
   *
   * `overallBudgetMillis` exists for networks where a connect neither succeeds nor fails — a
   * filtering router, a half-open NAT entry. A blocking probe cannot be cancelled, so the budget is
   * a promise about `locate`, not about the probe: the sweep returns null and the abandoned socket
   * dies on its own timeout. Worth pinning because "the budget returned" and "the probes finished"
   * are otherwise the same observation; the budget here is 300ms against a probe that sleeps 10s.
   */
  @Test
  fun `a probe that never answers is abandoned at the budget`() = runBlocking {
    val started = System.currentTimeMillis()

    val found = PeerLocator(overallBudgetMillis = 300).locate(
      deviceId = 7L,
      candidates = listOf(candidate("192.168.1.2"), candidate("192.168.1.3")),
    ) {
      Thread.sleep(10_000)
      null
    }

    val elapsed = System.currentTimeMillis() - started
    assertNull(found)
    assertTrue(elapsed < 3_000, "the sweep waited ${elapsed}ms past a 300ms budget")
  }

  /**
   * The probe is a *blocking* lambda on purpose, and this is the property that makes that safe.
   *
   * `PeerLocator`'s own documentation says "[probe] is blocking and is called on [dispatcher]", and
   * `SyncController.syncNow` depends on it: the lambda it passes opens a raw socket with `connect`
   * and `soTimeout`, which on the main thread would throw `NetworkOnMainThreadException` rather
   * than merely jank. That is exactly why `syncNow` wraps itself in `withContext(Dispatchers.IO)`,
   * and why `SyncOffMainThreadTest` calls it from the UI thread to prove the crossing happens.
   *
   * Nothing pinned *this* half, though: changing the default dispatcher, or refactoring `locate` to
   * call the probe inline, compiles and passes every other test in this file while breaking the app
   * only on a device that actually has a peer to find. Asserted on thread *identity* rather than a
   * thread name, because a name is a convention and identity is the thing that matters.
   */
  @Test
  fun `the probe never runs on the calling thread`() = runBlocking {
    val caller = Thread.currentThread()
    var probedOn: Thread? = null

    val found = PeerLocator().locate(7L, listOf(candidate("192.168.1.2"))) { _ ->
      probedOn = Thread.currentThread()
      7L
    }

    assertEquals("192.168.1.2", found?.host)
    assertNotNull(probedOn, "the probe has to be called at least once")
    assertNotSame(
      caller,
      probedOn,
      "the probe ran on the caller's thread, which is where a blocking connect would throw",
    )
  }
}
