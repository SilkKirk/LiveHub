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

  /**
   * Runs [block] with this target's own mutation lock held.
   *
   * A merge is `snapshot()` → compute → `apply()`, and the three only mean anything together: a
   * local mutation that lands between the read and the write is computed away, because `apply`
   * stores the whole merged list that was derived from the *older* snapshot. Making `apply` atomic
   * with respect to itself is not enough — the base it merges against has to be stable too.
   *
   * The engine therefore holds this lock for the whole read-merge-write. Implementations that
   * persist through a store with its own lock override this to enter it; the default is a no-op so
   * an in-memory target (every test fake) needs no change.
   *
   * **Must not** be used for anything that calls back into [SyncEngine]: `applyPayload` already
   * holds it, and the lock is not reentrant.
   */
  fun <R> locked(block: () -> R): R = block()
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
