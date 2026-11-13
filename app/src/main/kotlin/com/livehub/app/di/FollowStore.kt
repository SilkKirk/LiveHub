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

  /**
   * Keys of the rooms pinned to the 特别关注 row, in the user's order.
   *
   * Returned as keys rather than streamers so the screen can intersect it with whatever it is
   * already filtering: the avatar row is a *view* over the follow list, not a second list that
   * would need its own refresh path.
   */
  fun specialKeys(): List<String> {
    val positions = HashMap<String, Int>()
    for (record in records()) {
      if (record.deleted) continue
      val key = LibraryKey.decode(record.key) as? LibraryKey.Room ?: continue
      val value = record.value ?: continue
      if (!FollowCodec.isSpecial(value)) continue
      positions[Streamer.keyOf(key.platform, key.roomId)] = FollowCodec.orderOf(value)
    }
    return positions.keys.sortedWith(
      compareBy<String> { positions[it] ?: Int.MAX_VALUE },
    )
  }

  /**
   * The user's labels per followed room, keyed by [Streamer.key].
   *
   * A map rather than a `List<TaggedStreamer>` for the same reason [specialKeys] is a key list: the
   * follow screen already has the rooms and only needs one more fact about each of them.
   */
  fun tagsByKey(): Map<String, List<String>> {
    val out = HashMap<String, List<String>>()
    for (record in records()) {
      if (record.deleted) continue
      val key = LibraryKey.decode(record.key) as? LibraryKey.Room ?: continue
      val value = record.value ?: continue
      val tags = FollowCodec.tagsOf(value)
      if (tags.isNotEmpty()) out[Streamer.keyOf(key.platform, key.roomId)] = tags
    }
    return out
  }

  /**
   * Every tag in use, sorted.
   *
   * Sorted rather than in first-seen order so the filter row does not rearrange itself when a room
   * is tagged or untagged; a row that jumps between taps is how a filter becomes unusable.
   */
  fun allTags(): List<String> = tagsByKey().values.flatten().distinct().sorted()

  // -------------------------------------------------------------------- writing

  /**
   * Pins [streamer] to the 特别关注 row, or unpins it.
   *
   * The flag lives on the room's own record rather than in a separate one: it describes the same
   * thing the record already is ("this is a room you follow"), and a second record kind would bring
   * back the ordering/reference problem the removed `member:` records had. The tradeoff is that
   * "phone stars it" and "tablet unstars it" resolve by HLC instead of both surviving — which is
   * the correct semantic for a single boolean.
   *
   * Returns false when the room is not followed; a special mark without a follow would be a pin to
   * a row it can never appear in.
   */
  fun toggleSpecial(streamer: Streamer, hlc: Hlc, deviceId: Long, now: Long): Boolean {
    val roomKey = LibraryKey.Room(streamer.platform, streamer.roomId).encode()
    val existing = records().firstOrNull { it.key == roomKey }
    // Bound to a local before the null check: `SyncRecord` is declared in `:core:sync`, and Kotlin
    // cannot smart cast a `val` from another module — `existing.value[...]` below would not compile.
    val existingValue = existing?.value
    if (existing == null || existing.deleted || existingValue == null) return false

    val special = !FollowCodec.isSpecial(existingValue)
    val updated = SyncRecord(
      key = roomKey,
      value = FollowCodec.fromStreamer(
        streamer = streamer,
        now = now,
        // All three survive the rewrite: this only flips one flag, it must not reset the
        // arrangement the user made by dragging, nor forget the labels they typed.
        order = existingValue["order"],
        special = special,
        tags = FollowCodec.tagsOf(existingValue),
      ),
      deleted = false,
      hlc = hlc,
      deviceId = deviceId,
      updatedAt = now,
    )
    persist(records().filterNot { it.key == roomKey } + updated)
    return true
  }

  /**
   * Replaces the tags of one followed room.
   *
   * Tags are free-form user labels, stored on the room's own record next to `special` and `order`:
   * the record already means "a room you follow", and everything the user decided about that room
   * belongs with it. Emptying the list removes the field rather than writing `[]`, so untagging a
   * room returns its record to the shape it had before it was ever tagged.
   *
   * Returns false when the room is not followed — there is no record to put the labels on.
   */
  fun setTags(streamer: Streamer, tags: List<String>, hlc: Hlc, deviceId: Long, now: Long): Boolean {
    val roomKey = LibraryKey.Room(streamer.platform, streamer.roomId).encode()
    val existing = records().firstOrNull { it.key == roomKey }
    val existingValue = existing?.value
    if (existing == null || existing.deleted || existingValue == null) return false

    val cleaned = tags.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
    // Nothing to do: re-stamping the clock for an unchanged list would make two devices fight over
    // the same value on every sync.
    if (cleaned == FollowCodec.tagsOf(existingValue)) return true

    val updated = SyncRecord(
      key = roomKey,
      value = FollowCodec.fromStreamer(
        streamer = streamer,
        now = now,
        order = existingValue["order"],
        special = FollowCodec.isSpecial(existingValue),
        tags = cleaned,
      ),
      deleted = false,
      hlc = hlc,
      deviceId = deviceId,
      updatedAt = now,
    )
    persist(records().filterNot { it.key == roomKey } + updated)
    return true
  }

  /**
   * Follows [streamer], or unfollows it when it is already followed.
   *
   * A re-followed room comes back as a **brand-new record**: at the end of the list, unstarred.
   * The tombstone written on unfollow carries no payload, so there is nothing to inherit — and
   * inheriting would be wrong anyway. Unfollowing means "I am done with this room"; a position or
   * a 特别关注 mark the user never re-gave must not silently return.
   */
  fun toggle(streamer: Streamer, hlc: Hlc, deviceId: Long, now: Long) {
    val roomKey = LibraryKey.Room(streamer.platform, streamer.roomId).encode()
    val existing = records().firstOrNull { it.key == roomKey }
    val following = existing == null || existing.deleted

    val next = records().filterNot { it.key == roomKey }.toMutableList()
    if (following) {
      next += SyncRecord(
        key = roomKey,
        value = FollowCodec.fromStreamer(streamer, now, order = null, special = false),
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
      // The manual order, the 特别关注 mark *and* the tags survive the refresh. The worker rebuilds
      // the record from a network `Streamer`, which knows nothing about any of them — dropping the
      // flag here would quietly empty the avatar row every 30 minutes, and dropping the tags would
      // erase labels the user typed.
      value = FollowCodec.fromStreamer(
        streamer = streamer,
        now = now,
        order = existing.value?.get("order"),
        special = FollowCodec.isSpecial(existing.value),
        tags = FollowCodec.tagsOf(existing.value),
      ),
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
