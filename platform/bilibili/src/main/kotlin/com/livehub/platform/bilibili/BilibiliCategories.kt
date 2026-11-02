package com.livehub.platform.bilibili

import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.platform.api.AssetReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Bilibili's full category tree.
 *
 * Shipped as `assets/categories/bilibili_categories.json`: 12 groups and 456
 * sub-categories, generated from `room/v1/Area/getList`. The shape is:
 *
 * ```json
 * [ { "title": "网游", "subcategories": [ { "title": "英雄联盟", "id": "86", "parent_id": "2" } ] } ]
 * ```
 *
 * `parent_id` and `id` are exactly the `parent_area_id` and `area_id` that
 * `second/getList` takes, so the category id is encoded as `"<parent>:<area>"`.
 */
object BilibiliCategories {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  /** Hot tab: the only combination served by `index/getList` rather than `second/getList`. */
  const val HOT_ID = "0:0"

  fun parse(raw: String?): List<Category> {
    if (raw.isNullOrBlank()) return emptyList()
    val root = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
      ?: return emptyList()

    val out = mutableListOf<Category>()
    var order = 0
    root.forEach { groupElement ->
      val group = groupElement as? JsonObject ?: return@forEach
      val groupName = group.str("title") ?: return@forEach
      val parentAreaId = group["id"]?.jsonPrimitive?.intOrNull
        ?: group.str("id")?.toIntOrNull()
      val subcategories = group["subcategories"] as? JsonArray ?: return@forEach
      subcategories.forEach { subElement ->
        val sub = subElement as? JsonObject ?: return@forEach
        val areaId = sub["id"]?.jsonPrimitive?.intOrNull ?: sub.str("id")?.toIntOrNull()
          ?: return@forEach
        val name = sub.str("title") ?: return@forEach
        val parent = sub.str("parent_id")?.toIntOrNull() ?: parentAreaId ?: return@forEach
        out += Category(
          platform = Platform.BILIBILI,
          id = "$parent:$areaId",
          name = name,
          parentId = parent.toString(),
          parentName = groupName,
          sortOrder = order++,
        )
      }
    }
    return out
  }

  fun load(assets: AssetReader): List<Category> = parse(assets.read(ASSET_NAME))

  /** Splits an encoded id back into `parentAreaId to areaId`. */
  fun idsOf(categoryId: String): Pair<Int, Int>? {
    val parts = categoryId.split(':')
    if (parts.size != 2) return null
    val parent = parts[0].toIntOrNull() ?: return null
    val area = parts[1].toIntOrNull() ?: return null
    return parent to area
  }

  private fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

  const val ASSET_NAME = "categories/bilibili_categories.json"
}
