package com.livehub.core.network

import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/** Hashing and encoding helpers shared by the platform signing algorithms. */
object Crypto {
  private val HEX = "0123456789abcdef".toCharArray()

  fun md5Hex(input: String): String = md5Hex(input.toByteArray(Charsets.UTF_8))

  fun md5Hex(input: ByteArray): String =
    toHex(MessageDigest.getInstance("MD5").digest(input))

  fun sha1Hex(input: String): String =
    toHex(MessageDigest.getInstance("SHA-1").digest(input.toByteArray(Charsets.UTF_8)))

  fun sha256Hex(input: String): String =
    toHex(MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)))

  fun hmacSha256Hex(key: String, data: String): String {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(SecretKeySpec(key.toByteArray(Charsets.UTF_8), "HmacSHA256"))
    return toHex(mac.doFinal(data.toByteArray(Charsets.UTF_8)))
  }

  fun toHex(bytes: ByteArray): String {
    val out = CharArray(bytes.size * 2)
    bytes.forEachIndexed { index, byte ->
      val v = byte.toInt() and 0xFF
      out[index * 2] = HEX[v ushr 4]
      out[index * 2 + 1] = HEX[v and 0x0F]
    }
    return String(out)
  }

  fun base64Decode(value: String): ByteArray = java.util.Base64.getDecoder().decode(value)

  fun base64Encode(bytes: ByteArray): String = java.util.Base64.getEncoder().encodeToString(bytes)

  /**
   * Percent-decodes once, keeping an invalid escape verbatim instead of throwing.
   *
   * The previous shape was a bare `URLDecoder.decode`, which throws `IllegalArgumentException` for a
   * `%` not followed by two hex digits — and the value comes from a query string a platform built,
   * so a stray `%` in a stream name is not hypothetical. That exception is not a `PlatformException`,
   * so nothing classified it: it travelled out of `HuyaSigner`, past the retry policy (which treats
   * an unknown exception as final) and into `MainViewModel`, where `error.message` is rendered as-is
   * — putting a JDK sentence like "Illegal hex characters in escape (%) pattern" on a Chinese
   * screen. Keeping the segment unchanged degrades the signature to "wrong" instead of
   * "unexplainable", and it is exactly what the reference implementation does
   * (`HuyaStreamUrlResolverAndroid.urlDecodeOnce`).
   */
  fun urlDecodeOnce(value: String): String =
    runCatching { java.net.URLDecoder.decode(value, "UTF-8") }.getOrDefault(value)

  /**
   * Encodes a query component the way the Bilibili WBI signature expects:
   * `A-Za-z0-9-_.~` survive, `!'()*` are dropped, everything else is `%XX`.
   */
  fun wbiEncode(value: String): String {
    val sb = StringBuilder(value.length)
    value.toByteArray(Charsets.UTF_8).forEach { b ->
      val v = b.toInt() and 0xFF
      val c = v.toChar()
      when {
        c in 'A'..'Z' || c in 'a'..'z' || c in '0'..'9' || c == '-' || c == '_' || c == '.' || c == '~' ->
          sb.append(c)
        c == '!' || c == '\'' || c == '(' || c == ')' || c == '*' -> Unit
        else -> sb.append('%').append(UPPER_HEX[v ushr 4]).append(UPPER_HEX[v and 0x0F])
      }
    }
    return sb.toString()
  }

  /** Percent-encoding must be uppercase; `md5Hex` output stays lowercase. */
  private const val UPPER_HEX = "0123456789ABCDEF"
}
