package com.livehub.platform.douyin

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
 * Douyin's full partition tree.
 *
 * Shipped as `assets/categories/douyin_categories.json`: 9 groups and 306 partitions.
 * Each entry's `href` looks like `/category/1_2_1_1010023`, whose **last two segments**
 * are the `partitionType` and `partition` that the partition-scoped list endpoint
 * expects. All 306 hrefs follow that shape (verified), so parsing is safe.
 */
object DouyinCategoryCatalog {

  private val json = Json { ignoreUnknownKeys = true; isLenient = true }

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
        val name = sub.str("title") ?: return@forEach
        val href = sub.str("href") ?: return@forEach
        val parsed = partitionOf(href) ?: return@forEach
        out += Category(
          platform = Platform.DOUYIN,
          // The list endpoint needs both values, so they travel together in the id.
          id = "${parsed.first}:${parsed.second}",
          name = name,
          parentId = groupName,
          parentName = groupName,
          sortOrder = order++,
        )
      }
    }
    return out
  }

  /**
   * `partitionType to partition` from a `/category/1_2_1_1010023` href.
   *
   * The trailing pair is what the request needs; the leading segments describe the
   * browse hierarchy and are not sent.
   */
  fun partitionOf(href: String): Pair<String, String>? {
    val parts = href.split('_')
    if (parts.size < 2) return null
    val partition = parts.last().trim()
    val partitionType = parts[parts.size - 2].trim()
    if (partition.isEmpty() || partitionType.isEmpty()) return null
    if (partition.toLongOrNull() == null || partitionType.toLongOrNull() == null) return null
    return partitionType to partition
  }

  /** Splits an encoded id back into `partitionType to partition`. */
  fun idsOf(categoryId: String): Pair<String, String>? {
    val parts = categoryId.split(':')
    if (parts.size != 2) return null
    val type = parts[0].takeIf { it.toLongOrNull() != null } ?: return null
    val partition = parts[1].takeIf { it.toLongOrNull() != null } ?: return null
    return type to partition
  }

  fun load(assets: AssetReader): List<Category> = parse(assets.read(ASSET_NAME))

  private fun JsonObject.str(key: String): String? =
    this[key]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

  const val ASSET_NAME = "categories/douyin_categories.json"
}
