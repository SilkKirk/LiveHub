package com.livehub.platform.douyin

import com.livehub.core.model.Category
import com.livehub.core.model.Platform

/**
 * Douyin live partitions.
 *
 * Douyin's partition-scoped list endpoint now answers HTTP 444 to anonymous clients, and
 * the `webcast/feed` endpoint that still works ignores every partition parameter. LiveHub
 * therefore exposes a **single** category instead of a list of tabs that would all show
 * the same rooms: a filter that silently does nothing is worse than no filter.
 *
 * The partition names are kept in [KNOWN_PARTITIONS] so the mapping can be restored the
 * moment a working partition endpoint is found.
 */
object DouyinCategories {

  /** Partition ids accepted by the (currently unreachable) partition endpoint. */
  val KNOWN_PARTITIONS: List<Pair<String, String>> = listOf(
    "720" to "热门",
    "1" to "游戏",
    "2" to "娱乐",
    "3" to "知识",
    "4" to "户外",
    "5" to "音乐",
    "6" to "舞蹈",
    "7" to "美食",
    "8" to "体育",
    "9" to "二次元",
    "10" to "生活",
    "11" to "三农",
    "12" to "汽车",
    "13" to "购物",
    "14" to "旅行",
  )

  /**
   * What the UI shows. One tab labelled honestly: the feed is a recommendation stream,
   * not a browsable catalogue.
   */
  fun all(): List<Category> = listOf(
    Category(
      platform = Platform.DOUYIN,
      id = DEFAULT_ID,
      name = "推荐",
      parentId = "root",
      parentName = "抖音",
      sortOrder = 0,
    ),
  )

  const val DEFAULT_ID = "720"
}
