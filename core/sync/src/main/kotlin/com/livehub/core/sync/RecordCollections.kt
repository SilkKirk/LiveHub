package com.livehub.core.sync

import kotlinx.serialization.json.JsonObject

/**
 * Collections that follow the same record/tombstone semantics as the follow list.
 *
 * Each scope is stored as a list of `SyncRecord` encoded with [SyncCodec], so the merge
 * behaviour is identical everywhere: HLC ordering, tombstones for deletes, device id as
 * the tie breaker.
 */
object RecordCollections {

  /** Encodes a set of string keys with their HLC stamp. */
  fun encode(
    entries: List<Triple<String, JsonObject?, Boolean>>,
    hlc: Hlc,
    deviceId: Long,
    now: Long,
  ): List<SyncRecord<JsonObject>> = entries.map { (key, value, deleted) ->
    SyncRecord(
      key = key,
      value = if (deleted) null else value,
      deleted = deleted,
      hlc = hlc,
      deviceId = deviceId,
      updatedAt = now,
    )
  }

  /** Applies a local upsert to an existing record list. */
  fun upsert(
    records: List<SyncRecord<JsonObject>>,
    key: String,
    value: JsonObject,
    hlc: Hlc,
    deviceId: Long,
    now: Long,
  ): List<SyncRecord<JsonObject>> {
    val updated = SyncRecord(
      key = key,
      value = value,
      deleted = false,
      hlc = hlc,
      deviceId = deviceId,
      updatedAt = now,
    )
    return records.filterNot { it.key == key } + updated
  }

  /** Applies a local delete as a tombstone rather than dropping the record. */
  fun remove(
    records: List<SyncRecord<JsonObject>>,
    key: String,
    hlc: Hlc,
    deviceId: Long,
    now: Long,
  ): List<SyncRecord<JsonObject>> {
    val tombstone = SyncRecord<JsonObject>(
      key = key,
      value = null,
      deleted = true,
      hlc = hlc,
      deviceId = deviceId,
      updatedAt = now,
    )
    return records.filterNot { it.key == key } + tombstone
  }

  /** Live values in insertion order. */
  fun alive(records: List<SyncRecord<JsonObject>>): List<Pair<String, JsonObject>> =
    records.filter { !it.deleted }.mapNotNull { record ->
      record.value?.let { record.key to it }
    }

  fun isAlive(records: List<SyncRecord<JsonObject>>, key: String): Boolean =
    records.firstOrNull { it.key == key }?.deleted == false
}
