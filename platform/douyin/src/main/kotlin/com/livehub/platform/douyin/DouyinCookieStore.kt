package com.livehub.platform.douyin

/** Cookie names that only a logged-in Douyin web session carries. */
private val SESSION_COOKIES = setOf("sessionid", "sessionid_ss", "sid_tt")

/**
 * Merges two `a=1; b=2` cookie headers, the later value winning.
 *
 * Merging, never replacing: `ttwid` arrives from negotiation on first use and a QR login adds
 * `sessionid` and friends later. Replacing dropped whichever half came first, and the failure
 * reads as "just logged in, still asked to log in".
 */
fun mergeDouyinCookies(existing: String, incoming: String): String {
  val merged = LinkedHashMap<String, String>()

  fun absorb(raw: String) {
    raw.split(';').forEach { part ->
      val trimmed = part.trim()
      val index = trimmed.indexOf('=')
      if (trimmed.isNotEmpty() && index > 0) {
        merged[trimmed.substring(0, index)] = trimmed.substring(index + 1)
      }
    }
  }

  absorb(existing)
  absorb(incoming)
  return merged.entries.joinToString("; ") { (name, value) -> "$name=$value" }
}

/** True when [header] carries a logged-in Douyin web session. */
fun hasDouyinSession(header: String): Boolean = header.split(';').any { part ->
  part.substringBefore('=').trim() in SESSION_COOKIES
}

/**
 * Persists Douyin's cookies: the negotiated `ttwid`, plus whatever a QR login adds.
 *
 * The platform module cannot touch Android storage, so the app supplies a DataStore-backed
 * implementation and everything else falls back to [InMemory].
 */
interface DouyinCookieStore {
  /** The stored `Cookie` header value, or empty when nothing has been stored yet. */
  fun header(): String

  /** Merges [header] into the stored value; see [mergeDouyinCookies]. */
  fun save(header: String)

  fun clear()

  /**
   * True when the stored cookies carry a web session.
   *
   * `sessionid` / `sid_tt` are what douyin.com sets after a QR login. `ttwid` alone is the
   * anonymous anti-bot cookie every request carries, so its presence must not read as
   * "logged in" — keyword search is gated on this check.
   */
  fun isLoggedIn(): Boolean = hasDouyinSession(header())

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
    current = mergeDouyinCookies(current, header)
  }

  override fun clear() {
    current = ""
  }
}

