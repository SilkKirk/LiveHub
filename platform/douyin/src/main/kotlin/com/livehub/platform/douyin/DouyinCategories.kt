package com.livehub.platform.douyin

import com.livehub.core.model.Category
import com.livehub.core.model.Platform

/**
 * Douyin live partitions — fallback only.
 *
 * The real catalogue is `assets/categories/douyin_categories.json` (9 groups, 306
 * partitions), parsed by [DouyinCategoryCatalog]. The partition-scoped endpoint
 * `webcast/web/partition/detail/room/v2` serves those partitions correctly once the request
 * carries a valid `a_bogus` signature: measured with one, it returns `status_code = 0` and 15
 * distinct rooms per partition. Without the signature it answers HTTP 444 on `/room/` and an
 * empty 200 on `/room/v2/`, which is what made every tab look empty.
 *
 * This object is the fallback for a build whose asset is missing. A single honestly labelled
 * 推荐 tab beats 306 tabs that cannot resolve, so [all] returns one entry rather than
 * inventing a tree.
 */
object DouyinCategories {

  /** Partition ids the endpoint accepts, used when the bundled table is unavailable. */
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

  /** The one tab shown when the bundled catalogue is unavailable. */
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
