package com.livehub.app.di

import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import com.livehub.core.sync.Hlc
import com.livehub.core.sync.SyncCodec
import com.livehub.core.sync.SyncRecord
import com.livehub.core.sync.SyncScope
import com.livehub.core.sync.SyncTarget
import com.livehub.core.sync.SyncTargets
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/**
 * Persists the followed rooms with sync metadata.
 *
 * The follow list is the primary synchronised scope, so it is stored **with** its HLC
 * and tombstones rather than as a plain list: that is what allows an unfollow performed
 * on one device to win over a stale follow from another.
 */
class FollowStore(private val preferences: UserPreferences) {
  /**
   * `null` means "not loaded yet". An empty list is a valid cached value, so a sentinel
   * is required instead of a size check — otherwise every call re-parses DataStore.
   */
  @Volatile private var cachedRecords: List<SyncRecord<JsonObject>>? = null

  fun records(): List<SyncRecord<JsonObject>> {
    cachedRecords?.let { return it }
    val raw = preferences.settings.followRecords
    val decoded = if (raw.isBlank()) emptyList() else runCatching {
      SyncCodec.decodeRecords(raw)
    }.getOrDefault(emptyList())
    cachedRecords = decoded
    return decoded
  }

  /**
   * Live (non-deleted) followed streamers in the user's chosen order.
   *
   * The order is persisted inside each record's `order` field, so a manual reorder on
   * one device reaches the others and survives restarts. Records without an explicit
   * order sort last, by recency.
   */
  fun followed(): List<Streamer> = records()
    .filter { !it.deleted }
    .mapNotNull { record -> record.value?.let { toStreamer(it) } }
    .sortedWith(
      compareBy<Streamer> { orderOfKey(it.key) }
        .thenByDescending { it.updatedAt },
    )

  /** Explicit sort index of [key], or [Int.MAX_VALUE] when never reordered. */
  fun orderOfKey(key: String): Int =
    records().firstOrNull { it.key == key }
      ?.value?.get("order")?.jsonPrimitive?.intOrNull
      ?: Int.MAX_VALUE

  /**
   * Persists a new manual order for [keys].
   *
   * Every reordered record gets the same fresh HLC, which makes the new order win
   * against a concurrent reorder on another device while keeping the payload to one
   * clock tick. Tombstones and records outside [keys] are left untouched.
   */
  fun reorder(keys: List<String>, hlc: Hlc, deviceId: Long, now: Long) {
    if (keys.isEmpty()) return
    val positions = keys.withIndex().associate { it.value to it.index }
    val updated = records().map { record ->
      val index = positions[record.key]
      val value = record.value
      if (index == null || record.deleted || value == null) {
        record
      } else {
        record.copy(
          value = JsonObject(value + ("order" to JsonPrimitive(index))),
          hlc = hlc,
          deviceId = deviceId,
          updatedAt = now,
        )
      }
    }
    persist(updated)
  }

  fun isFollowed(platform: Platform, roomId: String): Boolean {
    val key = Streamer.keyOf(platform, roomId)
    return records().firstOrNull { it.key == key }?.deleted == false
  }

  fun toggle(streamer: Streamer, hlc: Hlc, deviceId: Long, now: Long) {
    val key = streamer.key
    val existing = records().firstOrNull { it.key == key }
    val next: SyncRecord<JsonObject> = when {
      existing == null || existing.deleted ->
        SyncRecord(
          key = key,
          value = fromStreamer(streamer, now),
          deleted = false,
          hlc = hlc,
          deviceId = deviceId,
          updatedAt = now,
        )
      else -> SyncRecord(
        key = key,
        value = null,
        deleted = true,
        hlc = hlc,
        deviceId = deviceId,
        updatedAt = now,
      )
    }
    persist(records().filterNot { it.key == key } + next)
  }

  /**
   * Refreshes the cached metadata of an already-followed streamer.
   *
   * Used by the background refresh worker: it must not *add* a follow (the user may
   * have unfollowed on another device since the worker started), only update one that
   * is still alive.
   */
  fun updateSnapshot(streamer: Streamer, hlc: Hlc, deviceId: Long, now: Long) {
    val existing = records().firstOrNull { it.key == streamer.key }
    if (existing == null || existing.deleted) return
    val order = existing.value?.get("order")
    val value = fromStreamer(streamer, now).let { base ->
      if (order == null) base else JsonObject(base + ("order" to order))
    }
    val updated = SyncRecord(
      key = streamer.key,
      value = value,
      deleted = false,
      hlc = hlc,
      deviceId = deviceId,
      updatedAt = now,
    )
    persist(records().filterNot { it.key == streamer.key } + updated)
  }
  fun upsertAll(streamers: List<Streamer>, hlc: Hlc, deviceId: Long, now: Long) {
    if (streamers.isEmpty()) return
    val byKey = records().associateBy { it.key }.toMutableMap()
    streamers.forEach { streamer ->
      val existing = byKey[streamer.key]
      if (existing == null || existing.deleted) {
        byKey[streamer.key] = SyncRecord(
          key = streamer.key,
          value = fromStreamer(streamer, now),
          deleted = false,
          hlc = hlc,
          deviceId = deviceId,
          updatedAt = now,
        )
      }
    }
    persist(byKey.values.toList())
  }

