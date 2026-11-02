package com.livehub.platform.douyin

/**
 * Persists the `ttwid` cookie Douyin negotiates.
 *
 * The platform module cannot touch Android storage, so the app supplies a DataStore-backed
 * implementation and everything else falls back to [InMemory]. Only a single value is
 * stored: Douyin's other cookies (`UIFID_TEMP` and friends) are not needed by any endpoint
 * LiveHub calls, and keeping just `ttwid` means there is no jar to go stale in ways that
 * are hard to reason about.
 */
interface DouyinCookieStore {
  /** The stored `Cookie` header value, or empty when nothing has been negotiated yet. */
  fun header(): String

  /** Stores a fresh `Cookie` header value, replacing the previous one. */
  fun save(header: String)

  fun clear()

  companion object {
    /**
     * A fresh in-process store.
     *
     * Deliberately a factory rather than a shared `val`: a single instance would leak one
     * room's negotiated `ttwid` into every other `DouyinApi`, which is how two of these
     * tests failed against each other. The app injects `DataStoreDouyinCookies` anyway, so
     * this exists for tests and for a build without persistence.
     */
    fun inMemory(): DouyinCookieStore = InMemoryDouyinCookieStore()
  }
}

/** Process-local implementation; the Android app substitutes a persistent one. */
class InMemoryDouyinCookieStore : DouyinCookieStore {
  @Volatile private var current: String = ""

  override fun header(): String = current

  override fun save(header: String) {
    if (header.isBlank()) return
    current = header.trim()
  }

  override fun clear() {
    current = ""
  }
}
