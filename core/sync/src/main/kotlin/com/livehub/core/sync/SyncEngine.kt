package com.livehub.core.sync

import kotlinx.serialization.json.JsonObject
import java.util.UUID

/**
 * Peer-to-peer sync engine.
 *
 * The model follows WeChat's cross-device input-method sync: devices discover each
 * other on the LAN, pair once with a code, then exchange **incremental** payloads that
 * carry hybrid logical clocks and tombstones. Nothing is ever "last writer by wall
 * clock": ordering is decided by [Hlc] and a device-id tie break.
 *
 * The engine is transport free. [SyncTransport] is a one-method interface so the same
 * logic runs over an HTTP server in production and over an in-memory channel in tests.
 */
class SyncEngine(
  private val targets: SyncTargets,
  private val deviceIdProvider: () -> Long,
  private val deviceNameProvider: () -> String,
  private val peersProvider: () -> List<TrustedPeer>,
  private val peersWriter: (List<TrustedPeer>) -> Unit,
  private val clock: () -> Long = System::currentTimeMillis,
  private val idGenerator: () -> String = { UUID.randomUUID().toString() },
) {
  /** Local physical/counter state, persisted implicitly through record timestamps. */
  @Volatile private var lastHlc: Hlc = Hlc.ZERO

  @Volatile private var sequence: Long = 0

  private val log = mutableListOf<SyncLogEntry>()

  val deviceId: Long get() = deviceIdProvider()

  val deviceName: String get() = deviceNameProvider()

  /** Records observed by this device, newest last. */
  fun logEntries(): List<SyncLogEntry> = log.toList()

  /** Advances the clock and returns the timestamp for a local mutation. */
  fun nextHlc(): Hlc {
    val next = lastHlc.tick(clock())
    lastHlc = next
    return next
  }

  /** Converts a remote clock into the local one so causality is preserved. */
  fun observeRemote(remote: Hlc) {
    lastHlc = lastHlc.merge(remote, clock())
  }

  /**
   * Builds the payload describing everything this device knows about [scope].
   *
   * @param peerKnown what the peer already holds for this scope. Anything the peer has at
   *   the same or a newer version is left out: it would lose the merge on their side and
   *   is therefore pure traffic. Pass the payload a peer just returned from `pull` to turn
   *   a full resend into a delta.
   */
  fun buildPayload(
    scope: SyncScope,
    peerKnown: Collection<SyncRecord<JsonObject>> = emptyList(),
  ): SyncPayload? = buildPayloads(scope, peerKnown, maxRecordsPerPayload = Int.MAX_VALUE)
    .firstOrNull()

  /**
   * Builds the payloads needed to bring [scope] up to date with a peer.
   *
   * Returns an empty list when the local scope is disabled. Otherwise the result is never
   * empty: an up-to-date peer still gets one (record-less) payload so the exchange keeps
   * its single request/response shape and the log can report "nothing to send".
   */
  fun buildPayloads(
    scope: SyncScope,
    peerKnown: Collection<SyncRecord<JsonObject>> = emptyList(),
    maxRecordsPerPayload: Int = SyncPayload.MAX_RECORDS_PER_PAYLOAD,
  ): List<SyncPayload> {
    require(maxRecordsPerPayload > 0) { "maxRecordsPerPayload must be positive" }
    val target = targets.targetFor(scope) ?: return emptyList()
    val pending = pendingRecords(target, peerKnown)

    val chunkSize = maxRecordsPerPayload
    // `pending.size + chunkSize - 1` overflows when the caller passes Int.MAX_VALUE to mean
    // "no limit" (which buildPayload does), so the comparison comes first.
    val chunkCount = when {
      pending.isEmpty() -> 1
      pending.size <= chunkSize -> 1
      else -> (pending.size + chunkSize - 1) / chunkSize
    }
    return (0 until chunkCount).map { index ->
      val slice = if (pending.isEmpty()) {
        emptyList()
      } else {
        pending.subList(index * chunkSize, minOf((index + 1) * chunkSize, pending.size))
      }
      sequence += 1
      SyncCodec.buildPayload(
        scope = scope,
        records = slice,
        deviceId = deviceId,
        sequence = sequence,
        now = clock(),
        chunkIndex = index,
        chunkCount = chunkCount,
      )
    }
  }

  /**
   * Builds one part of the payloads needed to bring [scope] up to date with a peer.
   *
   * Used by the LAN server, which returns one part per request: [chunkIndex] is clamped to
   * the available range, and the returned payload reports `chunkCount` so the caller knows
   * whether to ask for another. Note this deliberately uses the default record cap — the
   * pull path is the one place where a scope really can exceed a single response.
   */
  fun buildPullPayload(scope: SyncScope, chunkIndex: Int): SyncPayload? {
    val payloads = buildPayloads(scope)
    if (payloads.isEmpty()) return null
    return payloads[chunkIndex.coerceIn(0, payloads.lastIndex)]
  }

  /**
   * Local records the peer still needs.
   *
   * The comparison is exactly the one [SyncMerge] applies, so a record is skipped only
   * when the peer's copy would win the merge anyway. Tombstones go through the same rule,
   * which keeps "unfollow on the tablet" propagating to a phone that has never heard of it.
   */
  private fun pendingRecords(
    target: SyncTarget,
    peerKnown: Collection<SyncRecord<JsonObject>>,
  ): List<SyncRecord<JsonObject>> {
    val known: Map<String, SyncRecord<JsonObject>> =
      if (peerKnown.isEmpty()) emptyMap() else peerKnown.associateBy { it.key }
    return target.snapshot().mapNotNull { record ->
      val local = SyncRecord(
        key = record.key,
        value = record.value as? JsonObject,
        deleted = record.deleted,
        hlc = record.hlc,
        deviceId = record.deviceId,
        updatedAt = record.updatedAt,
      )
      val peerRecord = known[local.key]
      if (peerRecord == null || local.version > peerRecord.version) local else null
    }
  }

  /**
   * Applies a payload received from [peer].
   *
   * Rejects payloads from a newer schema, from an unknown scope, or one whose sequence
   * has already been applied — the latter also makes the exchange idempotent, so a
   * retried request can never duplicate records.
   */
  fun applyPayload(payload: SyncPayload, peer: TrustedPeer?): SyncApplyResult {
    val scope = payload.parsedScope
      ?: return SyncApplyResult(rejectedReason = "未知的同步范围: ${payload.scope}")

    if (payload.schema > SyncPayload.SCHEMA) {
      return SyncApplyResult(rejectedReason = "对端版本过新 (schema=${payload.schema})")
    }

    if (peer != null && !peer.isNewer(scope.wireName, payload.sequence)) {
      return SyncApplyResult(rejectedReason = "该版本已应用 (sequence=${payload.sequence})")
    }

    val target = targets.targetFor(scope)
      ?: return SyncApplyResult(rejectedReason = "本机不支持该范围: ${scope.displayName}")

    val incoming = SyncCodec.decodeRecords(payload.entries)
    val local = target.snapshot().map { record ->
      SyncRecord(
        key = record.key,
        value = record.value as? JsonObject,
        deleted = record.deleted,
        hlc = record.hlc,
        deviceId = record.deviceId,
        updatedAt = record.updatedAt,
      )
    }

    incoming.forEach { observeRemote(it.hlc) }

    val outcome = SyncMerge.merge(local, incoming)
    // Tombstones every peer has already confirmed are dropped here, not by a timer: the rule
    // needs the peer watermarks, and an exchange is the only moment at which they are known to
    // be current. See [SyncMerge.prune].
    val merged = SyncMerge.prune(outcome.merged, peersProvider(), scope)
    val changed = target.apply(merged)

    peer?.let { known ->
      val updated = known.withApplied(scope.wireName, payload.sequence, clock())
      peersWriter(peersProvider().map { if (it.deviceId == updated.deviceId) updated else it })
    }

    record(
      SyncLogEntry(
        at = clock(),
        direction = SyncLogEntry.Direction.PULL,
        peerName = peer?.deviceName ?: "未知设备",
        scope = scope.displayName,
        itemCount = outcome.remoteWins,
        success = true,
        note = if (changed == 0) "无变化" else "更新 $changed 条",
      ),
    )

    // `remoteWins` is what actually reached the store: records the peer knew and we did not,
    // plus records whose version beat ours (which replace a local record rather than adding
    // one, hence `applied` rather than `added`).
    return SyncApplyResult(applied = outcome.remoteWins)
  }

  /**
   * Records that [peer] now holds everything this device knows for [scope].
   *
   * Called by the coordinator after a scope's exchange finished without an error: the pull
   * showed what the peer had, and the push delivered the rest, so from that moment the peer's
   * copy is at least as new as the highest clock this device holds. [SyncMerge.prune] uses the
   * value to decide which tombstones are safe to drop.
   */
  fun confirmPeer(peer: TrustedPeer, scope: SyncScope) {
    val target = targets.targetFor(scope) ?: return
    val highest = target.snapshot().maxOfOrNull { it.hlc } ?: return
    // Read the stored peer rather than the caller's copy: the exchange has just written
    // `appliedSequences` and `lastSeenAt` through `applyPayload`, and copying the stale object
    // back would undo the replay guard for the next payload.
    val peers = peersProvider()
    val stored = peers.firstOrNull { it.deviceId == peer.deviceId } ?: return
    val updated = stored.withConfirmed(scope, highest)
    if (updated == stored) return
    peersWriter(peers.map { if (it.deviceId == stored.deviceId) updated else it })
  }

  /** Records a push to [peer] in the history shown in the sync screen. */
  fun recordPush(
    peer: TrustedPeer,
    scope: SyncScope,
    itemCount: Int,
    success: Boolean,
    note: String? = null,
    /**
     * Which arrow the row shows.
     *
     * A skipped scope is not a push and not a pull — nothing moved — but it was the *pull* half
     * of the exchange that found it, so the caller passes the direction it was attempted in.
     */
    direction: SyncLogEntry.Direction = SyncLogEntry.Direction.PUSH,
  ) {
    record(
      SyncLogEntry(
        at = clock(),
        direction = direction,
        peerName = peer.deviceName,
        scope = scope.displayName,
        itemCount = itemCount,
        success = success,
        note = note,
      ),
    )
  }

  // ------------------------------------------------------------------- pairing

  /**
   * Records the [TrustedPeer] for a peer whose code was just accepted.
   *
   * The code is **not** checked here. [PairingWindow] owns the code, its expiry, its
   * per-source attempt budget and its single-use rule, and the server calls it before this
   * method ever runs. Accepting an `expectedCode` here as well only produced a second,
   * weaker check: the caller had to pass the code it had just verified, so the comparison
   * was `code == code` and could never fail.
   *
   * The stored `sharedSecret` is the secret **the peer issued for itself**, not one this
   * device generates: `SyncHttpServer.authenticate` compares the caller's `secret` field
   * against the stored value, so a device must hold the [remoteSecret] the other side
   * will present. Generating it locally produced two unrelated secrets and every
   * authenticated request failed.
   *
   * @param remoteSecret the secret the peer presented for itself.
   * @param issuedSecret the secret this device issues for the peer to present; only used
   *   when the peer has not identified itself with one.
   */
  fun linkPeer(
    peerDeviceId: Long,
    peerDeviceName: String,
    remoteSecret: String? = null,
    issuedSecret: String? = null,
  ): Result<TrustedPeer> {
    // A generated secret is dash-stripped so it is URL and QR friendly. A secret the peer
    // *presented* is stored verbatim: rewriting it means this device compares a mangled
    // value against whatever the peer sends, and every authenticated request 403s. The
    // old code ran `.replace("-", "")` over both paths, which silently corrupted any peer
    // whose secret contained a dash.
    val secret = remoteSecret?.takeIf { it.isNotBlank() }
      ?: (issuedSecret ?: idGenerator()).replace("-", "")
    if (secret.isBlank()) {
      return Result.failure(IllegalStateException("配对密钥为空"))
    }
    val peer = TrustedPeer(
      deviceId = peerDeviceId,
      deviceName = peerDeviceName,
      sharedSecret = secret,
      pairedAt = clock(),
      lastSeenAt = clock(),
    )
    val existing = peersProvider().filterNot { it.deviceId == peerDeviceId }
    peersWriter(existing + peer)
    return Result.success(peer)
  }

  fun unpair(peerDeviceId: Long) {
    peersWriter(peersProvider().filterNot { it.deviceId == peerDeviceId })
  }

  fun peers(): List<TrustedPeer> = peersProvider()

  /** Scopes this device is currently willing to exchange. */
  fun enabledScopes(): Set<SyncScope> = targets.enabledScopes()

  private fun record(entry: SyncLogEntry) {
    synchronized(log) {
      log.add(0, entry)
      while (log.size > MAX_LOG_ENTRIES) log.removeAt(log.lastIndex)
    }
  }

  companion object {
    const val MAX_LOG_ENTRIES = 100
  }
}

/**
 * Transport used to reach a peer.
 *
 * Implementations may be HTTP over the LAN, a loopback pipe in tests, or a cloud relay
 * later; the engine itself never knows the difference.
 */
interface SyncTransport {
  /** Fetches what [peer] currently knows for [scope]. */
  suspend fun pull(peer: TrustedPeer, scope: SyncScope): SyncPayload?

  /** Sends local [payload] to [peer]; returns their payload in exchange. */
  suspend fun push(peer: TrustedPeer, payload: SyncPayload): SyncPayload?

  /** Cheap reachability probe used before showing a peer as online. */
  suspend fun probe(peer: TrustedPeer): Boolean
}
