package com.livehub.platform.huya

import com.livehub.core.model.Category
import com.livehub.core.model.Platform

/**
 * Curated Huya `iGid` catalogue.
 *
 * Huya does not publish a stable JSON category endpoint, so the ids are pinned here.
 * They change rarely; when they do, [HuyaApi.categories] still returns a usable list
 * because unknown ids simply produce empty room pages.
 */
object HuyaCategories {
  private data class Gid(val id: String, val name: String, val parent: String)

  private val entries = listOf(
    Gid("1", "网游", "热门"),
    Gid("2", "单机游戏", "热门"),
    Gid("3", "手游休闲", "热门"),
    Gid("8", "聊天", "娱乐"),
    Gid("6", "王者荣耀", "手游休闲"),
    Gid("1", "英雄联盟", "网游"),
    Gid("2793", "和平精英", "手游休闲"),
    Gid("66056", "英雄联盟手游", "手游休闲"),
    Gid("862", "原创", "娱乐"),
    Gid("978", "语音交友", "娱乐"),
    Gid("520", "户外", "娱乐"),
    Gid("532", "一起看", "娱乐"),
    Gid("21277", "地下城与勇士", "网游"),
    Gid("4", "绝地求生", "网游"),
    Gid("3171", "穿越火线", "网游"),
    Gid("100023", "无畏契约", "网游"),
    Gid("9", "娱乐天地", "娱乐"),
    Gid("1663", "电台", "娱乐"),
    Gid("1312", "交友", "娱乐"),
    Gid("26607", "棋牌桌游", "娱乐"),
  )

  /** Returns the curated list with the primary sections first. */
  fun all(): List<Category> = entries
    .distinctBy { it.id }
    .mapIndexed { index, gid ->
      Category(
        platform = Platform.HUYA,
        id = gid.id,
        name = gid.name,
        parentId = gid.parent,
        parentName = gid.parent,
        sortOrder = index,
      )
    }

  /** Category id used when the UI opens the Huya tab for the first time. */
  const val DEFAULT_GID = "1"
}
