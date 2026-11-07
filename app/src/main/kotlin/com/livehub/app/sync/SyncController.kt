package com.livehub.app.sync

import com.livehub.app.di.FollowStore
import com.livehub.app.di.UserPreferences
import com.livehub.core.sync.CodePairing
import com.livehub.core.sync.CodePairingResult
import com.livehub.core.sync.DiscoveredPeer
import com.livehub.core.sync.PairingWindow
import com.livehub.core.sync.SyncCodec
import com.livehub.core.sync.SyncCoordinator
import com.livehub.core.sync.SyncEnvelope
import com.livehub.core.sync.SyncPayload
import com.livehub.core.sync.SyncScope
import com.livehub.core.sync.TrustedPeer
import com.livehub.core.sync.discovery.PeerDiscovery
import com.livehub.core.sync.discovery.PeerLocator
import com.livehub.core.sync.discovery.SubnetScanner
import com.livehub.core.sync.server.PairingOutcome
import com.livehub.core.sync.server.ScopeDisabledException
import com.livehub.core.sync.server.SyncHttpServer
import com.livehub.core.sync.transport.LanSyncHttpTransport
import com.livehub.core.sync.transport.LanSyncTransport
import com.livehub.core.sync.transport.PeerAddressBook
import com.livehub.core.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
  /** Notified with the real listening port as soon as the LAN server binds. */
  private val onListening: (Int) -> Unit = {},
) {
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /**
   * Where each paired device was last seen, seeded from disk and written back on every change.
   *
   * Pairing learns the peer's address from the handshake and discovery learns it from mDNS,
   * but both used to live here only for the life of the process. A [TrustedPeer] carries the
   * secret, never the address, so after a restart the app could only reach the other device
   * through mDNS — and a router that blocks multicast left every sync reporting "不在线" on a
   * network where the peer was perfectly reachable.
   */
  private val addressBook = PeerAddressBook(
    initial = preferences.peerAddresses(),
    onChange = { preferences.savePeerAddresses(it) },
  )

  /** Sweeps the local subnet for a peer whose stored address does not answer. */
  private val locator = PeerLocator()

  private var server: SyncHttpServer? = null
  private var transport: LanSyncTransport? = null
  private var httpTransport: LanSyncHttpTransport? = null
  private val discoveryJob = mutableListOf<kotlinx.coroutines.Job>()

  /**
   * The code shown on this device, with its expiry and failed-attempt budget.
   *
   * Owned here rather than in the UI so a recomposition cannot silently mint a new code
   * the other device is no longer looking at.
   */
  private val pairingWindow = PairingWindow()

  /** The code currently displayed, or null when this device is not hosting. */
  val pairingCode: String?
    get() = pairingWindow.current?.code

  /** Seconds left on the displayed code, 0 when none is showing. */
  fun pairingCodeSecondsLeft(): Long {
    val current = pairingWindow.current ?: return 0
    return ((current.expiresAt - System.currentTimeMillis()) / 1000).coerceAtLeast(0)
  }

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
   * device types it into [pairByCode], and both sides store the resulting
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
        override fun onPair(envelope: SyncEnvelope, remoteAddress: String): PairingOutcome {
          // The typed code is the whole credential here, and it is checked against the
          // live window: wrong guesses are counted per source address (pairing sweeps the
          // subnet, so uninvolved devices see this request too) and a used or expired code
          // is dead.
          val verdict = pairingWindow.verify(
            code = envelope.payload?.entries.orEmpty(),
            source = remoteAddress.ifBlank { PairingWindow.DEFAULT_SOURCE },
          )
          verdict.reason?.let { return PairingOutcome.Rejected(it) }

          // The caller told us the secret it will present; we store that, and reply
          // with our own so the caller can authenticate against us.
          val peer = engine.linkPeer(
            peerDeviceId = envelope.deviceId,
            peerDeviceName = envelope.deviceName,
            remoteSecret = envelope.secret.ifBlank { null },
          ).getOrElse { error ->
            return PairingOutcome.Rejected(error.message ?: "配对失败")
          }
          // Record where the *peer* is listening. `remoteAddress` is their IP but the
          // port must be the one their server advertised, not our own default: the
          // handshake is the only moment we learn it.
          val remotePort = envelope.preferredPort.takeIf { it in 1..65535 } ?: DEFAULT_PORT
          addressBook.addManual(envelope.deviceId, envelope.deviceName, remoteAddress, remotePort)
          // Reply with the secret WE present; the caller stores it for later requests.
          return PairingOutcome.Accepted(issuedSecret = selfSecret(), peer = peer)
        }

        // One part per request: the caller pages through them with `chunk`. Sending the
        // whole scope in one response meant a large follow list produced a single unbounded
        // document, which is the same problem the push path already solved by chunking.
        //
        // A scope the user switched off is not served at all. The engine only checks for the
        // existence of a target, so without this guard the switch stopped the *local* sync
        // loop and nothing else: the other device could still pull every follow while this
        // one had 关注列表 turned off.
        override fun onPull(
          envelope: SyncEnvelope,
          scope: SyncScope,
          peer: TrustedPeer?,
          chunkIndex: Int,
        ): SyncPayload? {
          if (!isShared(scope)) return null
          return engine.buildPullPayload(scope, chunkIndex)
        }

        override fun onPush(envelope: SyncEnvelope, payload: SyncPayload, peer: TrustedPeer?): SyncPayload? {
          val scope = payload.parsedScope ?: return null
          // Incoming data for a switched-off scope is refused rather than stored: "关掉关注列表
          // 同步" has to mean the list stays put, not just that it stops being sent.
          if (!isShared(scope)) throw ScopeDisabledException(scope)
          engine.applyPayload(payload, peer)
          // Answer with the delta only: the peer provably holds every record it just sent,
          // so echoing those back would double the traffic for no gain. Anything else this
          // device knows still has to travel, otherwise a record the peer lacks would never
          // reach it — the push carries what *we* were missing, not what they are.
          return engine.buildPayload(
            scope = scope,
            peerKnown = SyncCodec.decodeRecords(payload.entries),
          )
        }

        override fun peerById(deviceId: Long): TrustedPeer? =
          preferences.trustedPeers().firstOrNull { it.deviceId == deviceId }
      },
    )
    listeningPort = httpServer.start()
    server = httpServer
    // Published before discovery starts, so the mDNS record carries the port the server
    // really bound (it falls back to an ephemeral one when the preferred port is taken).
    onListening(listeningPort)

    transport = LanSyncTransport(
      deviceId = deviceId,
      deviceName = deviceName,
      localSecret = selfSecret(),
      peersProvider = { preferences.trustedPeers() },
      addressProvider = { peer -> addressBook.addressFor(peer) },
      localPort = listeningPort,
    )
    // Same transport, exposed as the one-method shape CodePairing probes candidates with.
    transport?.let { lan ->
      httpTransport = LanSyncHttpTransport(lan, addressProvider = { addressBook.addressFor(it) })
    }

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
    httpTransport = null
    discovered.clear()
    // An open code must not outlive the page it was shown on: it is a live credential.
    pairingWindow.cancel()
  }

  fun dispose() {
    stop()
    scope.cancel()
  }

  /**
   * Issues the code this device shows.
   *
   * Re-issuing is explicit rather than automatic-on-render: the code is what the other
   * device types, so replacing it behind the user's back would strand them mid-entry.
   */
  fun beginPairing(): String = pairingWindow.issue().code

  /** Registers a peer discovered automatically so it can be reached later. */
  fun rememberPeer(peer: DiscoveredPeer) {
    addressBook.remember(peer)
  }

  /**
   * Pairs with the device that is showing [code], without being told any address.
   *
   * Candidates are the mDNS sightings plus a /24 sweep of this network, and every one of
   * them is offered the code. The device holding that code accepts; every other address
   * either refuses or never answers, and neither of those is an error until *nothing*
   * accepts.
   *
   * Suspending and dispatched to [Dispatchers.IO]: this opens sockets, and the caller is
   * `viewModelScope`. Android throws `NetworkOnMainThreadException` (with a null message)
   * for a connect on the main thread, which once surfaced as a bare "同步失败".
   */
  suspend fun pairByCode(code: String): Result<String> = withContext(Dispatchers.IO) {
    val attempts = pairingCandidates()
    if (attempts.isEmpty()) {
      return@withContext Result.failure(IllegalStateException("还没发现附近设备，请确认两台设备连的是同一个 Wi-Fi"))
    }
    val currentTransport = httpTransport
      ?: return@withContext Result.failure(IllegalStateException("同步服务未启动，请重新打开同步页"))

    val pairing = CodePairing(
      deviceId = preferences.deviceId,
      deviceName = preferences.deviceName,
      localSecret = selfSecret(),
      localPort = listeningPort,
    )

    when (val outcome = pairing.pair(code, attempts, currentTransport)) {
      is CodePairingResult.Paired -> {
        preferences.saveTrustedPeers(
          preferences.trustedPeers().filterNot { it.deviceId == outcome.peer.deviceId } + outcome.peer,
        )
        // Where the device actually answered, so the first sync does not have to search
        // for it again.
        addressBook.addManual(
          deviceId = outcome.address.deviceId,
          name = outcome.address.deviceName,
          host = outcome.address.host,
          port = outcome.address.port,
        )
        discovered[outcome.peer.deviceId] = outcome.address
        Result.success(outcome.peer.deviceName)
      }
      else -> Result.failure(IllegalStateException(outcome.message ?: "配对失败"))
    }
  }

  /**
   * Every address worth offering the code to.
   *
   * mDNS sightings come first because they are already known-good and carry names; the
   * subnet sweep fills in when multicast is blocked.
   *
   * Already-linked devices are excluded, and they have to be excluded **by address as well
   * as by id**: sweep entries carry `deviceId = 0` because an address alone says nothing
   * about identity, so filtering on the id alone let a freshly linked peer's IP straight
   * back into the next sweep, where it would be re-paired and have its secret churned.
   */
  private fun pairingCandidates(): List<DiscoveredPeer> {
    val peers = preferences.trustedPeers()
    val pairedIds = peers.map { it.deviceId }.toSet()
    // Addresses of linked peers. A `TrustedPeer` does not carry one, so it comes from the
    // address book and from whatever discovery last saw under that id.
    val pairedHosts = peers.mapNotNull { peer -> addressBook.addressFor(peer)?.host }.toSet()
    val known = discoveredPeers().filterNot { pairedIds.contains(it.deviceId) }
    // The sweep only has to cover what discovery missed, so already-known hosts are
    // dropped from it rather than tried twice.
    val knownHosts = known.map { it.host }.toSet()
    val sweep = SubnetScanner.candidates(listeningPort.takeIf { it > 0 } ?: DEFAULT_PORT)
      .filterNot { knownHosts.contains(it.host) || pairedHosts.contains(it.host) }
    return known + sweep
  }

  /**
   * Runs a full exchange. Returns a human readable summary for the snackbar.
   *
   * Every step opens a socket, so the whole exchange runs on [Dispatchers.IO]: the
   * caller is `viewModelScope`, and a blocking socket call on the main thread throws
   * `NetworkOnMainThreadException`.
   */
  suspend fun syncNow(): SyncSummary = withContext(Dispatchers.IO) {
    val currentTransport = transport ?: return@withContext SyncSummary(0, 0, "同步服务未启动")
    val coordinator = SyncCoordinator(engine, currentTransport)
    val peers = preferences.trustedPeers()
    if (peers.isEmpty()) return@withContext SyncSummary(0, 0, "尚未配对任何设备")

    var pulled = 0
    var pushed = 0
    var failures = 0
    var skipped = 0
    var firstFailure: String? = null
    peers.forEach { peer ->
      // Before anything is exchanged: a stored address is only worth using once it answers.
      ensureReachable(peer)
      coordinator.syncWith(peer).forEach { outcome ->
        pulled += outcome.pulled
        pushed += outcome.pushed
        if (!outcome.success) {
          failures++
          if (firstFailure == null) firstFailure = "${peer.deviceName} ${outcome.error}"
        }
        if (outcome.skipped) skipped++
      }
    }
    val message = when {
      // Name the peer and the reason. "部分同步失败 (1)" said nothing about *which* device
      // failed or why; the reason only existed in a log row two scroll lengths further down.
      failures > 0 && pulled == 0 && pushed == 0 -> "同步失败：" + (firstFailure ?: "$failures 项")
      failures > 0 -> "部分同步失败：" + (firstFailure ?: "$failures 项")
      // Nothing moved and nothing failed, because the other device has these scopes switched
      // off. Saying "已是最新" would claim the two devices agree, which was never checked.
      pulled == 0 && pushed == 0 && skipped > 0 -> "对方未开启所选同步内容"
      pulled == 0 && pushed == 0 -> "已是最新"
      else -> "同步完成：接收 $pulled，发送 $pushed"
    }
    SyncSummary(pulled, pushed, message)
  }

  /**
   * Makes sure [peer] can be reached before the exchange starts.
   *
   * Two things are repaired here, and both used to end as "不在线" on the sync button: the
   * address was never persisted (so a restarted app only had mDNS to go on), and a DHCP lease
   * that moved left a stored address that no longer answers. The ping is one cheap round trip
   * over the address already known, so the common case costs nothing; only a peer that fails
   * it is searched for.
   */
  private suspend fun ensureReachable(peer: TrustedPeer) {
    val current = transport ?: return
    if (addressBook.addressFor(peer) != null && current.probe(peer)) return
    locate(peer)
  }

  /**
   * Sweeps this network for [peer] and remembers where it answered.
   *
   * Pairing has always survived a router that blocks multicast — it offers the code to every
   * address on the local /24 ([CodePairing]). Sync could not: it knew one address per peer,
   * and on such a network the only outcome was "不在线" while the other device sat on the same
   * Wi-Fi, reachable over plain TCP. The ping endpoint answers with the server's device id, so
   * the sweep can identify the peer without pairing it again.
   */
  private suspend fun locate(peer: TrustedPeer): DiscoveredPeer? {
    val current = transport ?: return null
    val port = addressBook.addressFor(peer)?.port?.takeIf { it in 1..65535 }
      ?: listeningPort.takeIf { it > 0 }
      ?: DEFAULT_PORT
    // Read once: `preferences.deviceId` is a blocking DataStore read, and the sweep asks for
    // the envelope up to 254 times.
    val envelope = SyncEnvelope(
      deviceId = preferences.deviceId,
      deviceName = preferences.deviceName,
      secret = selfSecret(),
      preferredPort = listeningPort,
    )
    val candidates = discoveredPeers().filter { it.deviceId == peer.deviceId } +
      SubnetScanner.candidates(port)

    val found = locator.locate(peer.deviceId, candidates) { candidate ->
      runCatching {
        current.call(
          peer = peer,
          host = candidate.host,
          port = candidate.port,
          path = SyncEnvelope.PATH_PING,
          envelope = envelope,
          timeoutMillis = LOCATE_ATTEMPT_TIMEOUT_MILLIS,
        ).serverDeviceId
      }.getOrNull()
    } ?: return null

    val address = found.copy(deviceId = peer.deviceId, deviceName = peer.deviceName)
    addressBook.remember(address)
    return address
  }

  /** Probes every paired peer so the UI can show who is online. */
  suspend fun onlinePeers(): Set<Long> = withContext(Dispatchers.IO) {
    val currentTransport = transport ?: return@withContext emptySet()
    preferences.trustedPeers()
      .filter { currentTransport.probe(it) }
      .map { it.deviceId }
      .toSet()
  }

  data class SyncSummary(val pulled: Int, val pushed: Int, val message: String)

  /**
   * Whether the user still has this scope switched on.
   *
   * The single question both server paths ask. `SyncTargets.enabledScopes()` is read from the
   * settings on every call, so flipping a chip takes effect on the next request without
   * restarting the server.
   */
  private fun isShared(scope: SyncScope): Boolean = engine.enabledScopes().contains(scope)

  /**
   * Secret this installation presents to peers.
   *
   * Derived from the persisted device id so it survives restarts and is identical for
   * every peer: a peer stores exactly this value and compares it on each request.
   */
  private fun selfSecret(): String =
    preferences.settings.selfSecret.ifBlank {
      val generated = java.util.UUID.randomUUID().toString().replace("-", "")
      preferences.update { it.copy(selfSecret = generated) }
      generated
    }

  companion object {
    const val DEFAULT_PORT = 47653

    /**
     * Per-address budget while sweeping for a peer.
     *
     * A live device answers a ping in milliseconds; anything slower than this is an address
     * with nothing behind it, and waiting longer would only stall a 254 address sweep.
     */
    private const val LOCATE_ATTEMPT_TIMEOUT_MILLIS = 350

    /**
     * Secret used for the very first exchange. Pairing replaces it with a per-peer
     * random value, so this constant never protects real data.
     */
    private const val CURRENT_SECRET = "livehub-pairing"
  }
}
