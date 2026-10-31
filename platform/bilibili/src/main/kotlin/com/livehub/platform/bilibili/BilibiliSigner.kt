package com.livehub.platform.bilibili

import com.livehub.core.network.Crypto

/**
 * Bilibili request signing.
 *
 * Two different schemes are in use:
 *
 * * The **web-interface `getList`** endpoint signs a *fixed order* query with a public
 *   salt: `w_rid = md5(join("&", "k=v" in declared order) + SALT)`.
 * * Everything newer uses **WBI**: parameters are sorted by name, encoded with the
 *   WBI-safe alphabet, and hashed together with a mixin key derived from the daily
 *   `img_key`/`sub_key` pair.
 */
object BilibiliSigner {

  /** Public salt used by `xlive/web-interface/v1/second/getList`. */
  const val LIST_SALT = "ea1db124af3c7062474693fa704f4ff8"

  /** Permutation applied to `imgKey + subKey` to build the 32 char mixin key. */
  private val MIXIN_KEY_ENC_TAB = intArrayOf(
    46, 47, 18, 2, 53, 8, 23, 32, 15, 50, 10, 31, 58, 3, 45, 35,
    27, 43, 5, 49, 33, 9, 42, 19, 29, 28, 14, 39, 12, 38, 41, 13,
    37, 48, 7, 16, 24, 55, 40, 61, 26, 17, 0, 1, 60, 51, 30, 4,
    22, 25, 54, 21, 56, 59, 6, 63, 57, 62, 11, 36, 20, 34, 44, 52,
  )

  /**
   * Signs the room-list query.
   *
   * @param orderedParams the parameters **in the exact order they appear in the URL**,
   *   excluding `wts` and `w_rid`; the signer appends `wts` before hashing.
   */
  fun signListQuery(orderedParams: List<Pair<String, String>>, wts: Long): SignedQuery {
    val canonical = buildString {
      orderedParams.forEach { (key, value) ->
        append(key).append('=').append(value).append('&')
      }
      append("wts=").append(wts)
    }
    val wRid = Crypto.md5Hex(canonical + LIST_SALT)
    return SignedQuery(canonical = canonical, wts = wts, wRid = wRid)
  }

  /** Result of [signListQuery]; [query] already includes `wts` and `w_rid`. */
  data class SignedQuery(val canonical: String, val wts: Long, val wRid: String) {
    val query: String get() = "$canonical&w_rid=$wRid"
  }

  /** Derives the 32 character WBI mixin key from the nav response keys. */
  fun mixinKey(imgKey: String, subKey: String): String {
    val raw = (imgKey + subKey).toByteArray(Charsets.UTF_8)
    val sb = StringBuilder(32)
    repeat(32) { index ->
      val source = MIXIN_KEY_ENC_TAB[index]
      sb.append(if (source < raw.size) raw[source].toInt().toChar() else '\u0000')
    }
    return sb.toString()
  }

  /** Extracts the key part of a `https://.../xxx.png` WBI URL. */
  fun keyFromUrl(url: String): String {
    if (url.isBlank()) return ""
    val file = url.substringAfterLast('/')
    return if (file.contains('.')) file.substringBeforeLast('.') else file
  }

  /**
   * Signs arbitrary WBI parameters: keys are sorted, values WBI-encoded and the whole
   * query is hashed with the mixin key.
   */
  fun signWbi(params: Map<String, String>, mixinKey: String, wts: Long): WbiSigned {
    val sorted = params.entries.sortedBy { it.key }
    val canonical = buildString {
      sorted.forEach { (key, value) ->
        append(Crypto.wbiEncode(key)).append('=').append(Crypto.wbiEncode(value)).append('&')
      }
      append("wts=").append(wts)
    }
    val wRid = Crypto.md5Hex(canonical + mixinKey)
    return WbiSigned(canonical = canonical, wts = wts, wRid = wRid)
  }

  data class WbiSigned(val canonical: String, val wts: Long, val wRid: String) {
    val query: String get() = "$canonical&w_rid=$wRid"
  }

  /** Escape hatch used by tests to assert the table is applied verbatim. */
  internal fun mixinKeyTable(): IntArray = MIXIN_KEY_ENC_TAB.copyOf()
}
