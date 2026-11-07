package com.livehub.app.di

import com.livehub.core.model.LibraryKey
import com.livehub.core.model.Platform
import com.livehub.core.sync.Hlc
import com.livehub.core.sync.SyncCodec
import com.livehub.core.sync.SyncRecord
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The persisted follow records: loading, migrating and writing them, plus the record-level
 * helpers every caller shares.
 *
 * This is the layer that knows about DataStore and about the key vocabulary being versioned. It
 * knows nothing about rooms as a *concept* — that is [FollowStore]'s job. The split exists because
 * those are two genuinely different reasons to change: a storage field or a migration touches only
 * this file, while a rule about what a follow means touches only the other.
 *
 * ## Versioning
 *
 * [RECORDS_VERSION] is bumped whenever the key vocabulary changes.
 *
 *  * 1 — bare `<platform>:<roomId>` keys, followed rooms only.
 *  * 2 — prefixed keys with four record kinds: `room:` / `group:` / `member:` / `fav:`.
 *  * 3 — back to `room:` only. The group and pinned-category features were removed.
 *
 * ## Why version 3 *drops* records instead of tombstoning them
 *
 * The record kinds it discards no longer have a reader: nothing in the app can render a group, a
 * membership or a favourite, so carrying them would only mean shipping dead weight to every peer
 * forever. Tombstones would be marginally cleaner for the *other* device's copy — it would merge
 * the deletion instead of keeping a group it can no longer see on this one — but they would need an
 * HLC and a device id at load time, before any sync engine exists, and the group would already be
 * invisible to the user here.
 *
 * The normalisation below is therefore authoritative and runs on **every** load and every
 * [replaceAll], not just once: a version-2 peer that is still paired can push `group:` records at
 * any time, and they must be discarded rather than stored and echoed back.
 */
internal class FollowRecordSet(private val preferences: UserPreferences) {

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
  fun replaceAll(records: List<SyncRecord<JsonObject>>) = persist(normalize(records))

  /** The live value at [key], or null when the record is absent or tombstoned. */
  fun liveValue(key: String): JsonObject? =
    records().firstOrNull { it.key == key && !it.deleted }?.value

  fun tombstone(key: String, hlc: Hlc, deviceId: Long, now: Long): SyncRecord<JsonObject> =
    SyncRecord(key = key, value = null, deleted = true, hlc = hlc, deviceId = deviceId, updatedAt = now)

  private fun load(): List<SyncRecord<JsonObject>> {
    val settings = preferences.settings
    val raw = settings.followRecords
    val decoded = if (raw.isBlank()) emptyList() else runCatching {
      SyncCodec.decodeRecords(raw)
    }.getOrDefault(emptyList())
    val normalized = normalize(decoded)
    // Written back only when something actually changed: a version bump or a dropped record. An
    // unconditional write would re-encode every follow on every cold start for no benefit.
    if (settings.recordsVersion < RECORDS_VERSION || normalized != decoded) {
      preferences.update {
        it.copy(
          followRecords = SyncCodec.encodeRecords(normalized),
          recordsVersion = RECORDS_VERSION,
        )
      }
    }
    return normalized
  }

  /**
   * Keeps the `room:` records and rewrites the version-1 bare keys; everything else is dropped.
   *
   * The bare-key rewrite is keyed on "this does not parse", so it has to run *before* the drop:
   * `douyu:288016` is a room whose key merely lacks the prefix. A retired kind such as
   * `group:<uuid>` has no platform id in front of the colon, so it falls through to the drop
   * instead of being mistaken for one.
   */
  private fun normalize(records: List<SyncRecord<JsonObject>>): List<SyncRecord<JsonObject>> =
    records.mapNotNull { record ->
      if (LibraryKey.decode(record.key) != null) record else roomFromLegacyKey(record)
    }

  /** `douyu:288016` → `room:douyu:288016`, or null when the key is not a legacy room key. */
  private fun roomFromLegacyKey(record: SyncRecord<JsonObject>): SyncRecord<JsonObject>? {
    val split = record.key.indexOf(':')
    if (split <= 0) return null
    val platform = Platform.fromId(record.key.substring(0, split)) ?: return null
    val roomId = record.key.substring(split + 1)
    if (roomId.isBlank()) return null
    return record.copy(key = LibraryKey.Room(platform, roomId).encode())
  }

  private fun persist(records: List<SyncRecord<JsonObject>>) {
    cached = records
    preferences.update {
      it.copy(
        followRecords = SyncCodec.encodeRecords(records),
        recordsVersion = RECORDS_VERSION,
      )
    }
  }

  companion object {
    const val RECORDS_VERSION = 3
  }
}

/**
 * Rewrites [index] into this record's `order` field and re-stamps its clock.
 *
 * A top-level extension rather than a member of [FollowRecordSet] so [FollowStore] can use it
 * without holding the set in scope at every call site.
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
