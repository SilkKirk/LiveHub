package com.livehub.core.sync

import com.livehub.core.sync.server.PairingOutcome
import com.livehub.core.sync.server.SyncHttpServer
import com.livehub.core.sync.transport.LanSyncHttpTransport
import com.livehub.core.sync.transport.LanSyncTransport
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.ServerSocket

/**
 * Pairing with nothing but the six digit code.
 *
 * The point of these tests is the *absence* of an address: the client is never told where
 * the host is beyond "somewhere on this network", exactly like WeChat's input method. A
 * real [SyncHttpServer] runs on loopback, so the handshake, the secret exchange and the
 * distinct failure messages are all exercised for real.
 */
class CodePairingTest {

  private val hosts = mutableListOf<SyncHttpServer>()
  private val hostPeers = mutableListOf<TrustedPeer>()
  private var hostPort = 0
  private var hostCode = ""
  private var hostWindow: PairingWindow? = null

  /** Controllable clock for the host's [PairingWindow], so expiry needs no sleep. */
  private var hostClock = 1_000_000L

  private val hostDeviceId = 7L
  private val hostSecret = "issued-by-host"

  @AfterEach
  fun tearDown() {
    hosts.forEach { it.stop() }
    hosts.clear()
    hostPeers.clear()
    hostWindow = null
  }

  /** Starts a device showing a real code from its own [PairingWindow]. */
  private fun startHost(): Int {
    // The clock is a variable so expiry can be driven without sleeping.
    val pairingWindow = PairingWindow(clock = { hostClock })
    hostWindow = pairingWindow
    hostCode = pairingWindow.issue().code
    val server = SyncHttpServer(
      deviceId = hostDeviceId,
      deviceName = "小米平板 6",
      localSecret = hostSecret,
      handler = object : SyncHttpServer.Handler {
        override fun onPair(envelope: SyncEnvelope, remoteAddress: String): PairingOutcome {
          // Validated through the same window the UI drives, so expiry and the per-source
          // attempt budget are the real implementation rather than a stub.
          val verdict = pairingWindow.verify(envelope.payload?.entries.orEmpty(), remoteAddress)
          verdict.reason?.let { return PairingOutcome.Rejected(it) }
          val peer = TrustedPeer(
            deviceId = envelope.deviceId,
            deviceName = envelope.deviceName,
            sharedSecret = envelope.secret,
            pairedAt = 0,
          )
          hostPeers += peer
          return PairingOutcome.Accepted(issuedSecret = hostSecret, peer = peer)
        }

        override fun onPull(
          envelope: SyncEnvelope,
          scope: SyncScope,
          peer: TrustedPeer?,
          chunkIndex: Int,
        ): SyncPayload? = null

        override fun onPush(envelope: SyncEnvelope, payload: SyncPayload, peer: TrustedPeer?): SyncPayload? = null

        override fun peerById(deviceId: Long): TrustedPeer? = hostPeers.firstOrNull { it.deviceId == deviceId }
      },
    )
    hosts += server
    hostPort = server.start(0)
    return hostPort
  }

  private fun clientTransport(): LanSyncHttpTransport {
    val lan = LanSyncTransport(
      deviceId = 1,
      deviceName = "REDMI K80 Pro",
      localSecret = "client-secret",
      peersProvider = { emptyList() },
      addressProvider = { null },
      timeoutMillis = 1_500,
      localPort = 0,
    )
    return LanSyncHttpTransport(lan, addressProvider = { null })
  }

  private fun pairing() = CodePairing(
    deviceId = 1,
    deviceName = "REDMI K80 Pro",
    localSecret = "client-secret",
    overallBudgetMillis = 6_000,
  )

  /** A locally closed port, so an unsuccessful attempt fails fast instead of waiting. */
  private fun closedPort(): Int = ServerSocket(0).use { it.localPort }

  private fun peerAt(port: Int) = DiscoveredPeer(0, "", "127.0.0.1", port)

  @Test
  fun `the code alone finds and pairs with the host`() = runBlocking {
    val port = startHost()

    val result = pairing().pair(hostCode, listOf(peerAt(port)), clientTransport())

    val paired = result as? CodePairingResult.Paired
    assertNotNull(paired, "expected a pairing, got $result")
    paired!!
    assertEquals(hostDeviceId, paired.peer.deviceId)
    assertEquals("小米平板 6", paired.peer.deviceName)
    // The secret the host issued is what this device must present from now on.
    assertEquals(hostSecret, paired.peer.sharedSecret)
    // And the host stored ours, which is what makes the next request authenticate.
    assertEquals("client-secret", hostPeers.single().sharedSecret)
  }

  @Test
  fun `the paired address is the port the host actually bound`() = runBlocking {
    val port = startHost()

    val result = pairing().pair(hostCode, listOf(peerAt(port)), clientTransport())

    val paired = result as? CodePairingResult.Paired
    assertNotNull(paired)
    assertEquals(port, paired!!.address.port)
    assertEquals("127.0.0.1", paired.address.host)
  }

