package com.livehub.core.sync

import kotlinx.serialization.Serializable

/**
 * A single synchronised record with conflict metadata.
 *
 * LiveHub follows the same model that WeChat's input method uses for its cross
 * device dictionary: every mutation is stamped with a **hybrid logical clock**
 * (`hlc`) instead of a wall clock, deletions leave a tombstone instead of simply
 * disappearing, and two devices reconcile by comparing `(hlc, deviceId)`.
 *
 * That combination is what makes "关注 on phone then unfollow on tablet" behave
 * deterministically even when the two devices have skewed clocks or sync in a
 * different order than the operations happened.
 */
@Serializable
data class SyncRecord<T>(
  /** Stable identity, e.g. `douyu:12345` for a followed room. */
  val key: String,
  /** Current value; meaningless when [deleted] is true. */
  val value: T? = null,
  /** Tombstone flag. Deleted records are kept so the delete can propagate. */
  val deleted: Boolean = false,
  /** Hybrid logical clock of the last mutation. */
  val hlc: Hlc = Hlc.ZERO,
  /** Tie breaker when two devices produced the same [hlc]. */
  val deviceId: Long = 0,
  /** Wall clock of the last mutation, for UI display only. */
  val updatedAt: Long = 0,
) {
  /** Ordering used to decide which of two versions of the same key wins. */
  val version: RecordVersion get() = RecordVersion(hlc, deviceId)

  companion object {
    fun <T> alive(key: String, value: T, hlc: Hlc, deviceId: Long, updatedAt: Long): SyncRecord<T> =
      SyncRecord(key = key, value = value, deleted = false, hlc = hlc, deviceId = deviceId, updatedAt = updatedAt)

    fun <T> tombstone(key: String, hlc: Hlc, deviceId: Long, updatedAt: Long): SyncRecord<T> =
      SyncRecord(key = key, value = null, deleted = true, hlc = hlc, deviceId = deviceId, updatedAt = updatedAt)
  }
}

/** Total order over record versions: HLC first, device id breaks ties. */
@Serializable
data class RecordVersion(val hlc: Hlc, val deviceId: Long) : Comparable<RecordVersion> {
  override fun compareTo(other: RecordVersion): Int {
    val byClock = hlc.compareTo(other.hlc)
    return if (byClock != 0) byClock else deviceId.compareTo(other.deviceId)
  }
}

/**
 * Hybrid logical clock (Kulkarni et al.), serialised as `"<physical>-<counter>-<deviceId>"`.
 *
 * It behaves like a Lamport clock that stays close to wall-clock time, so the sync
 * log can show meaningful timestamps while still guaranteeing a total order.
 */
@Serializable
data class Hlc(val physical: Long = 0, val counter: Long = 0) : Comparable<Hlc> {
  override fun compareTo(other: Hlc): Int {
    val byPhysical = physical.compareTo(other.physical)
    return if (byPhysical != 0) byPhysical else counter.compareTo(other.counter)
  }

  /** Produces the next local timestamp for a mutation happening now. */
  fun tick(nowMillis: Long): Hlc =
    if (nowMillis > physical) Hlc(nowMillis, 0) else Hlc(physical, counter + 1)

  /** Merges a remote timestamp seen on the wire into the local clock. */
  fun merge(remote: Hlc, nowMillis: Long): Hlc = when {
    nowMillis > physical && nowMillis > remote.physical -> Hlc(nowMillis, 0)
    physical == remote.physical -> Hlc(physical, maxOf(counter, remote.counter) + 1)
    physical > remote.physical -> Hlc(physical, counter + 1)
    else -> Hlc(remote.physical, remote.counter + 1)
  }

  fun encode(): String = "$physical-$counter"

  override fun toString(): String = encode()

  companion object {
    val ZERO = Hlc(0, 0)

    fun decode(raw: String?): Hlc {
      if (raw.isNullOrBlank()) return ZERO
      val parts = raw.split('-')
      if (parts.size != 2) return ZERO
      return Hlc(
        physical = parts[0].toLongOrNull() ?: 0L,
        counter = parts[1].toLongOrNull() ?: 0L,
      )
    }
  }
}

/** Outcome of merging a local and a remote record set. */
data class MergeOutcome<T>(
  val merged: List<SyncRecord<T>>,
  val localWins: Int,
  val remoteWins: Int,
  val identical: Int,
) {
  val changed: Boolean get() = remoteWins > 0
}

/**
 * The conflict resolution rules shared by every synchronised scope.
 *
 * The rules are deliberately simple and total:
 * 1. If only one side knows the key, that side wins — including tombstones, so a
 *    delete performed while offline still propagates.
 * 2. If both sides know it, the higher [RecordVersion] wins.
 * 3. Ties (identical version) are treated as identical, keeping the local record.
 */
object SyncMerge {

  fun <T> merge(
    local: Collection<SyncRecord<T>>,
    remote: Collection<SyncRecord<T>>,
  ): MergeOutcome<T> {
    val localByKey = local.associateBy { it.key }
    val remoteByKey = remote.associateBy { it.key }
    val keys = LinkedHashSet<String>(localByKey.size + remoteByKey.size)
    keys.addAll(localByKey.keys)
    keys.addAll(remoteByKey.keys)

    var localWins = 0
    var remoteWins = 0
    var identical = 0
    val merged = ArrayList<SyncRecord<T>>(keys.size)

    keys.forEach { key ->
      val left = localByKey[key]
      val right = remoteByKey[key]
      when {
        left == null && right != null -> {
          merged += right
          remoteWins++
        }
        left != null && right == null -> {
          merged += left
          localWins++
        }
        left != null && right != null -> {
          val cmp = left.version.compareTo(right.version)
          when {
            cmp > 0 -> {
              merged += left
              localWins++
            }
            cmp < 0 -> {
              merged += right
              remoteWins++
            }
            else -> {
              merged += left
              identical++
            }
          }
        }
      }
    }

    return MergeOutcome(merged, localWins, remoteWins, identical)
  }

  /** Drops tombstones that every known peer has already observed. */
  fun <T> prune(
    records: Collection<SyncRecord<T>>,
    peers: Collection<TrustedPeer>,
    nowMillis: Long,
    retentionMillis: Long = TOMBSTONE_RETENTION_MILLIS,
  ): List<SyncRecord<T>> {
    if (peers.isEmpty()) return records.toList()
    return records.filter { record ->
      if (!record.deleted) {
        true
      } else {
        val expired = nowMillis - record.updatedAt > retentionMillis
        val everyoneSawIt = peers.all { peer ->
          (peer.appliedSequences.entries.firstOrNull { it.key == TOMBSTONE_SCOPE }?.value ?: 0L) >=
            record.hlc.physical
        }
        !(expired || everyoneSawIt)
      }
    }
  }

  /** Tombstones are retained for 30 days; long enough for a rarely used tablet. */
  const val TOMBSTONE_RETENTION_MILLIS: Long = 30L * 24 * 60 * 60 * 1000

  /**
   * Synthetic scope key used by [prune]; tombstones are only dropped once the peer
   * has acknowledged a payload produced after them.
   */
  const val TOMBSTONE_SCOPE: String = "__tombstone_ack"
}
