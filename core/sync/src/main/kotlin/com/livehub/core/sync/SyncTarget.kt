package com.livehub.core.sync

/**
 * The persistence side of a synchronisable scope.
 *
 * The engine only ever talks to this interface, so tests can substitute an in-memory
 * implementation and a future scope (danmaku keywords for example) only needs a new
 * [SyncTargets] entry.
 */
interface SyncTarget {
  val scope: SyncScope

  /** Current records, including tombstones. */
  fun snapshot(): List<SyncRecord<out Any>>

  /** Applies a merged record set, returning how many entries actually changed. */
  fun apply(records: List<SyncRecord<out Any>>): Int
}

/** Registry of every synchronisable scope. */
interface SyncTargets {
  fun targetFor(scope: SyncScope): SyncTarget?

  fun available(): List<SyncTarget>

  /** Scopes this device is willing to share. */
  fun enabledScopes(): Set<SyncScope>
}

/** Simple map-backed implementation. */
class MapSyncTargets(
  private val targets: Map<SyncScope, SyncTarget>,
  private val enabled: Set<SyncScope> = targets.keys,
) : SyncTargets {
  override fun targetFor(scope: SyncScope): SyncTarget? = targets[scope]

  override fun available(): List<SyncTarget> = targets.values.toList()

  override fun enabledScopes(): Set<SyncScope> = enabled
}
