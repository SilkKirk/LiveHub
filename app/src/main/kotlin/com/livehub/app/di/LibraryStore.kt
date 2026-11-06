package com.livehub.app.di

import com.livehub.core.model.Category
import com.livehub.core.model.FollowGroup
import com.livehub.core.model.LibraryKey
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import com.livehub.core.sync.Hlc
import com.livehub.core.sync.SyncRecord
import com.livehub.core.sync.SyncScope
import com.livehub.core.sync.SyncTarget
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID

/**
 * The user's library: followed rooms, the groups they filed them into, and pinned categories.
 *
 * All four record kinds live in **one** synchronised record set, distinguished by their
 * [LibraryKey] prefix:
 *
 * ```
 * room:<platform>:<roomId>                the room itself (name, cover, live state, list order)
 * group:<uuid>                            a user-created group (name, order)
 * member:<groupUuid>:<platform>:<roomId>  "this room is in this group" (order inside the group)
 * fav:<platform>:<categoryId>             a category pinned under the platform tab
 * ```
 *
 * ## Three layers, three reasons to change
 *
 *  * [LibraryCodec] — the JSON shape of each kind. Changes when the *wire format* changes.
 *  * [LibraryRecordSet] — loading, migrating and writing the set. Changes when *storage* changes.
 *  * this class — the rules. Changes when *the product* changes.
 *
 * They were one 700-line file until the rules for four record kinds plus a migration plus a
 * version constant made "where do I add this?" a real question. Nothing about the behaviour moved
 * in that split; the seams were already there, they just were not cut.
 *
 * ## Why memberships are their own record instead of a field on the room
 *
 * A room can be in several groups, and that is the whole reason this is a record set rather than a
 * list field on the room. If membership lived in a `groups: List<String>` field, then "phone adds
 * this room to 游戏" and "tablet adds it to 音乐" would be **two writes to the same key**: the merge
 * keeps the higher clock and one of the two additions is silently lost — exactly the failure
 * hybrid logical clocks exist to prevent, reintroduced at field granularity.
 *
 * As separate records they are two keys and two clocks, so both survive. The same split makes
 * group-scoped ordering free ([reorderWithinGroup]) and keeps the background snapshot refresh from
 * ever touching group membership: [updateSnapshot] only ever rewrites `room:` records.
 *
 * ## Deletes
 *
 * Everything deletes by tombstone, never by dropping the record, so "removed on the tablet" cannot
 * be undone by a phone that still holds the old value. Tombstones are reclaimed by
 * [com.livehub.core.sync.SyncMerge.prune] once every paired peer has confirmed them.
 */
class LibraryStore(preferences: UserPreferences) {

  private val set = LibraryRecordSet(preferences)

  // -------------------------------------------------------------------- reading

  fun records(): List<SyncRecord<JsonObject>> = set.records()

  /**
   * Followed rooms in the user's manual order.
   *
   * The order lives inside each `room:` record, so a reorder on one device reaches the others and
   * survives a restart. Records without an explicit order sort last, by recency.
   */
  fun followed(): List<Streamer> {
    val orders = HashMap<String, Int>()
    val out = ArrayList<Streamer>()
    for (record in records()) {
      if (record.deleted) continue
      val key = LibraryKey.decode(record.key) as? LibraryKey.Room ?: continue
      val value = record.value ?: continue
      val streamer = LibraryCodec.toStreamer(key, value) ?: continue
      orders[Streamer.keyOf(key.platform, key.roomId)] = LibraryCodec.orderOf(value)
      out.add(streamer)
    }
    return out.sortedWith(
      compareBy<Streamer> { orders[it.key] ?: Int.MAX_VALUE }.thenByDescending { it.updatedAt },
    )
  }

  fun isFollowed(platform: Platform, roomId: String): Boolean =
    set.liveValue(LibraryKey.Room(platform, roomId).encode()) != null

  /** Live groups in the user's order. */
  fun groups(): List<FollowGroup> {
    val out = ArrayList<FollowGroup>()
    for (record in records()) {
      if (record.deleted) continue
      val key = LibraryKey.decode(record.key) as? LibraryKey.Group ?: continue
      out.add(LibraryCodec.toGroup(key, record.value))
    }
    return out.sortedWith(compareBy<FollowGroup> { it.order }.thenBy { it.name })
  }

