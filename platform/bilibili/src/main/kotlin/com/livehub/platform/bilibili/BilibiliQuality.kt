package com.livehub.platform.bilibili

import com.livehub.core.model.Platform

/** Bilibili quality naming and ordering rules. */
object BilibiliQuality {
  const val QN_SOURCE = 10000
  const val QN_BLURAY_DOLBY = 30000
  const val QN_BLURAY = 20000
  const val QN_ULTRA_HD = 400
  const val QN_HD = 250
  const val QN_SMOOTH = 150

  /** Human label for a `qn` code. */
  fun labelForQn(qn: Int): String = when (qn) {
    QN_SOURCE -> "原画"
    QN_BLURAY_DOLBY -> "蓝光杜比"
    QN_BLURAY -> "蓝光"
    QN_ULTRA_HD -> "超清"
    QN_HD -> "高清"
    QN_SMOOTH -> "流畅"
    else -> "原画"
  }

  /**
   * Availability rank for a stream URL; lower is better.
   *
   * HLS is preferred over FLV because Media3's HLS module handles CDN failover and
   * discontinuity markers more gracefully.
   */
  fun rankOf(baseUrl: String): Int {
    val lower = baseUrl.lowercase()
    return when {
      lower.contains("index.m3u8") -> 0
      lower.contains(".m3u8") -> 1
      lower.contains(".flv") -> 2
      else -> 9
    }
  }

  /** Options offered by the in-player quality selector. */
  fun options(): List<Pair<Int, String>> = listOf(
    QN_SOURCE to "原画",
    QN_BLURAY to "蓝光",
    QN_ULTRA_HD to "超清",
    QN_HD to "高清",
    QN_SMOOTH to "流畅",
  )

  val platform: Platform get() = Platform.BILIBILI
}