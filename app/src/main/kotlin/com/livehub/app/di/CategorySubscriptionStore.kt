package com.livehub.app.di

import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import com.livehub.core.sync.Hlc
import com.livehub.core.sync.RecordCollections
import com.livehub.core.sync.SyncCodec
import com.livehub.core.sync.SyncRecord
import com.livehub.core.sync.SyncScope
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Persists the categories the user pinned to the home screen.
 *
 * Stored with the same record/tombstone shape as the follow list so pinning and
 * unpinning propagate between devices instead of silently diverging.
 */
class CategorySubscriptionStore(private val preferences: UserPreferences) {
  @Volatile private var cached: List<SyncRecord<JsonObject>>? = null

  fun records(): List<SyncRecord<JsonObject>> {
    cached?.let { return it }
    val raw = preferences.settings.categoryRecords
    val decoded = if (raw.isBlank()) emptyList() else runCatching {
      SyncCodec.decodeRecords(raw)
    }.getOrDefault(emptyList())
    cached = decoded
    return decoded
  }

  fun subscribed(): List<Category> = RecordCollections.alive(records()).mapNotNull { (_, value) ->
        toCategory(value)
      }
      .sortedBy { it.sortOrder }

  fun isSubscribed(category: Category): Boolean = RecordCollections.isAlive(records(), category.key)

  fun toggle(category: Category, hlc: Hlc, deviceId: Long, now: Long) {
    val key = category.key
    val current = records()
    val next = if (RecordCollections.isAlive(current, key)) {
      RecordCollections.remove(current, key, hlc, deviceId, now)
    } else {
      RecordCollections.upsert(current, key, fromCategory(category), hlc, deviceId, now)
    }
    persist(next)
  }

  fun replaceAll(records: List<SyncRecord<JsonObject>>) = persist(records)

  private fun persist(records: List<SyncRecord<JsonObject>>) {
    cached = records
    preferences.update { it.copy(categoryRecords = SyncCodec.encodeRecords(records)) }
  }

  private fun fromCategory(category: Category): JsonObject = buildJsonObject {
    put("platform", category.platform.id)
    put("id", category.id)
    put("name", category.name)
    put("parentId", category.parentId.orEmpty())
    put("parentName", category.parentName.orEmpty())
    put("iconUrl", category.iconUrl.orEmpty())
    put("sortOrder", category.sortOrder)
  }

  private fun toCategory(value: JsonObject): Category? {
    val platformId = value["platform"]?.jsonPrimitive?.contentOrNull ?: return null
    val platform = Platform.fromId(platformId) ?: return null
    val id = value["id"]?.jsonPrimitive?.contentOrNull ?: return null
    return Category(
      platform = platform,
      id = id,
      name = value["name"]?.jsonPrimitive?.contentOrNull.orEmpty(),
      parentId = value["parentId"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
      parentName = value["parentName"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
      iconUrl = value["iconUrl"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() },
      sortOrder = value["sortOrder"]?.jsonPrimitive?.intOrNull ?: 0,
    )
  }
}

/** [com.livehub.core.sync.SyncTarget] for the category subscription scope. */
class CategorySyncTarget(
  private val store: CategorySubscriptionStore,
) : com.livehub.core.sync.SyncTarget {
  override val scope: SyncScope = SyncScope.CATEGORIES

  override fun snapshot(): List<SyncRecord<out Any>> = store.records()

  override fun apply(records: List<SyncRecord<out Any>>): Int {
    @Suppress("UNCHECKED_CAST")
    val typed = records as List<SyncRecord<JsonObject>>
    val before = store.records().associate { it.key to it.version }
    store.replaceAll(typed)
    return typed.count { before[it.key] != it.version }
  }
}

/** Helpers for reordering the follow list while keeping sync metadata intact. */
object FollowOrdering {
  /**
   * Reorders [ordered] so that [moved] sits at [toIndex].
   *
   * Returns the new order as a list of streamer keys; the caller re-stamps each record
   * so the new order wins over a concurrent reorder from another device.
   */
  fun move(ordered: List<Streamer>, moved: Streamer, toIndex: Int): List<String> {
    if (ordered.isEmpty()) return emptyList()
    val without = ordered.filterNot { it.key == moved.key }
    val target = toIndex.coerceIn(0, without.size)
    val result = without.toMutableList()
    result.add(target, moved)
    return result.map { it.key }
  }
}