  /**
   * Group ids per room key, live memberships only.
   *
   * A membership whose group is gone is dropped here rather than trusted: an offline device can
   * file a room into a group that has since been deleted on another, and that pair must not
   * resurrect the group in the row.
   */
  fun memberships(): Map<String, Set<String>> {
    val liveGroups = groups().mapTo(HashSet()) { it.id }
    val out = HashMap<String, MutableSet<String>>()
    for (record in records()) {
      if (record.deleted) continue
      val key = LibraryKey.decode(record.key) as? LibraryKey.Member ?: continue
      if (key.groupId !in liveGroups) continue
      out.getOrPut(Streamer.keyOf(key.platform, key.roomId)) { HashSet() }.add(key.groupId)
    }
    return out
  }

  fun groupsOf(roomKey: String): Set<String> = memberships()[roomKey].orEmpty()

  /**
   * Rooms inside [groupId] in the order the user arranged them there.
   *
   * Falls back to the global follow order for a room with no explicit position inside the group,
   * which is the state every membership starts in.
   */
  fun roomsInGroup(groupId: String): List<String> {
    val globalOrder = followed().withIndex().associate { (index, streamer) -> streamer.key to index }
    val local = HashMap<String, Int>()
    for (record in records()) {
      if (record.deleted) continue
      val key = LibraryKey.decode(record.key) as? LibraryKey.Member ?: continue
      if (key.groupId != groupId) continue
      local[Streamer.keyOf(key.platform, key.roomId)] = LibraryCodec.orderOf(record.value)
    }
    return local.keys.sortedWith(
      compareBy<String> { local[it] ?: Int.MAX_VALUE }.thenBy { globalOrder[it] ?: Int.MAX_VALUE },
    )
  }

  /** Categories pinned under the platform tab, in the user's order. */
  fun favorites(): List<Category> {
    val out = ArrayList<Pair<Category, Int>>()
    for (record in records()) {
      if (record.deleted) continue
      val key = LibraryKey.decode(record.key) as? LibraryKey.Favorite ?: continue
      val category = LibraryCodec.toCategory(key, record.value) ?: continue
      // A peer can send anything. Dropping an aggregate here keeps the row's contract — every chip
      // is one concrete partition — even if an old or hand-edited record slips through.
      if (category.isAggregate || category.id == Category.ALL_ID) continue
      out.add(category to LibraryCodec.orderOf(record.value))
    }
    return out.sortedWith(compareBy<Pair<Category, Int>> { it.second }.thenBy { it.first.name })
      .map { it.first }
  }

  fun isFavorite(category: Category): Boolean =
    set.liveValue(LibraryKey.Favorite(category.platform, category.id).encode()) != null

  // -------------------------------------------------------------------- writing

  /**
   * Follows [streamer], or unfollows it when it is already followed.
   *
   * Unfollowing also tombstones every membership of that room: a room that is not followed cannot
   * be in a group, and re-following it must land in 未分类 rather than reappear in a group the
   * user did not choose for it this time.
   */
  fun toggle(streamer: Streamer, hlc: Hlc, deviceId: Long, now: Long) {
    val roomKey = LibraryKey.Room(streamer.platform, streamer.roomId).encode()
    val existing = records().firstOrNull { it.key == roomKey }
    val following = existing == null || existing.deleted

    val next = records().filterNot { it.key == roomKey }.toMutableList()
    if (following) {
      next += SyncRecord(
        key = roomKey,
        value = LibraryCodec.fromStreamer(streamer, now, order = existing?.value?.get("order")),
        deleted = false,
        hlc = hlc,
        deviceId = deviceId,
        updatedAt = now,
      )
    } else {
      next += set.tombstone(roomKey, hlc, deviceId, now)
      memberRecordsOf(streamer.key).forEach { member ->
        next.removeAll { it.key == member.key }
        next += set.tombstone(member.key, hlc, deviceId, now)
      }
    }
    persist(next)
  }

