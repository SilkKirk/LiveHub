package com.livehub.core.sync

import kotlinx.serialization.Serializable

/**
 * What gets synchronised between two LiveHub installs.
 *
 * Two scopes, and both are data whose *value* is destroyed by owning two devices: the library
 * (which rooms you care about, how you filed them into groups, which partitions you pinned) and
 * the login state (who you are on Bilibili / Douyin).
 *
 * `FOLLOWS` kept its wire name while growing into the library scope. It is deliberately one
 * scope and not four: a `member:` record references a `group:` record, so both have to land in
 * the same merge. [com.livehub.core.sync.SyncCoordinator] iterates scopes and treats a failed
 * scope as `continue`, so splitting them would make "a membership pointing at a group this
 * device has not received yet" a real, reachable state.
 *
 * Login state only ever reaches a **paired** device: the server rejects unauthenticated
 * requests before a payload is read
 * ([com.livehub.core.sync.server.SyncHttpServer.authenticate]), and unpairing stops it.
 */
enum class SyncScope(val wireName: String, val displayName: String) {
  FOLLOWS("follows", "关注、分组与收藏"),
  CREDENTIALS("credentials", "登录状态"),
  ;

  companion object {
    fun fromWireName(name: String): SyncScope? =
      entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }

    val default: Set<SyncScope> = setOf(FOLLOWS, CREDENTIALS)
  }
}

/**
 * The unit of transfer in the sync protocol.
 *
 * A payload always describes **one** scope so peers can accept some scopes and
 * reject others. [entries] is JSON encoded with the schema version in [schema]
 * so older peers can refuse unknown layouts instead of corrupting local state.
 */
@Serializable
data class SyncPayload(
  val scope: String,
  val schema: Int = SCHEMA,
  /** Random id of the producing installation. */
  val sourceDeviceId: Long,
  /** Monotonic per-device sequence, used for de-duplication. */
  val sequence: Long,
  val createdAt: Long,
  /** JSON document; the format depends on [scope]. */
  val entries: String,
  /**
   * Position of this payload inside a multi-part transfer, starting at 0.
   *
   * A scope with more records than [MAX_RECORDS_PER_PAYLOAD] is split so a single HTTP
   * response never has to hold an unbounded document. Every part carries its own
   * [sequence], so the receiver's de-duplication guard works per part and a retry after a
   * partial transfer is still accepted.
   */
  val chunkIndex: Int = 0,
  /** Number of parts in this transfer; 1 for the common single-payload case. */
  val chunkCount: Int = 1,
) {
  val parsedScope: SyncScope? get() = SyncScope.fromWireName(scope)

  /** True when this payload is not the last part of a split transfer. */
  val hasMoreChunks: Boolean get() = chunkIndex + 1 < chunkCount

  /**
   * How many records this payload carries.
   *
   * Used for the human-facing "sent N items" count. It is deliberately *not* [sequence]:
   * the sequence is a monotonic per-device counter used for de-duplication, so reporting it
   * as a transfer size showed numbers that had nothing to do with the data exchanged.
   * Malformed entries decode to an empty list rather than throwing, because this is only
   * used for reporting.
   */
  val recordCount: Int
    get() = runCatching { SyncCodec.decodeRecords(entries).size }.getOrDefault(0)

  companion object {
    /**
     * Bumped whenever the JSON layout of any scope changes incompatibly.
     *
     * 2 — the follow scope became the *library* scope: its record keys are now prefixed
     * (`room:` / `group:` / `fav:` / `member:`) instead of being bare `<platform>:<roomId>`.
     * A peer on schema 1 is refused with "对端版本过新" rather than being handed keys it would
     * silently misread as rooms.
     */
    const val SCHEMA: Int = 2

    /**
     * Upper bound on the records carried by one payload.
     *
     * The follow list is the only scope that can realistically grow past this, but the
     * limit applies to all of them so no scope can produce a response that is slow to
     * serialise or too large to read in one buffered request.
     */
    const val MAX_RECORDS_PER_PAYLOAD: Int = 500
  }
}

/** A peer that the local installation already paired with. */
@Serializable
data class TrustedPeer(
  val deviceId: Long,
  val deviceName: String,
  /** Long-lived pairing secret; never leaves the device unencrypted. */
  val sharedSecret: String,
  val pairedAt: Long,
  /** Highest [SyncPayload.sequence] successfully applied per scope. */
  val appliedSequences: Map<String, Long> = emptyMap(),
  val lastSeenAt: Long = 0,
  /**
   * Highest clock, per scope, that this peer is known to hold.
   *
   * Written after an exchange with the peer finished for that scope, where the pull showed what
   * the peer had and the push delivered the rest: at that moment the peer's copy is at least as
   * new as everything this device held, up to the highest [Hlc] in the local record set.
   *
   * It is the only sound input for dropping tombstones — a tombstone below every peer's
   * watermark has provably been delivered to all of them, so none of them can resurrect the
   * record it deletes. See [SyncMerge.prune].
   */
  val confirmedHlc: Map<String, String> = emptyMap(),
) {
  fun isNewer(scope: String, sequence: Long): Boolean =
    sequence > (appliedSequences[scope] ?: 0L)

  fun withApplied(scope: String, sequence: Long, at: Long): TrustedPeer {
    val next = appliedSequences.toMutableMap()
    next[scope] = maxOf(next[scope] ?: 0L, sequence)
    return copy(appliedSequences = next, lastSeenAt = at)
  }

  /** Highest clock this peer is known to hold for [scope]; [Hlc.ZERO] when never confirmed. */
  fun confirmed(scope: SyncScope): Hlc = Hlc.decode(confirmedHlc[scope.wireName])

  fun withConfirmed(scope: SyncScope, hlc: Hlc): TrustedPeer {
    val previous = confirmed(scope)
    if (hlc <= previous) return this
    return copy(confirmedHlc = confirmedHlc + (scope.wireName to hlc.encode()))
  }
}

/**
 * Result of applying a remote payload.
 *
 * Two fields, because both were lies in the previous shape: `updated` was always 0 (nothing ever
 * counted an update separately from an insert), `skipped` and `scope` were never read, and
 * `total = added + updated` therefore just restated `added`. A record the peer won may replace a
 * local one, so the count is [applied].
 */
data class SyncApplyResult(
  /** How many remote versions won the merge and reached the store. */
  val applied: Int = 0,
  val rejectedReason: String? = null,
) {
  val accepted: Boolean get() = rejectedReason == null
}

/** One entry in the human readable sync history shown in the UI. */
@Serializable
data class SyncLogEntry(
  val at: Long,
  val direction: Direction,
  val peerName: String,
  val scope: String,
  val itemCount: Int,
  val success: Boolean,
  val note: String? = null,
) {
  @Serializable
  enum class Direction { PUSH, PULL }
}

/** Transport agnostic description of a reachable peer. */
@Serializable
data class DiscoveredPeer(
  val deviceId: Long,
  val deviceName: String,
  val host: String,
  val port: Int,
  /** True when mDNS advertised it, false when entered manually. */
  val discovered: Boolean = true,
) {
  val address: String get() = "http://$host:$port"
}
