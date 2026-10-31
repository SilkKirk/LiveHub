package com.livehub.core.common

/**
 * Converts a raw viewer count into the compact Chinese form used across the UI:
 * `8532` -> `8532`, `12500` -> `1.3万`, `186000000` -> `1.9亿`.
 *
 * The function is deliberately dependency free so it can be unit tested on the JVM
 * and shared by every platform adapter.
 */
object ViewerCountFormatter {
  private const val WAN = 10_000.0
  private const val YI = 100_000_000.0

  fun format(count: Long): String = when {
    count < 0 -> "0"
    count < 10_000 -> count.toString()
    count < 100_000_000 -> trimTrailingZero(count / WAN) + "万"
    else -> trimTrailingZero(count / YI) + "亿"
  }

  /** Parses a raw text like `"1.2万"`, `"12.3w"`, `"1,234"` or `"--"`. */
  fun parse(text: String?): Long {
    val raw = text?.trim().orEmpty()
    if (raw.isEmpty()) return 0L
    val normalized = raw.replace(",", "").replace("，", "")
    val suffixScale = when {
      normalized.endsWith("亿") -> YI
      normalized.endsWith("万") || normalized.endsWith("w") || normalized.endsWith("W") -> WAN
      else -> 1.0
    }
    val numeric = normalized.dropLastWhile { !it.isDigit() && it != '.' }
    return numeric.toDoubleOrNull()?.let { (it * suffixScale).toLong() } ?: 0L
  }

  private fun trimTrailingZero(value: Double): String {
    val rounded = kotlin.math.round(value * 10) / 10
    return if (rounded % 1.0 == 0.0) rounded.toLong().toString() else rounded.toString()
  }
}
