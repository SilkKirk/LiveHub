package com.livehub.core.common

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A tiny single-flight / TTL cache for values produced by suspending providers.
 *
 * Concurrent callers requesting the same key share one in-flight computation, so
 * the app never issues duplicate network requests for the same live list page or
 * room profile. Entries expire after [ttlMillis]; a [ttlMillis] of [NEVER] keeps
 * a value until it is explicitly invalidated.
 *
 * ## Bounded, because nothing else bounds it
 *
 * A TTL alone does not bound this cache: [peek] is the only place that removes an expired entry,
 * so a key that is written and never asked for again stays resident forever. That was harmless
 * for the two lists it started with (categories are a handful of keys), but `roomDetail` is keyed
 * per room — browsing all evening leaves one entry per room ever opened.
 *
 * The cap is on [maxEntries] with least-recently-used eviction. It is deliberately generous: the
 * point is to make the curve flat, not to shrink the working set. Evicting an entry only costs one
 * extra request, so being too eager is safe; being unbounded is not.
 *
 * [inFlight] is cleaned up unconditionally in a `finally`, which is the other half of the same
 * leak: it used to gain one `Mutex` per distinct key and never lose any, including for keys whose
 * producer then failed and which therefore never reached [entries] at all.
 */
class TtlCache<K : Any, V : Any>(
  private val ttlMillis: Long,
  private val clock: () -> Long = System::currentTimeMillis,
  /**
   * Upper bound on resident entries.
   *
   * The default is sized for "a few hundred rooms plus the platform lists" — several times more
   * than any single session needs, while still capping a process that browses for hours.
   */
  private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {
  private data class Entry<V>(val value: V, val storedAt: Long)

  init {
    require(maxEntries > 0) { "maxEntries must be positive, was $maxEntries" }
  }

  private val mutex = Mutex()

  /**
   * Insertion-ordered, and re-inserted on every hit, so iteration order is recency order.
   *
   * That is what makes [trim]'s "drop from the front" an LRU policy without a second structure.
   */
  private val entries = LinkedHashMap<K, Entry<V>>()

  /** One in-flight computation per key, plus how many callers are waiting on it. */
  private class Flight {
    val mutex = Mutex()
    var users: Int = 0
  }

  private val inFlight = mutableMapOf<K, Flight>()

  fun now(): Long = clock()

  /** Reads a cached value without computing it. */
  suspend fun peek(key: K): V? = mutex.withLock {
    val entry = entries[key] ?: return@withLock null
    if (isExpired(entry)) {
      entries.remove(key)
      return@withLock null
    }
    // Re-insert to move the key to the tail: `LinkedHashMap` keeps insertion order, so touching an
    // entry has to be a remove-then-put to count as a use.
    entries.remove(key)
    entries[key] = entry
    entry.value
  }

  /**
   * Returns the cached value for [key] or computes it with [producer].
   *
   * When several callers race, exactly one producer runs; the others await it and
   * then read the freshly stored value.
   */
  suspend fun getOrPut(key: K, producer: suspend () -> V): V {
    peek(key)?.let { return it }

    // Registered and reference-counted under the cache lock, so a caller that arrives while the
    // producer runs joins the same flight instead of starting a second one.
    val flight = mutex.withLock {
      val existing = inFlight.getOrPut(key) { Flight() }
      existing.users++
      existing
    }
    try {
      return flight.mutex.withLock {
        // Re-checked under the flight's own lock: the previous holder may have stored a value
        // between the `peek` above and this acquisition.
        peek(key)?.let { return@withLock it }
        val value = producer()
        store(key, value)
        value
      }
    } finally {
      // Released only when the last waiter leaves. Dropping the entry on the first exit would let a
      // caller that arrived late create a *new* flight and run a second producer alongside the one
      // still working — the duplicate request this class exists to prevent.
      //
      // Removing at all is the other half of the fix: the map used to gain one entry per distinct
      // key and never lose any, and a producer that threw never reached `entries`, so the TTL never
      // aged that key out either.
      mutex.withLock {
        flight.users--
        if (flight.users == 0) inFlight.remove(key)
      }
    }
  }

  suspend fun put(key: K, value: V) {
    store(key, value)
  }

  suspend fun invalidate(key: K) {
    mutex.withLock { entries.remove(key) }
  }

  suspend fun invalidateAll() {
    mutex.withLock { entries.clear() }
  }

  suspend fun size(): Int = mutex.withLock { entries.size }

  /** Writes one entry and evicts the least recently used ones over [maxEntries]. */
  private suspend fun store(key: K, value: V) = mutex.withLock {
    entries.remove(key)
    entries[key] = Entry(value, clock())
    trim()
  }

  /** Drops least-recently-used entries until the cache is back inside its bound. */
  private fun trim() {
    if (entries.size <= maxEntries) return
    val iterator = entries.keys.iterator()
    while (entries.size > maxEntries && iterator.hasNext()) {
      iterator.next()
      iterator.remove()
    }
  }

  private fun isExpired(entry: Entry<V>): Boolean =
    ttlMillis != NEVER && clock() - entry.storedAt >= ttlMillis

  companion object {
    /** Sentinel TTL meaning "never expire". */
    const val NEVER: Long = -1L

    /**
     * Default entry cap.
     *
     * Large enough to hold every category list plus far more room details than one session opens;
     * small enough that the map cannot grow with usage. Eviction costs one network request, so the
     * exact number does not need tuning per call site.
     */
    const val DEFAULT_MAX_ENTRIES: Int = 256
  }
}
