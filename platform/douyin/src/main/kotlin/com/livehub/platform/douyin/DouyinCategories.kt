package com.livehub.platform.douyin

import com.livehub.core.model.Category
import com.livehub.core.model.Platform

/**
 * Douyin live partitions.
 *
 * Douyin exposes a flat partition list; the ids below are the `partition` values the
 * web client sends to `webcast/web/partition/detail/room/`.
 */
object DouyinCategories {
  private val entries = listOf(
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

  fun all(): List<Category> = entries.mapIndexed { index, (id, name) ->
    Category(
      platform = Platform.DOUYIN,
      id = id,
      name = name,
      parentId = "root",
      parentName = "抖音",
      sortOrder = index,
    )
  }

  const val DEFAULT_ID = "720"
}
