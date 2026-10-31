package com.livehub.platform.bilibili

import com.livehub.core.model.Category
import com.livehub.core.model.Platform

/**
 * Bilibili live section catalogue as `parentAreaId:areaId` pairs.
 *
 * The ids are stable and documented by the platform; names are the display labels the
 * web client uses. Grouping into parents drives the two-level selector.
 */
object BilibiliCategories {

  private data class Parent(val id: String, val name: String, val children: List<Pair<String, String>>)

  private val tree = listOf(
    Parent(
      id = "0",
      name = "热门",
      children = listOf("0" to "推荐"),
    ),
    Parent(
      id = "1",
      name = "娱乐",
      children = listOf(
        "21" to "视频唱见",
        "177" to "视频聊天",
        "199" to "语音厅",
        "207" to "虚拟主播",
        "587" to "舞见",
        "935" to "唱见",
        "123" to "户外",
        "77" to "交友",
        "371" to "电台",
      ),
    ),
    Parent(
      id = "2",
      name = "网游",
      children = listOf(
        "86" to "英雄联盟",
        "27" to "王者荣耀",
        "491" to "无畏契约",
        "129" to "绝地求生",
        "3" to "穿越火线",
        "209" to "CS2",
        "108" to "DOTA2",
        "5" to "魔兽世界",
        "30" to "DNF",
        "113" to "剑网3",
        "107" to "主机游戏",
        "339" to "永劫无间",
        "417" to "Apex英雄",
      ),
    ),
    Parent(
      id = "3",
      name = "手游",
      children = listOf(
        "376" to "和平精英",
        "658" to "原神",
        "808" to "崩坏：星穹铁道",
        "213" to "英雄联盟手游",
        "116" to "王者荣耀体验服",
        "885" to "命运方舟",
      ),
    ),
    Parent(
      id = "4",
      name = "单机",
      children = listOf(
        "222" to "单机游戏",
        "228" to "我的世界",
        "370" to "艾尔登法环",
      ),
    ),
  )

  fun all(): List<Category> {
    var order = 0
    return tree.flatMap { parent ->
      parent.children.map { (areaId, name) ->
        Category(
          platform = Platform.BILIBILI,
          id = BilibiliArea(parent.id, areaId).encoded,
          name = name,
          parentId = parent.id,
          parentName = parent.name,
          sortOrder = order++,
        )
      }
    }
  }

  val default: BilibiliArea get() = BilibiliArea.HOT
}