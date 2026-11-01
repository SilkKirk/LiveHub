package com.livehub.platform.huya

import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.platform.api.AssetReader
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Huya's full category tree.
 *
 * Huya exposes no usable public endpoint for the two-level catalogue, so the app ships
 * the table it renders (`assets/categories/huya_categories.json`, 4 groups and 424
 * sub-categories). The shape is:
 *
 * ```json
 * [ { "title": "网游", "href": "...", "subcategories": [ { "title": "英雄联盟", "id": "1" } ] } ]
 * ```
 *
 * `id` is the `iGid` that `liveHttpUI/getLiveList` expects.
 */
object HuyaCategories {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

  /** Group the home screen opens on when nothing has been chosen yet. */
  const val DEFAULT_GID = "1"

  fun parse(raw: String?): List<Category> {
    if (raw.isNullOrBlank()) return emptyList()
    val root = runCatching { json.parseToJsonElement(raw) as? JsonArray }.getOrNull()
      ?: return emptyList()

    val out = mutableListOf<Category>()
    var order = 0
    root.forEach { groupElement ->
      val group = groupElement as? JsonObject ?: return@forEach
      val groupName = group.str("title") ?: return@forEach
      val subcategories = group["subcategories"] as? JsonArray ?: return@forEach
      subcategories.forEach { subElement ->
        val sub = subElement as? JsonObject ?: return@forEach
        val gid = sub.str("id") ?: return@forEach
        val name = sub.str("title") ?: return@forEach
        out += Category(
          platform = Platform.HUYA,
          id = gid,
          name = name,
          parentId = groupName,
          parentName = groupName,
          sortOrder = order++,
        )
      }
    }
    return out
  }

  /** Loads and parses the shipped table. */
  fun load(assets: AssetReader): List<Category> =
    parse(assets.read(ASSET_NAME))

  private fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

  const val ASSET_NAME = "categories/huya_categories.json"
}
