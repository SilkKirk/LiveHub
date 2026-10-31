package com.livehub.platform.douyu

import com.livehub.core.model.Platform
import com.livehub.core.model.StreamVariant

/** Quality selection and CDN preference rules for Douyu. */
object DouyuQuality {

  /** The rate/CDN combination chosen for playback. */
  data class Selection(
    val rate: Int,
    val rateLabel: String,
    val cdn: String,
  )

  /**
   * Picks the requested rate, falling back to the highest available.
   *
   * Douyu encodes `rate == 0` as 原画 (source quality). When the caller asks for a
   * label rather than a code we canonicalise common aliases first.
   */
  fun select(response: DyPlayResponse, preferred: Int?): Selection {
    val rates = response.rates
    val cdn = selectCdn(response.cdns, preferredCdn = null)
    if (rates.isEmpty()) return Selection(rate = -1, rateLabel = "原画", cdn = cdn)

    val chosen = when (preferred) {
      null -> rates.firstOrNull { it.rate == 0 } ?: rates.maxByOrNull { it.rate } ?: rates.first()
      else -> rates.firstOrNull { it.rate == preferred }
        ?: rates.firstOrNull { it.rate == 0 }
        ?: rates.maxByOrNull { it.rate }
        ?: rates.first()
    }
    return Selection(rate = chosen.rate, rateLabel = chosen.name.ifBlank { labelFor(chosen.rate) }, cdn = cdn)
  }

  /** Canonicalises a user visible quality label into a Douyu rate code. */
  fun resolveRate(label: String, rates: List<DouyuRate>): Int? {
    val trimmed = label.trim()
    if (trimmed.isEmpty()) return null
    trimmed.toIntOrNull()?.let { numeric ->
      return if (rates.any { it.rate == numeric }) numeric else numeric
    }
    rates.firstOrNull { it.name.equals(trimmed, ignoreCase = true) }?.let { return it.rate }

    val canonical = canonicalise(trimmed)
    rates.firstOrNull { canonicalise(it.name) == canonical }?.let { return it.rate }

    return when (canonical) {
      "原画" -> rates.filter { it.rate == 0 }.minByOrNull { it.rate }?.rate
        ?: rates.filter { it.name.contains("原画") || it.name.contains("蓝光") }
          .minByOrNull { it.rate }?.rate
      "高清" -> rates.firstOrNull { it.name.contains("高清") }?.rate
        ?: rates.firstOrNull { it.rate == 2 }?.rate
        ?: rates.firstOrNull { it.rate == 4 }?.rate
        ?: rates.filter { it.name.contains("超清") }.maxByOrNull { it.rate }?.rate
        ?: rates.maxByOrNull { it.rate }?.rate
      "标清" -> rates.firstOrNull { it.name.contains("标清") }?.rate
        ?: rates.firstOrNull { it.name.contains("流畅") }?.rate
        ?: rates.firstOrNull { it.rate == 1 }?.rate
        ?: rates.minByOrNull { it.bitRate }?.rate
      else -> null
    }
  }

  /**
   * Prefers non-`scdn` CDNs, then alphabetical order.
   *
   * `scdn` hosts are the ones most likely to be geo-restricted, so they are tried last.
   */
  fun selectCdn(cdns: List<String>, preferredCdn: String?): String {
    if (cdns.isEmpty()) return DouyuParser.DEFAULT_DOUYU_CDN
    preferredCdn?.let { wanted ->
      cdns.firstOrNull { it.equals(wanted, ignoreCase = true) }?.let { return it }
    }
    return cdns.sortedWith(
      compareBy({ it.startsWith("scdn") }, { it }),
    ).first()
  }

  /** Builds the variant list offered by the quality selector. */
  fun variants(response: DyPlayResponse, selection: Selection): List<StreamVariant> {
    val cdn = selection.cdn
    val primary = StreamVariant(
      qualityLabel = selection.rateLabel,
      url = response.urlFor(cdn),
      lineName = cdn,
      isHls = false,
      qualityRank = rankOf(selection.rate),
      qualityCode = selection.rate,
    )
    val alternates = response.cdns
      .filterNot { it.equals(cdn, ignoreCase = true) }
      .sortedWith(compareBy({ it.startsWith("scdn") }, { it }))
      .map { alternateCdn ->
        StreamVariant(
          qualityLabel = selection.rateLabel,
          url = response.urlFor(alternateCdn),
          lineName = alternateCdn,
          isHls = false,
          qualityRank = rankOf(selection.rate),
          qualityCode = selection.rate,
        )
      }
    return (listOf(primary) + alternates).distinctBy { it.url }
  }

  /** Douyu `rate == 0` is source quality, so it must sort first. */
  fun rankOf(rate: Int): Int = if (rate <= 0) 0 else rate

  fun labelFor(rate: Int): String = when (rate) {
    0 -> "原画"
    1 -> "标清"
    2 -> "高清"
    4 -> "超清"
    else -> "画质$rate"
  }

  private fun canonicalise(value: String): String = when {
    value.contains("原") || value.contains("origin", ignoreCase = true) -> "原画"
    value.contains("高") || value.contains("high", ignoreCase = true) -> "高清"
    value.contains("标") || value.contains("standard", ignoreCase = true) -> "标清"
    else -> value
  }

  val platform: Platform get() = Platform.DOUYU
}
