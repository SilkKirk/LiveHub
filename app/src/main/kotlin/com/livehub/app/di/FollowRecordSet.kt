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
   * Guards every read-modify-write over [cached].
   *
   * The whole record set is one value that is replaced wholesale, so "read it, derive a new one,
   * store it" is not a sequence that may interleave with another one. Two writers used to be able
   * to run it at once and the second would publish a list computed from the base it read
   * *before* the first landed — losing the first writer's change from memory and from disk alike.
   *
   * The two writers are on different threads, which is what made it real rather than theoretical:
   * the UI thread (a star tap, a tag edit, a drag reorder) and the sync server's connection
   * threads (`SyncEngine.applyPayload` → `FollowSyncTarget.apply` → [replaceAll]). Pull a follow
   * list while tapping a star and the star could disappear — the same class of bug
   * [UserPreferences.update] already guards against, one layer up.
   *
   * Note that [UserPreferences.updateLock] does **not** cover this: it makes one
   * `transform` atomic, but the base it transforms is read here, on the caller's thread, before
   * `update` is ever entered.
   */
  private val lock = Any()

  /**
   * `null` means "not loaded yet". An empty list is a valid cached value, so a sentinel is
   * required instead of a size check — otherwise every call re-parses DataStore.
   */
  @Volatile private var cached: List<SyncRecord<JsonObject>>? = null

  fun records(): List<SyncRecord<JsonObject>> = synchronized(lock) { recordsLocked() }

  private fun recordsLocked(): List<SyncRecord<JsonObject>> {
    cached?.let { return it }
    return load().also { cached = it }
  }

  /** Replaces the whole set, used when a merged remote snapshot is applied. */
  fun replaceAll(records: List<SyncRecord<JsonObject>>) =
    synchronized(lock) { persist(normalize(records)) }

  /**
   * Applies [transform] to the current set as one atomic read-modify-write, and returns whatever
   * it produced.
   *
   * Every mutation in [FollowStore] goes through here rather than doing its own
   * `records()` → change → `replaceAll()`, because that shape is exactly the lost-update window
   * described on [lock]. [transform] must be pure: it is handed the authoritative list and its
   * result is both cached and written.
   */
  fun mutate(
    transform: (List<SyncRecord<JsonObject>>) -> List<SyncRecord<JsonObject>>,
  ) {
    mutateReturning { current -> transform(current) to Unit }
  }

  /**
   * The same, for a mutation that also has something to report — `false` when the room was not
   * followed, for instance.
   *
   * A separate name rather than an overload: the two transforms differ only in their return type,
   * and a lambda that happens to produce a list is exactly what the other one expects.
   */
  fun <R> mutateReturning(
    transform: (List<SyncRecord<JsonObject>>) -> Pair<List<SyncRecord<JsonObject>>, R>,
  ): R = synchronized(lock) {
    val (next, result) = transform(recordsLocked())
    persist(normalize(next))
    result
  }

  /**
   * Applies the key normalisation without writing.
   *
   * Exposed for callers that are already inside [mutateReturning] and need the filtered/rewritten
   * list as a *value* rather than as a side effect — `FollowSyncTarget.apply` counts what survived
   * normalisation, so it needs the result without a second write.
   */
  fun normalizeForApply(records: List<SyncRecord<JsonObject>>): List<SyncRecord<JsonObject>> =
    normalize(records)

  /**
   * Runs [block] holding [lock].
   *
   * The critical section the sync engine needs: `SyncEngine.applyPayload` has to read the set,
   * merge against it and write the result, and the read and the write must not be separated by a
   * local mutation. `apply` being atomic on its own would not be enough — the merged list is
   * derived from the snapshot read before it.
   *
   * Reentrant — `synchronized` is a JVM monitor — so the nested acquisition in [replaceAll] or in a
   * mutator is harmless. It still must not be used as a general-purpose lock: [block] runs with
   * every other writer blocked, so it must not wait on anything outside this class.
   */
  fun <R> locked(block: () -> R): R = synchronized(lock) { block() }

  /** The live value at [key], or null when the record is absent or tombstoned. */
  fun liveValue(key: String): JsonObject? = synchronized(lock) {
    recordsLocked().firstOrNull { it.key == key && !it.deleted }?.value
  }

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
