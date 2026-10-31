package com.livehub.core.common

/**
 * Converts a raw viewer count into the compact Chinese form used across the UI:
 * `8532` -> `8532`, `12500` -> `1.3万`, `186000000` -> `1.9亿`.
 *
 * The function is deliberately dependency free so it can be unit tested on the JVM
 * and shared by every platform adapter.
 *
 * Rounding is done with **integer arithmetic**. `kotlin.math.round` uses
 * round-half-to-even, so `12.5 -> 12` and `1.25万` would render as `1.2万`, which looks
 * wrong to users. `String.format` is also avoided because it would emit `1,3` under
 * locales that use a comma as the decimal separator.
 */
object ViewerCountFormatter {
  private const val WAN = 10_000L
  private const val YI = 100_000_000L

  fun format(count: Long): String = when {
    count < 0 -> "0"
    count < WAN -> count.toString()
    count < YI -> scaledTenths(count, WAN) + "万"
    else -> scaledTenths(count, YI) + "亿"
  }

  /** Parses a raw text like `"1.2万"`, `"12.3w"`, `"1,234"` or `"--"`. */
  fun parse(text: String?): Long {
    val raw = text?.trim().orEmpty()
    if (raw.isEmpty()) return 0L
    val normalized = raw.replace(",", "").replace("，", "")
    val suffixScale = when {
      normalized.endsWith("亿") -> YI.toDouble()
      normalized.endsWith("万") || normalized.endsWith("w") || normalized.endsWith("W") -> WAN.toDouble()
      else -> 1.0
    }
    val numeric = normalized.dropLastWhile { !it.isDigit() && it != '.' }
    return numeric.toDoubleOrNull()?.let { (it * suffixScale).toLong() } ?: 0L
  }

  /**
   * Formats `count / unit` with exactly one decimal, rounding halves up.
   *
   * `count` is always below 1e8 here (or divided by 1e8 for the 亿 branch), so the
   * `* 10` below cannot overflow a [Long].
   */
  private fun scaledTenths(count: Long, unit: Long): String {
    val tenths = (count * 10 + unit / 2) / unit
    val whole = tenths / 10
    val fraction = tenths % 10
    return if (fraction == 0L) whole.toString() else "$whole.$fraction"
  }
}
