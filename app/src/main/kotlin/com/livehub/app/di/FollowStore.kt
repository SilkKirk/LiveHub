package com.livehub.app.di

import com.livehub.core.model.LibraryKey
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import com.livehub.core.sync.Hlc
import com.livehub.core.sync.SyncRecord
import com.livehub.core.sync.SyncScope
import com.livehub.core.sync.SyncTarget
import kotlinx.serialization.json.JsonObject

/**
 * The user's followed rooms: one synchronised record per room.
 *
 * ## Three layers, three reasons to change
 *
 *  * [FollowCodec] — the JSON shape of a record. Changes when the *wire format* changes.
 *  * [FollowRecordSet] — loading, migrating and writing the set. Changes when *storage* changes.
 *  * this class — the rules. Changes when *the product* changes.
 *
 * They were one 700-line file while the store also carried groups, memberships and pinned
 * categories. Those three record kinds are gone (see [FollowRecordSet.RECORDS_VERSION]); what is
 * left is genuinely one list, so the split is smaller but the same.
 *
 * ## Deletes
 *
 * A room unfollows by tombstone, never by dropping the record, so "removed on the tablet" cannot be
 * undone by a phone that still holds the old value. Tombstones are reclaimed by
 * [com.livehub.core.sync.SyncMerge.prune] once every paired peer has confirmed them.
 */
class FollowStore(preferences: UserPreferences) {

  private val set = FollowRecordSet(preferences)

  // -------------------------------------------------------------------- reading

  fun records(): List<SyncRecord<JsonObject>> = set.records()

  /**
   * Followed rooms in the user's manual order.
   *
   * The order lives inside each record, so a reorder on one device reaches the others and survives
   * a restart. Records without an explicit order sort last, by recency.
   */
  fun followed(): List<Streamer> {
    val orders = HashMap<String, Int>()
    val out = ArrayList<Streamer>()
    for (record in records()) {
      if (record.deleted) continue
      val key = LibraryKey.decode(record.key) as? LibraryKey.Room ?: continue
      val value = record.value ?: continue
      val streamer = FollowCodec.toStreamer(key, value) ?: continue
      orders[Streamer.keyOf(key.platform, key.roomId)] = FollowCodec.orderOf(value)
      out.add(streamer)
    }
    return out.sortedWith(
      compareBy<Streamer> { orders[it.key] ?: Int.MAX_VALUE }.thenByDescending { it.updatedAt },
    )
  }

  fun isFollowed(platform: Platform, roomId: String): Boolean =
    set.liveValue(LibraryKey.Room(platform, roomId).encode()) != null

  // -------------------------------------------------------------------- writing

  /**
   * Follows [streamer], or unfollows it when it is already followed.
   *
   * Re-following keeps no memory of the old record other than its position: a room that comes back
   * returns where the user had arranged it rather than at the bottom.
   */
  fun toggle(streamer: Streamer, hlc: Hlc, deviceId: Long, now: Long) {
    val roomKey = LibraryKey.Room(streamer.platform, streamer.roomId).encode()
    val existing = records().firstOrNull { it.key == roomKey }
    val following = existing == null || existing.deleted

    val next = records().filterNot { it.key == roomKey }.toMutableList()
    if (following) {
      next += SyncRecord(
        key = roomKey,
        value = FollowCodec.fromStreamer(streamer, now, order = existing?.value?.get("order")),
        deleted = false,
        hlc = hlc,
        deviceId = deviceId,
        updatedAt = now,
      )
    } else {
      next += set.tombstone(roomKey, hlc, deviceId, now)
    }
    persist(next)
  }

  /**
   * Refreshes the cached metadata of an already-followed room.
   *
   * Used by the background refresh worker: it must not *add* a follow (the user may have
   * unfollowed on another device since the worker started), only update one that is still alive.
   */
  fun updateSnapshot(streamer: Streamer, hlc: Hlc, deviceId: Long, now: Long) {
    val roomKey = LibraryKey.Room(streamer.platform, streamer.roomId).encode()
    val existing = records().firstOrNull { it.key == roomKey }
    if (existing == null || existing.deleted) return
    val updated = SyncRecord(
      key = roomKey,
      // The manual order survives the refresh; without this every background run would reset the
      // user's arrangement back to recency.
      value = FollowCodec.fromStreamer(streamer, now, existing.value?.get("order")),
      deleted = false,
      hlc = hlc,
      deviceId = deviceId,
      updatedAt = now,
    )
    persist(records().filterNot { it.key == roomKey } + updated)
  }

  /**
   * Persists a new manual order for the followed rooms.
   *
   * [roomKeys] are [Streamer.key] values (`douyu:288016`), which is what the UI already works with.
   * Every reordered record gets the same fresh HLC, so the new order wins against a concurrent
   * reorder on another device while the payload stays one clock tick.
   */
  fun reorder(roomKeys: List<String>, hlc: Hlc, deviceId: Long, now: Long) {
    if (roomKeys.isEmpty()) return
    val positions = roomKeys.withIndex().associate { it.value to it.index }
    persist(
      records().map { record ->
        val key = LibraryKey.decode(record.key) as? LibraryKey.Room
        val index = key?.let { positions[Streamer.keyOf(it.platform, it.roomId)] }
        if (key == null || index == null || record.deleted) record else record.withOrder(index, hlc, deviceId, now)
      },
    )
  }

  /** Replaces the whole set, used when a merged remote snapshot is applied. */
  fun replaceAll(records: List<SyncRecord<JsonObject>>) = set.replaceAll(records)

  // ------------------------------------------------------------------ internals

  private fun persist(records: List<SyncRecord<JsonObject>>) = set.replaceAll(records)
}

/** [SyncTarget] for the follow scope — the followed rooms and their manual order. */
class FollowSyncTarget(private val store: FollowStore) : SyncTarget {
  override val scope: SyncScope = SyncScope.FOLLOWS

  override fun snapshot(): List<SyncRecord<out Any>> = store.records()

  override fun apply(records: List<SyncRecord<out Any>>): Int {
    @Suppress("UNCHECKED_CAST")
    val typed = records as List<SyncRecord<JsonObject>>
    val before = store.records().associate { it.key to it.version }
    store.replaceAll(typed)
    // Counted against what the store *kept*, not against what arrived: a retired `group:` record
    // from an older peer is dropped by the store, and reporting it as an applied change would make
    // the sync log claim it moved data it never stored.
    return store.records().count { before[it.key] != it.version }
  }
}

/**
 * Wires the scopes the app exchanges.
 *
 * Two entries: the follow list, and the Bilibili/Douyin login state. `SyncScopesTest` pins both
 * halves — the registry exposes exactly these two, and the sync screen names both.
 */
class AppSyncTargets(
  private val followStore: FollowStore,
  private val credentialStore: CredentialStore,
) : com.livehub.core.sync.SyncTargets {
  private val targets = listOf(
    FollowSyncTarget(followStore),
    CredentialSyncTarget(credentialStore),
  ).associateBy { it.scope }

  override fun targetFor(scope: SyncScope): SyncTarget? = targets[scope]

  override fun available(): List<SyncTarget> = targets.values.toList()

  override fun enabledScopes(): Set<SyncScope> = targets.keys
}