  /**
   * Refreshes the cached metadata of an already-followed room.
   *
   * Used by the background refresh worker: it must not *add* a follow (the user may have
   * unfollowed on another device since the worker started), only update one that is still alive.
   * It deliberately rewrites only `room:` records — memberships and favourites are untouched by
   * construction, which is what makes "the worker ate my groups" impossible.
   */
  fun updateSnapshot(streamer: Streamer, hlc: Hlc, deviceId: Long, now: Long) {
    val roomKey = LibraryKey.Room(streamer.platform, streamer.roomId).encode()
    val existing = records().firstOrNull { it.key == roomKey }
    if (existing == null || existing.deleted) return
    val updated = SyncRecord(
      key = roomKey,
      // The manual order survives the refresh; without this every background run would reset the
      // user's arrangement back to recency.
      value = LibraryCodec.fromStreamer(streamer, now, existing.value?.get("order")),
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
  fun reorderRooms(roomKeys: List<String>, hlc: Hlc, deviceId: Long, now: Long) {
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

  /**
   * Creates a group and returns it, or null when [name] is blank, too long or already taken.
   *
   * The id is a fresh UUID rather than a slug of the name: deleting 游戏 and creating 游戏 again
   * has to produce a different group, otherwise the old membership records would silently adopt
   * the new one.
   */
  fun createGroup(name: String, hlc: Hlc, deviceId: Long, now: Long): FollowGroup? {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_GROUP_NAME) return null
    if (groups().any { it.name.equals(trimmed, ignoreCase = true) }) return null
    val group = FollowGroup(
      id = UUID.randomUUID().toString(),
      name = trimmed,
      order = (groups().maxOfOrNull { it.order }?.takeIf { it != Int.MAX_VALUE } ?: -1) + 1,
    )
    val record = SyncRecord(
      key = group.key,
      value = LibraryCodec.fromGroup(group),
      deleted = false,
      hlc = hlc,
      deviceId = deviceId,
      updatedAt = now,
    )
    persist(records().filterNot { it.key == group.key } + record)
    return group
  }

  fun renameGroup(id: String, name: String, hlc: Hlc, deviceId: Long, now: Long): Boolean {
    val trimmed = name.trim()
    if (trimmed.isEmpty() || trimmed.length > MAX_GROUP_NAME) return false
    if (groups().any { it.id != id && it.name.equals(trimmed, ignoreCase = true) }) return false
    val existing = groups().firstOrNull { it.id == id } ?: return false
    val record = SyncRecord(
      key = existing.key,
      value = LibraryCodec.fromGroup(existing.copy(name = trimmed)),
      deleted = false,
      hlc = hlc,
      deviceId = deviceId,
      updatedAt = now,
    )
    persist(records().filterNot { it.key == existing.key } + record)
    return true
  }

  /**
   * Deletes [groupId] together with every membership inside it, in one clock tick.
   *
   * Writing both tombstones at the same HLC keeps the pair atomic: a peer cannot end up with the
   * group gone but its members still pointing at it.
   */
  fun deleteGroup(groupId: String, hlc: Hlc, deviceId: Long, now: Long) {
    val group = groups().firstOrNull { it.id == groupId } ?: return
    val doomedKeys = records().filter { record ->
      !record.deleted && (LibraryKey.decode(record.key) as? LibraryKey.Member)?.groupId == groupId
    }.map { it.key }.toSet()

    val next = records().filterNot { it.key == group.key || it.key in doomedKeys }.toMutableList()
    next += set.tombstone(group.key, hlc, deviceId, now)
    doomedKeys.forEach { next += set.tombstone(it, hlc, deviceId, now) }
    persist(next)
  }

  fun reorderGroups(keys: List<String>, hlc: Hlc, deviceId: Long, now: Long) {
    if (keys.isEmpty()) return
    val positions = keys.withIndex().associate { it.value to it.index }
    persist(
      records().map { record ->
        val key = LibraryKey.decode(record.key) as? LibraryKey.Group
        val index = key?.let { positions[it.id] }
        if (key == null || index == null || record.deleted) record else record.withOrder(index, hlc, deviceId, now)
      },
    )
  }

  /**
   * Makes [targetGroupIds] the exact set of groups [streamer] belongs to.
   *
   * Adding writes a membership record, removing writes its tombstone. Both happen in one clock tick
   * so a peer never observes a half-applied move. An empty set files the room back into 未分类.
   */
  fun setMembership(
    streamer: Streamer,
    targetGroupIds: Set<String>,
    hlc: Hlc,
    deviceId: Long,
    now: Long,
  ) {
    if (set.liveValue(LibraryKey.Room(streamer.platform, streamer.roomId).encode()) == null) return
    val liveGroups = groups().mapTo(HashSet()) { it.id }
    val wanted = targetGroupIds.filterTo(LinkedHashSet()) { it in liveGroups }

    val current = memberRecordsOf(streamer.key)
    val currentIds = current.mapNotNullTo(HashSet()) {
      (LibraryKey.decode(it.key) as? LibraryKey.Member)?.groupId
    }

    val next = records().toMutableList()
    (currentIds - wanted).forEach { groupId ->
      val key = LibraryKey.Member(groupId, streamer.platform, streamer.roomId).encode()
      next.removeAll { it.key == key }
      next += set.tombstone(key, hlc, deviceId, now)
    }
    (wanted - currentIds).forEach { groupId ->
      val key = LibraryKey.Member(groupId, streamer.platform, streamer.roomId).encode()
      next.removeAll { it.key == key }
      next += SyncRecord(
        key = key,
        // Append to the end of that group, so a newly filed room does not jump to the front of a
        // list the user has arranged.
        value = buildJsonObject { put("order", nextOrderInGroup(groupId)) },
        deleted = false,
        hlc = hlc,
        deviceId = deviceId,
        updatedAt = now,
      )
    }
    persist(next)
  }

  /**
   * Files several rooms into [groupIds] at once, adding to whatever they already belong to.
   *
   * One clock tick for the whole batch, so a peer never observes half of a bulk move. Rooms that
   * are already in a target group are left alone rather than re-stamped: re-writing them would
   * push them to the end of that group's order for no reason.
   *
   * Returns how many memberships were actually created.
   */
  fun addRoomsToGroups(
    streamers: List<Streamer>,
    groupIds: Set<String>,
    hlc: Hlc,
    deviceId: Long,
    now: Long,
  ): Int {
    if (streamers.isEmpty() || groupIds.isEmpty()) return 0
    val liveGroups = groups().mapTo(HashSet()) { it.id }
    val wanted = groupIds.filterTo(LinkedHashSet()) { it in liveGroups }
    if (wanted.isEmpty()) return 0

    val next = records().toMutableList()
    var added = 0
    streamers.forEach { streamer ->
      if (set.liveValue(LibraryKey.Room(streamer.platform, streamer.roomId).encode()) == null) {
        return@forEach
      }
      val current = memberRecordsOf(streamer.key)
        .mapNotNullTo(HashSet()) { (LibraryKey.decode(it.key) as? LibraryKey.Member)?.groupId }
      (wanted - current).forEach { groupId ->
        val key = LibraryKey.Member(groupId, streamer.platform, streamer.roomId).encode()
        next.removeAll { it.key == key }
        next += SyncRecord(
          key = key,
          value = buildJsonObject { put("order", nextOrderInGroup(groupId)) },
          deleted = false,
          hlc = hlc,
          deviceId = deviceId,
          updatedAt = now,
        )
        added++
      }
    }
    if (added > 0) persist(next)
    return added
  }

  /** Persists the order of rooms inside one group, leaving the global order untouched. */
  fun reorderWithinGroup(groupId: String, roomKeys: List<String>, hlc: Hlc, deviceId: Long, now: Long) {
    if (roomKeys.isEmpty()) return
    val positions = roomKeys.withIndex().associate { it.value to it.index }
    persist(
      records().map { record ->
        val member = LibraryKey.decode(record.key) as? LibraryKey.Member
        val index = member?.let { positions[Streamer.keyOf(it.platform, it.roomId)] }
        if (member == null || member.groupId != groupId || index == null || record.deleted) {
          record
        } else {
          record.withOrder(index, hlc, deviceId, now)
        }
      },
    )
  }

  /**
   * Pins [category] under the platform tab, or unpins it.
   *
   * Aggregate entries are refused rather than stored as a no-op: 全部直播 is the whole platform,
   * not a partition, so a favourite for it would put a chip in the row that means something
   * different from every chip beside it. Refusing here rather than only in the UI matters because
   * this path is also reached by records arriving from a peer.
   *
   * Returns false when the request was refused, so the caller can explain instead of silently
   * doing nothing.
   */
  fun toggleFavorite(category: Category, hlc: Hlc, deviceId: Long, now: Long): Boolean {
    if (category.isAggregate || category.id == Category.ALL_ID || category.id.isBlank()) return false
    val key = LibraryKey.Favorite(category.platform, category.id).encode()
    val existing = records().firstOrNull { it.key == key }
    val pinned = existing == null || existing.deleted
    if (pinned && favorites().size >= MAX_FAVORITES) return false

    val next = records().filterNot { it.key == key } + if (pinned) {
      SyncRecord(
        key = key,
        value = LibraryCodec.fromCategory(category, order = favorites().size),
        deleted = false,
        hlc = hlc,
        deviceId = deviceId,
        updatedAt = now,
      )
    } else {
      set.tombstone(key, hlc, deviceId, now)
    }
    persist(next)
    return true
  }

  /** Persists the pinned-category order. [keys] are [LibraryKey.Favorite.encode] values. */
  fun reorderFavorites(keys: List<String>, hlc: Hlc, deviceId: Long, now: Long) {
    if (keys.isEmpty()) return
    val positions = keys.withIndex().associate { it.value to it.index }
    persist(
      records().map { record ->
        val favorite = LibraryKey.decode(record.key) as? LibraryKey.Favorite
        val index = favorite?.let { positions[LibraryKey.Favorite(it.platform, it.categoryId).encode()] }
        if (favorite == null || index == null || record.deleted) record else record.withOrder(index, hlc, deviceId, now)
      },
    )
  }

  /** Replaces the whole set, used when a merged remote snapshot is applied. */
  fun replaceAll(records: List<SyncRecord<JsonObject>>) = set.replaceAll(records)

  // ------------------------------------------------------------------ internals

  private fun persist(records: List<SyncRecord<JsonObject>>) = set.replaceAll(records)

  private fun memberRecordsOf(roomKey: String): List<SyncRecord<JsonObject>> = records().filter { record ->
    val member = LibraryKey.decode(record.key) as? LibraryKey.Member ?: return@filter false
    !record.deleted && Streamer.keyOf(member.platform, member.roomId) == roomKey
  }

  /** One past the highest position currently used inside [groupId]. */
  private fun nextOrderInGroup(groupId: String): Int {
    var highest = -1
    for (record in records()) {
      if (record.deleted) continue
      val member = LibraryKey.decode(record.key) as? LibraryKey.Member ?: continue
      if (member.groupId != groupId) continue
      val order = record.value?.get("order")?.jsonPrimitive?.intOrNull ?: continue
      if (order > highest) highest = order
    }
    return highest + 1
  }

  private companion object {
    const val MAX_GROUP_NAME = 20

    /** Above this the row stops being scannable and the payload starts to matter. */
    const val MAX_FAVORITES = 100
  }
}

/** [SyncTarget] for the library scope — followed rooms, their groups and the pinned categories. */
class LibrarySyncTarget(private val store: LibraryStore) : SyncTarget {
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

/**
 * Wires the scopes the app exchanges.
 *
 * Two entries: the library (followed rooms + groups + pinned categories), and the
 * Bilibili/Douyin login state. `SyncScopesTest` pins both halves — the registry exposes exactly
 * these two, and the sync screen names both.
 */
class AppSyncTargets(
  private val libraryStore: LibraryStore,
  private val credentialStore: CredentialStore,
) : com.livehub.core.sync.SyncTargets {
  private val targets = listOf(
    LibrarySyncTarget(libraryStore),
    CredentialSyncTarget(credentialStore),
  ).associateBy { it.scope }

  override fun targetFor(scope: SyncScope): SyncTarget? = targets[scope]

  override fun available(): List<SyncTarget> = targets.values.toList()

  override fun enabledScopes(): Set<SyncScope> = targets.keys
}
