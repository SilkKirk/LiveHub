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
 */
class TtlCache<K : Any, V : Any>(
  private val ttlMillis: Long,
  private val clock: () -> Long = System::currentTimeMillis,
) {
  private data class Entry<V>(val value: V, val storedAt: Long)

  private val mutex = Mutex()
  private val entries = LinkedHashMap<K, Entry<V>>()
  private val inFlight = mutableMapOf<K, Mutex>()

  fun now(): Long = clock()

  /** Reads a cached value without computing it. */
  suspend fun peek(key: K): V? = mutex.withLock {
    val entry = entries[key] ?: return@withLock null
    if (isExpired(entry)) {
      entries.remove(key)
      null
    } else {
      entry.value
    }
  }

  /**
   * Returns the cached value for [key] or computes it with [producer].
   *
   * When several callers race, exactly one producer runs; the others await it and
   * then read the freshly stored value.
   */
  suspend fun getOrPut(key: K, producer: suspend () -> V): V {
    peek(key)?.let { return it }

    val keyMutex = mutex.withLock { inFlight.getOrPut(key) { Mutex() } }
    return keyMutex.withLock {
      peek(key)?.let { return@withLock it }
      val value = producer()
      mutex.withLock { entries[key] = Entry(value, clock()) }
      value
    }
  }

  suspend fun put(key: K, value: V) {
    mutex.withLock { entries[key] = Entry(value, clock()) }
  }

  suspend fun invalidate(key: K) {
    mutex.withLock { entries.remove(key) }
  }

  suspend fun invalidateAll() {
    mutex.withLock { entries.clear() }
  }

  suspend fun size(): Int = mutex.withLock { entries.size }

  private fun isExpired(entry: Entry<V>): Boolean =
    ttlMillis != NEVER && clock() - entry.storedAt >= ttlMillis

  companion object {
    /** Sentinel TTL meaning "never expire". */
    const val NEVER: Long = -1L
  }
}
