package com.livehub.platform.bilibili

/**
 * Persistent Bilibili session cookies.
 *
 * The platform issues a different cookie set per device, and the search / playurl /
 * danmaku endpoints all behave differently for anonymous callers. The store keeps a
 * single `k=v; k=v` header string so it can be persisted by any key-value backend.
 */
interface BilibiliCookieStore {
  fun header(): String

  /** Merges [rawHeader] into the stored cookies, preserving unrelated keys. */
  fun save(rawHeader: String)

  fun clear()

  /** True when a login cookie (`SESSDATA`) is present. */
  fun isLoggedIn(): Boolean = header().contains("SESSDATA=")

  companion object {
    /** A no-op store used in tests and when persistence is unavailable. */
    val InMemory: BilibiliCookieStore = InMemoryCookieStore()
  }
}

/** Process-local implementation; the Android app substitutes a DataStore backed one. */
class InMemoryCookieStore : BilibiliCookieStore {
  @Volatile private var current: String = ""

  override fun header(): String = current

  override fun save(rawHeader: String) {
    current = merge(current, rawHeader)
  }

  override fun clear() {
    current = ""
  }

  private fun merge(existing: String, incoming: String): String {
    if (incoming.isBlank()) return existing
    val map = LinkedHashMap<String, String>()
    parse(existing).forEach { (k, v) -> map[k] = v }
    parse(incoming).forEach { (k, v) -> map[k] = v }
    return map.entries.joinToString("; ") { "${it.key}=${it.value}" }
  }

  private fun parse(raw: String): List<Pair<String, String>> = raw
    .split(';')
    .mapNotNull { part ->
      val trimmed = part.trim()
      if (trimmed.isEmpty()) return@mapNotNull null
      val index = trimmed.indexOf('=')
      if (index <= 0) return@mapNotNull null
      trimmed.substring(0, index) to trimmed.substring(index + 1)
    }
}