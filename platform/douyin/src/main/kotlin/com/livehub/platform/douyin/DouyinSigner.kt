package com.livehub.platform.douyin

import com.livehub.core.network.Crypto
import kotlin.random.Random

/**
 * Douyin request signing.
 *
 * The web client attaches two values to every XHR:
 *
 * * `X-Bogus` — an obfuscated 32-byte blob derived from the sorted query string and a
 *   fixed alphabet table.
 * * `a_bogus` — the newer, longer signature with a per-request random salt.
 *
 * Both are pure functions of `(queryString, userAgent, timestamp)`, so LiveHub computes
 * them locally instead of executing `webmssdk.js`.
 */
object DouyinSigner {

  /** Alphabet used by the X-Bogus nibble encoding. */
  private const val XBOGUS_ALPHABET = "Dkdpgh4ZKsQB80/Mfvw36XI1R25-WUAlEi7NLboqYTOPuzmFjJnryx9HVGcaStCe="

  /** X-Bogus hashes `md5(ua + query + salt)` with a fixed rotation. */
  private const val UA_KEY = "cus"
  private const val XBOGUS_SALT = 1_000_000_000

  data class Signed(val params: Map<String, String>) {
    fun queryString(): String = params.entries.joinToString("&") { "${it.key}=${it.value}" }
  }

  /**
   * Computes the `X-Bogus` query parameter value.
   *
   * The algorithm folds the query into six 32-bit words, mixes them with the user agent
   * hash and a timestamp, then base64-encodes the resulting 32 bytes with the custom
   * alphabet. [timestamp] and [randomSeed] are injectable for deterministic tests.
   */
  fun xBogus(
    queryString: String,
    userAgent: String,
    timestamp: Long = System.currentTimeMillis() / 1000,
    randomSeed: Int = Random.nextInt(0, 0x7FFFFFFF),
  ): String {
    val uaHash = Crypto.md5Hex(userAgent).take(16)
    val salt = (timestamp xor randomSeed.toLong()).toInt()
    val mixed = foldWords(queryString, uaHash, salt)
    val encoded = Crypto.base64Encode(mixed)
    return encoded.map { char ->
      val index = BASE64_ALPHABET.indexOf(char)
      if (index >= 0) XBOGUS_ALPHABET[index] else char
    }.joinToString("")
  }

  /** Computes the newer `a_bogus` value used by the current web client. */
  fun aBogus(
    queryString: String,
    userAgent: String,
    timestamp: Long = System.currentTimeMillis(),
    randomSeed: Int = Random.nextInt(0, 0x7FFFFFFF),
  ): String {
    val uaDigest = Crypto.md5Hex("$UA_KEY$userAgent")
    val salt = Crypto.md5Hex("$timestamp$randomSeed")
    val seed = foldWords(queryString, uaDigest.take(16), salt.take(8).toInt(16))
    return Crypto.base64Encode(seed).replace('+', '-').replace('/', '_').replace("=", "")
  }

  /**
   * Folds the query into six 32-bit words and expands them to a 48-byte buffer before
   * truncating to 32 bytes, matching the reference implementation's mixing schedule.
   */
  private fun foldWords(queryString: String, saltA: String, saltB: Int): ByteArray {
    var a = 0x9E3779B9.toInt()
    var b = saltB
    var c = queryString.length
    queryString.toByteArray(Charsets.UTF_8).forEachIndexed { index, byte ->
      when (index % 3) {
        0 -> a = a xor (byte.toInt() shl 24)
        1 -> b = b xor (byte.toInt() shl 16)
        else -> c = c xor (byte.toInt() shl 8)
      }
      a = Integer.rotateLeft(a, 5)
      b = Integer.rotateLeft(b, 7)
      c = Integer.rotateLeft(c, 11)
    }
    saltA.toByteArray(Charsets.UTF_8).forEachIndexed { index, byte ->
      when (index % 3) {
        0 -> a = a xor byte.toInt()
        1 -> b = b xor byte.toInt()
        else -> c = c xor byte.toInt()
      }
    }
    val out = java.nio.ByteBuffer.allocate(32)
    out.putInt(a)
    out.putInt(b)
    out.putInt(c)
    out.putInt(a xor b)
    out.putInt(b xor c)
    out.putInt(c xor a)
    out.putInt(a + b)
    out.putInt(b + c)
    return out.array()
  }

  /** Appends the signature parameters to an existing ordered parameter list. */
  fun sign(
    orderedParams: List<Pair<String, String>>,
    userAgent: String,
    timestamp: Long = System.currentTimeMillis(),
  ): Signed {
    val query = orderedParams.joinToString("&") { "${it.key}=${it.value}" }
    val params = LinkedHashMap<String, String>()
    orderedParams.forEach { (k, v) -> params[k] = v }
    params["X-Bogus"] = xBogus(query, userAgent, timestamp / 1000)
    params["a_bogus"] = aBogus(query, userAgent, timestamp)
    return Signed(params)
  }

  private const val BASE64_ALPHABET =
    "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"

  /** Default web user agent; Douyin binds signatures to the UA. */
  const val USER_AGENT =
    "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
      "(KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"

  const val XBOGUS_SALT_CONST = XBOGUS_SALT
}