  fun replaceAll(records: List<SyncRecord<JsonObject>>) {
    persist(records)
  }

  private fun persist(records: List<SyncRecord<JsonObject>>) {
    cachedRecords = records
    val encoded = SyncCodec.encodeRecords(records)
    preferences.update { it.copy(followRecords = encoded) }
  }

  private fun fromStreamer(streamer: Streamer, now: Long): JsonObject = buildJsonObject {
    put("platform", streamer.platform.id)
    put("roomId", streamer.roomId)
    put("streamerName", streamer.streamerName)
    put("title", streamer.title)
    put("coverUrl", streamer.coverUrl.orEmpty())
    put("avatarUrl", streamer.avatarUrl.orEmpty())
    put("viewerCount", streamer.viewerCount)
    put("isLive", streamer.isLive)
    put("categoryName", streamer.categoryName.orEmpty())
    put("updatedAt", if (streamer.updatedAt == 0L) now else streamer.updatedAt)
  }

  private fun toStreamer(value: JsonObject): Streamer? {
    val platformId = value["platform"]?.jsonPrimitive?.content ?: return null
    val platform = Platform.fromId(platformId) ?: return null
    val roomId = value["roomId"]?.jsonPrimitive?.content ?: return null
    return Streamer(
      platform = platform,
      roomId = roomId,
      streamerName = value["streamerName"]?.jsonPrimitive?.content.orEmpty(),
      title = value["title"]?.jsonPrimitive?.content.orEmpty(),
      coverUrl = value["coverUrl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
      avatarUrl = value["avatarUrl"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
      viewerCount = value["viewerCount"]?.jsonPrimitive?.long ?: 0,
      isLive = value["isLive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
      categoryName = value["categoryName"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
      updatedAt = value["updatedAt"]?.jsonPrimitive?.long ?: 0,
    )
  }
}

/** [SyncTarget] implementation for the follow scope. */
class FollowSyncTarget(private val store: FollowStore) : SyncTarget {
  override val scope: SyncScope = SyncScope.FOLLOWS

  override fun snapshot(): List<SyncRecord<out Any>> = store.records()

  override fun apply(records: List<SyncRecord<out Any>>): Int {
    @Suppress("UNCHECKED_CAST")
    val typed = records as List<SyncRecord<JsonObject>>
    val before = store.records().associate { it.key to it.version }
    store.replaceAll(typed)
    return typed.count { before[it.key] != it.version }
  }
}

/** [SyncTarget] implementation for the danmaku/playback settings. */
class PreferencesSyncTarget(private val store: PreferencesStore) : SyncTarget {
  override val scope: SyncScope = SyncScope.PREFERENCES

  override fun snapshot(): List<SyncRecord<out Any>> = store.records()

  override fun apply(records: List<SyncRecord<out Any>>): Int {
    @Suppress("UNCHECKED_CAST")
    val typed = records as List<SyncRecord<JsonObject>>
    return store.applyRemote(typed)
  }
}

/** [SyncTarget] implementation for the search history. */
class HistorySyncTarget(private val store: HistoryStore) : SyncTarget {
  override val scope: SyncScope = SyncScope.HISTORY

  override fun snapshot(): List<SyncRecord<out Any>> = store.records()

  override fun apply(records: List<SyncRecord<out Any>>): Int {
    @Suppress("UNCHECKED_CAST")
    val typed = records as List<SyncRecord<JsonObject>>
    return store.merge(typed)
  }
}

/**
 * Wires every scope the app can exchange.
 *
 * The set here has to cover all of [SyncScope]: the sync screen renders one chip per entry,
 * so a scope with no target is a switch that silently does nothing on this device and answers
 * the peer with an HTTP 404 it reports as a failure. `SyncScopeCoverageTest` pins that.
 */
class AppSyncTargets(
  private val preferences: UserPreferences,
  private val followStore: FollowStore,
  private val preferencesStore: PreferencesStore,
  private val historyStore: HistoryStore,
) : SyncTargets {
  private val targets = listOf(
    FollowSyncTarget(followStore),
    PreferencesSyncTarget(preferencesStore),
    HistorySyncTarget(historyStore),
  ).associateBy { it.scope }

  override fun targetFor(scope: SyncScope): SyncTarget? = targets[scope]

  override fun available(): List<SyncTarget> = targets.values.toList()

  override fun enabledScopes(): Set<SyncScope> =
    preferences.settings.syncScopes.mapNotNull { SyncScope.fromWireName(it) }.toSet()
}
