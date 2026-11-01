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

  /**
   * Peers currently visible on the LAN, newest first.
   *
   * mDNS keeps re-announcing the same device, so the map is keyed by device id and
   * only the last sighting is kept; the UI shows this list as "可配对设备".
   */
  private val discovered = java.util.concurrent.ConcurrentHashMap<Long, DiscoveredPeer>()

  fun discoveredPeers(): List<DiscoveredPeer> = discovered.values.sortedBy { it.deviceName }

  /** Removes a peer that stopped advertising, so the list does not grow stale. */
  fun forgetPeer(deviceId: Long) {
    discovered.remove(deviceId)
    addressBook.forget(deviceId)
  }

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
          // Record where the *peer* is listening. `remoteAddress` is their IP but the
          // port must be the one their server advertised, not our own default: the
          // handshake is the only moment we learn it.
          val remotePort = envelope.preferredPort.takeIf { it in 1..65535 } ?: DEFAULT_PORT
          addressBook.addManual(envelope.deviceId, envelope.deviceName, remoteAddress, remotePort)
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
        discovered[peer.deviceId] = peer
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
    discovered.clear()
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

  /**
   * Pairs with a peer that is showing [code].
   *
   * Performs the real handshake rather than only recording the attempt locally:
   * a `pair` request is sent to the peer with the code as the payload, the peer
   * validates it against the code it is currently hosting and answers with the
   * per-peer shared secret that both sides then use for every sync request.
   *
   * @return the paired device name on success, or a failure describing why.
   */
  fun pair(peerName: String, address: String, code: String): Result<String> {
    val parsed = com.livehub.core.sync.transport.parseManualAddress(address, DEFAULT_PORT)
      ?: return Result.failure(IllegalArgumentException("地址格式应为 192.168.1.10 或 192.168.1.10:$DEFAULT_PORT"))

    val currentTransport = transport
      ?: return Result.failure(IllegalStateException("同步服务未启动，请重新打开同步页"))

    val envelope = SyncEnvelope(
      deviceId = preferences.deviceId,
      deviceName = preferences.deviceName,
      secret = "",
      preferredPort = listeningPort,
      payload = SyncPayload(
        scope = "pair",
        sourceDeviceId = preferences.deviceId,
        sequence = System.currentTimeMillis(),
        createdAt = System.currentTimeMillis(),
        entries = code,
      ),
    )

    val response = runCatching {
      currentTransport.call(
        peer = TrustedPeer(
          deviceId = 0,
          deviceName = peerName,
          sharedSecret = "",
          pairedAt = 0,
        ),
        host = parsed.first,
        port = parsed.second,
        path = SyncEnvelope.PATH_PAIR,
        envelope = envelope,
      )
    }.getOrElse { error ->
      return Result.failure(IllegalStateException(error.message ?: "无法连接到该设备"))
    }

    response.error?.let { return Result.failure(IllegalStateException(it)) }
    if (response.secret.isBlank()) {
      return Result.failure(IllegalStateException("对方未返回配对密钥"))
    }

    val peerId = if (response.serverDeviceId != 0L) response.serverDeviceId else response.deviceId
    val peer = TrustedPeer(
      deviceId = peerId,
      deviceName = response.serverDeviceName.ifBlank { peerName },
      sharedSecret = response.secret,
      pairedAt = System.currentTimeMillis(),
      lastSeenAt = System.currentTimeMillis(),
    )
    preferences.saveTrustedPeers(
      preferences.trustedPeers().filterNot { it.deviceId == peerId } + peer,
    )
    addressBook.addManual(peerId, peer.deviceName, parsed.first, parsed.second)
    return Result.success(peer.deviceName)
  }

  /** Adds a peer the user typed manually (`host[:port]`) without a handshake. */
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