  @Test
  fun `a wrong code reports the code rather than a connection problem`() = runBlocking {
    val port = startHost()
    val wrong = if (hostCode == "000000") "000001" else "000000"

    val result = pairing().pair(wrong, listOf(peerAt(port)), clientTransport())

    val rejected = result as? CodePairingResult.Rejected
    assertNotNull(rejected, "expected a rejection, got $result")
    // The words matter: "配对码不正确" tells the user to recheck the digits, "连接失败"
    // sends them to debug their Wi-Fi.
    assertTrue(rejected!!.reason.contains("配对码"), rejected.reason)
    assertTrue(hostPeers.isEmpty(), "nothing must be stored on a wrong code")
  }

  /**
   * A six digit code that no device will accept.
   *
   * `hostCode` is only populated by [startHost], so the tests that never start a host have
   * to supply their own — passing the empty default made every one of them come back
   * `Malformed` and assert the wrong thing.
   */
  private val anyCode = "482913"

  @Test
  fun `no device answering is reported as unreachable, not as a wrong code`() = runBlocking {
    val result = pairing().pair(anyCode, listOf(peerAt(closedPort())), clientTransport())

    assertTrue(result is CodePairingResult.Unreachable, "got $result")
    assertTrue((result as CodePairingResult.Unreachable).tried >= 1)
    assertTrue(result.message!!.contains("没有找到"))
  }

  @Test
  fun `a malformed code never opens a socket`() = runBlocking {
    val result = pairing().pair("12", listOf(peerAt(1)), clientTransport())

    assertEquals(CodePairingResult.Malformed, result)
  }

  @Test
  fun `no candidates explains what to check`() = runBlocking {
    val result = pairing().pair(anyCode, emptyList(), clientTransport())

    assertEquals(CodePairingResult.NoCandidate, result)
    assertTrue(result.message!!.contains("Wi-Fi"))
  }

  /**
   * The sweep has to find the host among addresses that are not listening.
   *
   * This is the case discovery cannot cover (multicast blocked), so it is the one that
   * decides whether "just type the code" works on a locked-down router.
   */
  @Test
  fun `the host is found among dead addresses`() = runBlocking {
    val port = startHost()
    val dead = (1..3).map { peerAt(closedPort()) }

    val result = pairing().pair(hostCode, dead + peerAt(port), clientTransport())

    assertTrue(result is CodePairingResult.Paired, "got $result")
  }

  /** The same host listed twice must not produce two peers or two handshakes. */
  @Test
  fun `duplicate candidates are collapsed`() = runBlocking {
    val port = startHost()
    val candidate = peerAt(port)

    val result = pairing().pair(hostCode, listOf(candidate, candidate, candidate), clientTransport())

    assertTrue(result is CodePairingResult.Paired, "got $result")
    assertEquals(1, hostPeers.size)
  }

  @Test
  fun `repeating the flow yields the same peer identity, never a second one`() = runBlocking {
    val port = startHost()
    // The host's code is single-use, so a second attempt gets its own fresh code; the
    // device identity it reports must not change.
    val first = pairing().pair(hostCode, listOf(peerAt(port)), clientTransport()) as CodePairingResult.Paired
    val secondCode = hostWindow!!.issue().code
    val second = pairing().pair(secondCode, listOf(peerAt(port)), clientTransport()) as CodePairingResult.Paired

    assertEquals(first.peer.deviceId, second.peer.deviceId)
  }

  @Test
  fun `a used code is refused by the host`() = runBlocking {
    val port = startHost()
    val first = pairing().pair(hostCode, listOf(peerAt(port)), clientTransport())
    assertTrue(first is CodePairingResult.Paired, "first attempt should pair, got $first")

    // Same digits again: the host closed that window on success.
    val second = pairing().pair(hostCode, listOf(peerAt(port)), clientTransport())

    assertTrue(second is CodePairingResult.Rejected, "replay should be refused, got $second")
  }

  @Test
  fun `an expired code is refused with an expiry message`() = runBlocking {
    val port = startHost()
    // Walk the host's clock past the five minute window without sleeping.
    hostClock += PairingWindow.DEFAULT_TTL_MILLIS + 1

    val result = pairing().pair(hostCode, listOf(peerAt(port)), clientTransport())

    val rejected = result as? CodePairingResult.Rejected
    assertNotNull(rejected, "got $result")
    // Jupiter argument order: the reason is last.
    assertTrue(
      rejected!!.reason.contains("过期"),
      "an expired code must say so, not blame the network: ${rejected.reason}",
    )
    assertTrue(hostPeers.isEmpty())
  }

  @Test
  fun `the rejection message wins over a silently dead address`() = runBlocking {
    val port = startHost()
    val wrong = if (hostCode == "000000") "000001" else "000000"

    val result = pairing().pair(wrong, listOf(peerAt(closedPort()), peerAt(port)), clientTransport())

    assertTrue(result is CodePairingResult.Rejected, "got $result")
  }

  @Test
  fun `a paired peer reports no error message`() = runBlocking {
    val port = startHost()

    val result = pairing().pair(hostCode, listOf(peerAt(port)), clientTransport())

    assertNull(result.message)
  }
}
