package com.livehub.app.di

import com.livehub.core.model.LibraryKey
import com.livehub.core.model.Platform
import com.livehub.core.sync.Hlc
import com.livehub.core.sync.SyncCodec
import com.livehub.core.sync.SyncRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The persisted record set: loading, migrating and writing it, plus the record-level helpers every
 * kind shares.
 *
 * This is the layer that knows about DataStore and about the key vocabulary being versioned. It
 * knows nothing about rooms, groups, memberships or favourites as *concepts* — that is
 * [LibraryStore]'s job. The split exists because those are two genuinely different reasons to
 * change: a new storage field or a migration touches only this file, while a new rule about what a
 * group may contain touches only the other.
 *
 * ## Versioning
 *
 * [RECORDS_VERSION] is bumped whenever the key vocabulary changes. Version 2 introduced the
 * [LibraryKey] prefixes (`room:` / `group:` / `fav:` / `member:`); version 1 keys were bare
 * `<platform>:<roomId>`.
 *
 * The version-1 rewrite below is a **one-shot migration, not a compatibility layer**: old keys are
 * replaced, never read alongside the new ones, and the block can be deleted once no installation
 * can still hold version 1. It exists because an internal refactor must not silently empty a
 * user's follow list.
 */
internal class LibraryRecordSet(private val preferences: UserPreferences) {

  /**
   * `null` means "not loaded yet". An empty list is a valid cached value, so a sentinel is
   * required instead of a size check — otherwise every call re-parses DataStore.
   */
  @Volatile private var cached: List<SyncRecord<JsonObject>>? = null

  fun records(): List<SyncRecord<JsonObject>> {
    cached?.let { return it }
    return load().also { cached = it }
  }

  /** Replaces the whole set, used when a merged remote snapshot is applied. */
  fun replaceAll(records: List<SyncRecord<JsonObject>>) = persist(records)

  /** The live value at [key], or null when the record is absent or tombstoned. */
  fun liveValue(key: String): JsonObject? =
    records().firstOrNull { it.key == key && !it.deleted }?.value

  fun tombstone(key: String, hlc: Hlc, deviceId: Long, now: Long): SyncRecord<JsonObject> =
    SyncRecord(key = key, value = null, deleted = true, hlc = hlc, deviceId = deviceId, updatedAt = now)

  private fun load(): List<SyncRecord<JsonObject>> {
    val settings = preferences.settings
    val raw = settings.libraryRecords
    val decoded = if (raw.isBlank()) emptyList() else runCatching {
      SyncCodec.decodeRecords(raw)
    }.getOrDefault(emptyList())
    if (settings.recordsVersion >= RECORDS_VERSION) return decoded

    val migrated = decoded.map { record ->
      if (LibraryKey.decode(record.key) != null) record else migrateLegacyKey(record)
    }
    preferences.update {
      it.copy(libraryRecords = SyncCodec.encodeRecords(migrated), recordsVersion = RECORDS_VERSION)
    }
    return migrated
  }

  /** `douyu:288016` → `room:douyu:288016`. Anything unrecognised is left alone. */
  private fun migrateLegacyKey(record: SyncRecord<JsonObject>): SyncRecord<JsonObject> {
    val split = record.key.indexOf(':')
    if (split <= 0) return record
    val platform = Platform.fromId(record.key.substring(0, split)) ?: return record
    val roomId = record.key.substring(split + 1)
    if (roomId.isBlank()) return record
    return record.copy(key = LibraryKey.Room(platform, roomId).encode())
  }

  private fun persist(records: List<SyncRecord<JsonObject>>) {
    cached = records
    preferences.update {
      it.copy(
        libraryRecords = SyncCodec.encodeRecords(records),
        recordsVersion = RECORDS_VERSION,
      )
    }
  }

  companion object {
    const val RECORDS_VERSION = 2
  }
}

/**
 * Rewrites [index] into this record's `order` field and re-stamps its clock.
 *
 * One helper for all four kinds because "move this row to position N" is the same write everywhere;
 * only which records are eligible differs, and that is the caller's filter. A top-level extension
 * rather than a member of [LibraryRecordSet] so [LibraryStore] can use it without holding the set
 * in scope at every call site.
 */
internal fun SyncRecord<JsonObject>.withOrder(
  index: Int,
  hlc: Hlc,
  deviceId: Long,
  now: Long,
): SyncRecord<JsonObject> = copy(
  value = value?.let { JsonObject(it + ("order" to JsonPrimitive(index))) },
  hlc = hlc,
  deviceId = deviceId,
  updatedAt = now,
)
