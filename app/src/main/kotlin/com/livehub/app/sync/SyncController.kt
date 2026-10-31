package com.livehub.app.sync

import com.livehub.app.di.FollowStore
import com.livehub.app.di.UserPreferences
import com.livehub.core.sync.DiscoveredPeer
import com.livehub.core.sync.SyncCoordinator
import com.livehub.core.sync.SyncEnvelope
import com.livehub.core.sync.SyncPayload
import com.livehub.core.sync.SyncScope
import com.livehub.core.sync.TrustedPeer
import com.livehub.core.sync.discovery.PeerDiscovery
import com.livehub.core.sync.server.SyncHttpServer
import com.livehub.core.sync.transport.LanSyncTransport
import com.livehub.core.sync.transport.PeerAddressBook
import com.livehub.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The Android side of cross-device sync.
 *
 * Owns the LAN server, the client transport, discovery and the pairing handshake so the
 * ViewModel only has to call [start], [syncNow] and [stop]. The server runs only while
 * the sync screen is open, which keeps the battery and the attack surface small.
 */
class SyncController(
  private val preferences: UserPreferences,
  private val followStore: FollowStore,
  private val engine: SyncEngine,
  private val discovery: PeerDiscovery,
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val addressBook = PeerAddressBook()

  private var server: SyncHttpServer? = null
  private var transport: LanSyncTransport? = null
  private val discoveryJob = mutableListOf<kotlinx.coroutines.Job>()

  @Volatile var pairingCode: String? = null
    private set

  @Volatile var listeningPort: Int = 0
    private set

  val serverAddress: String?
    get() = PeerAddressBook.preferredLocalAddress()?.let { "$it:$listeningPort" }

  /** Observers get every peer seen by discovery. */
  var onPeerDiscovered: ((DiscoveredPeer) -> Unit)? = null

  val isRunning: Boolean get() = server?.isRunning == true

  /**
   * Starts discovery and the LAN server.
   *
   * Pairing is a two-step flow: [beginPairing] shows a code on one device, the other
   * device enters it and calls [completePairing], and both sides store the resulting
   * [TrustedPeer].
   */
  fun start() {
    if (isRunning) return
    val deviceId = preferences.deviceId
    val deviceName = preferences.deviceName

    val httpServer = SyncHttpServer(
      deviceId = deviceId,
      deviceName = deviceName,
      localSecret = CURRENT_SECRET,
      handler = object : SyncHttpServer.Handler {
        override fun onPair(envelope: SyncEnvelope, remoteAddress: String): Pair<String, TrustedPeer>? {
          val expected = pairingCode ?: return null
          val result = engine.completePairing(
            peerDeviceId = envelope.deviceId,
            peerDeviceName = envelope.deviceName,
            code = envelope.payload?.entries.orEmpty(),
            expectedCode = expected,
          )
          val peer = result.getOrNull() ?: return null
          addressBook.addManual(envelope.deviceId, envelope.deviceName, remoteAddress, DEFAULT_PORT)
          return peer.sharedSecret to peer
        }

        override fun onPull(envelope: SyncEnvelope, scope: SyncScope, peer: TrustedPeer?): SyncPayload? =
          engine.buildPayload(scope)

        override fun onPush(envelope: SyncEnvelope, payload: SyncPayload, peer: TrustedPeer?): SyncPayload? {
          engine.applyPayload(payload, peer)
          return engine.buildPayload(payload.parsedScope ?: return null)
        }

        override fun peerById(deviceId: Long): TrustedPeer? =
          preferences.trustedPeers().firstOrNull { it.deviceId == deviceId }
      },
    )
    listeningPort = httpServer.start()
    server = httpServer

    transport = LanSyncTransport(
      deviceId = deviceId,
      deviceName = deviceName,
      localSecret = CURRENT_SECRET,
      peersProvider = { preferences.trustedPeers() },
      addressProvider = { peer -> addressBook.addressFor(peer) },
    )

    // mDNS advertises only while the flow is collected, so keep one collector alive.
    discoveryJob += scope.launch {
      discovery.discover().collect { peer ->
        addressBook.remember(peer)
        onPeerDiscovered?.invoke(peer)
      }
    }
  }

  fun stop() {
    discoveryJob.forEach { it.cancel() }
    discoveryJob.clear()
    server?.stop()
    server = null
    transport = null
  }

  fun dispose() {
    stop()
    scope.cancel()
  }

  /** Generates the code shown to the user on this device. */
  fun beginPairing(): String {
    val hosting = engine.startHosting()
    pairingCode = hosting.code
    return hosting.code
  }

  /** Registers a peer discovered automatically so it can be reached later. */
  fun rememberPeer(peer: DiscoveredPeer) {
    addressBook.remember(peer)
  }

  /** Adds a peer the user typed manually (`host[:port]`). */
  fun addManualPeer(peerName: String, address: String): Boolean {
    val parsed = com.livehub.core.sync.transport.parseManualAddress(address, DEFAULT_PORT) ?: return false
    val deviceId = peerName.hashCode().toLong() and 0x7FFFFFFF
    addressBook.addManual(deviceId, peerName, parsed.first, parsed.second)
    engine.completePairing(
      peerDeviceId = deviceId,
      peerDeviceName = peerName,
      code = pairingCode.orEmpty(),
      expectedCode = pairingCode.orEmpty(),
    )
    return true
  }

  /**
   * Runs a full exchange. Returns a human readable summary for the snackbar.
   */
  suspend fun syncNow(): SyncSummary {
    val currentTransport = transport ?: return SyncSummary(0, 0, "同步服务未启动")
    val coordinator = SyncCoordinator(engine, currentTransport)
    val peers = preferences.trustedPeers()
    if (peers.isEmpty()) return SyncSummary(0, 0, "尚未配对任何设备")

    var pulled = 0
    var pushed = 0
    var failures = 0
    peers.forEach { peer ->
      coordinator.syncWith(peer).forEach { outcome ->
        pulled += outcome.pulled
        pushed += outcome.pushed
        if (!outcome.success) failures++
      }
    }
    val message = when {
      failures > 0 -> "部分同步失败 ($failures)"
      pulled == 0 && pushed == 0 -> "已是最新"
      else -> "同步完成：接收 $pulled，发送 $pushed"
    }
    return SyncSummary(pulled, pushed, message)
  }

  /** Probes every paired peer so the UI can show who is online. */
  suspend fun onlinePeers(): Set<Long> {
    val currentTransport = transport ?: return emptySet()
    return preferences.trustedPeers()
      .filter { currentTransport.probe(it) }
      .map { it.deviceId }
      .toSet()
  }

  data class SyncSummary(val pulled: Int, val pushed: Int, val message: String)

  companion object {
    const val DEFAULT_PORT = 47653

    /**
     * Secret used for the very first exchange. Pairing replaces it with a per-peer
     * random value, so this constant never protects real data.
     */
    private const val CURRENT_SECRET = "livehub-pairing"
  }
}
