package com.livehub.core.sync

import kotlinx.serialization.Serializable

/** Everything that can be synchronised between two LiveHub installs. */
enum class SyncScope(val wireName: String, val displayName: String) {
  FOLLOWS("follows", "关注列表"),
  PREFERENCES("preferences", "播放与弹幕设置"),
  HISTORY("history", "搜索历史"),
  ;

  companion object {
    fun fromWireName(name: String): SyncScope? =
      entries.firstOrNull { it.wireName.equals(name, ignoreCase = true) }

    val default: Set<SyncScope> = setOf(FOLLOWS, PREFERENCES)
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
    /** Bump whenever the JSON layout of any scope changes incompatibly. */
    const val SCHEMA: Int = 1

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
) {
  fun isNewer(scope: String, sequence: Long): Boolean =
    sequence > (appliedSequences[scope] ?: 0L)

  fun withApplied(scope: String, sequence: Long, at: Long): TrustedPeer {
    val next = appliedSequences.toMutableMap()
    next[scope] = maxOf(next[scope] ?: 0L, sequence)
    return copy(appliedSequences = next, lastSeenAt = at)
  }
}

/** Result of applying a remote payload. */
data class SyncApplyResult(
  val scope: SyncScope,
  val added: Int,
  val updated: Int,
  val skipped: Int,
  val rejectedReason: String? = null,
) {
  val total: Int get() = added + updated
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

/** State of the pairing handshake. */
sealed interface PairingState {
  data object Idle : PairingState

  /** The local device is showing a code that the peer must enter. */
  data class Hosting(val code: String, val expiresAt: Long) : PairingState

  data object WaitingForPeer : PairingState

  data class Paired(val peer: TrustedPeer) : PairingState

  data class Failed(val reason: String) : PairingState
}
