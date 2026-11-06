package com.livehub.app.di

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import com.livehub.core.sync.Hlc
import com.livehub.core.sync.SyncCodec
import com.livehub.core.sync.SyncMerge
import com.livehub.core.sync.SyncRecord
import com.livehub.core.sync.TrustedPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The library record set: followed rooms, their groups, the memberships and the pinned
 * categories.
 *
 * The behaviour worth pinning is not "add then read back" — it is the four places the design
 * makes a claim:
 *
 *  1. a room in two groups survives a merge where the two devices each added one group;
 *  2. the background snapshot refresh cannot eat group membership;
 *  3. the global order and a group's internal order are independent;
 *  4. an aggregate category can never become a favourite.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LibraryStoreTest {

  private lateinit var preferences: UserPreferences
  private lateinit var store: LibraryStore

  private var clock = 1_700_000_000_000L

  @Before
  fun setUp() {
    preferences = UserPreferences(ApplicationProvider.getApplicationContext())
    preferences.update { UserSettings() }
    store = LibraryStore(preferences)
  }

  private fun hlc(): Hlc = Hlc(clock++, 0)

  private fun streamer(roomId: String, name: String = "主播$roomId", platform: Platform = Platform.DOUYU) =
    Streamer(
      platform = platform,
      roomId = roomId,
      streamerName = name,
      title = "$name 的直播",
      isLive = true,
      viewerCount = 100,
    )

  private fun category(id: String, name: String = "分区$id", platform: Platform = Platform.DOUYU) =
    Category(platform = platform, id = id, name = name, parentName = "网游", slug = "LOL")

  private fun follow(streamer: Streamer) = store.toggle(streamer, hlc(), 1L, clock)

  // --------------------------------------------------------------------- rooms

  @Test
  fun `following and unfollowing a room round trips`() {
    val room = streamer("1")
    follow(room)
    assertEquals(listOf(room.key), store.followed().map { it.key })
    assertTrue(store.isFollowed(Platform.DOUYU, "1"))

    follow(room)
    assertTrue(store.followed().isEmpty())
    assertFalse(store.isFollowed(Platform.DOUYU, "1"))
  }

  @Test
  fun `a reorder writes the order into the records`() {
    val a = streamer("1")
    val b = streamer("2")
    follow(a)
    follow(b)

    store.reorderRooms(listOf(b.key, a.key), hlc(), 1L, clock)

    assertEquals(listOf(b.key, a.key), store.followed().map { it.key })
  }

  /**
   * The defect this replaces: `updateSnapshot` rebuilt the record from the `Streamer` alone and
   * only carried `order` over, so a background refresh silently dropped everything else it did
   * not know about.
   */
  @Test
  fun `a snapshot refresh keeps group membership`() {
    val room = streamer("1")
    follow(room)
    val group = store.createGroup("游戏", hlc(), 1L, clock)!!
    store.setMembership(room, setOf(group.id), hlc(), 1L, clock)

    store.updateSnapshot(room.copy(isLive = false, viewerCount = 0), hlc(), 1L, clock)

    assertEquals(setOf(group.id), store.groupsOf(room.key))
    assertEquals(1, store.followed().size)
    assertFalse(store.followed().first().isLive)
  }

  @Test
  fun `a snapshot refresh keeps the manual order`() {
    val a = streamer("1")
    val b = streamer("2")
    follow(a)
    follow(b)
    store.reorderRooms(listOf(b.key, a.key), hlc(), 1L, clock)

    store.updateSnapshot(b.copy(viewerCount = 999), hlc(), 1L, clock)

    assertEquals(listOf(b.key, a.key), store.followed().map { it.key })
  }

  @Test
  fun `a snapshot refresh never resurrects an unfollowed room`() {
    val room = streamer("1")
    follow(room)
    follow(room) // unfollow

    store.updateSnapshot(room, hlc(), 1L, clock)

    assertTrue(store.followed().isEmpty())
  }

  // -------------------------------------------------------------------- groups

  @Test
  fun `a group is created with a fresh id and can be renamed`() {
    val group = store.createGroup("游戏", hlc(), 1L, clock)!!
    assertEquals("游戏", group.name)
    assertEquals(listOf(group.id), store.groups().map { it.id })

    assertTrue(store.renameGroup(group.id, "主机游戏", hlc(), 1L, clock))
    assertEquals("主机游戏", store.groups().single().name)
  }

  @Test
  fun `a blank or duplicate group name is refused`() {
    assertNull(store.createGroup("   ", hlc(), 1L, clock))
    store.createGroup("游戏", hlc(), 1L, clock)
    assertNull("duplicate names must be refused", store.createGroup("游戏", hlc(), 1L, clock))
    assertNull("names are matched case-insensitively", store.createGroup("游戏 ", hlc(), 1L, clock))
  }

  /**
   * The id is a UUID rather than a slug of the name, so re-creating 游戏 cannot adopt the members
   * of the deleted one.
   */
  @Test
  fun `re-creating a deleted group produces a different id`() {
    val first = store.createGroup("游戏", hlc(), 1L, clock)!!
    val room = streamer("1")
    follow(room)
    store.setMembership(room, setOf(first.id), hlc(), 1L, clock)
    store.deleteGroup(first.id, hlc(), 1L, clock)

    val second = store.createGroup("游戏", hlc(), 1L, clock)!!

    assertNotEquals(first.id, second.id)
    assertTrue("the new group must start empty", store.groupsOf(room.key).isEmpty())
  }

  @Test
  fun `deleting a group tombstones its memberships too`() {
    val room = streamer("1")
    follow(room)
    val group = store.createGroup("游戏", hlc(), 1L, clock)!!
    store.setMembership(room, setOf(group.id), hlc(), 1L, clock)

    store.deleteGroup(group.id, hlc(), 1L, clock)

    assertTrue(store.groups().isEmpty())
    assertTrue(store.groupsOf(room.key).isEmpty())
    // The room itself is untouched: deleting a group must not unfollow anybody.
    assertEquals(1, store.followed().size)
    // Both tombstones are written, so a peer cannot keep the membership alive.
    val tombstones = store.records().filter { it.deleted }
    assertEquals(2, tombstones.size)
  }

  // --------------------------------------------------------------- memberships

  @Test
  fun `a room can be in several groups at once`() {
    val room = streamer("1")
    follow(room)
    val game = store.createGroup("游戏", hlc(), 1L, clock)!!
    val music = store.createGroup("音乐", hlc(), 1L, clock)!!

    store.setMembership(room, setOf(game.id, music.id), hlc(), 1L, clock)

    assertEquals(setOf(game.id, music.id), store.groupsOf(room.key))
  }

  /**
   * The reason memberships are separate records rather than a list on the room.
   *
   * Phone adds 游戏, tablet adds 音乐, then they merge. With a `groups: List<String>` field this
   * would be one key with two writes and one survivor; with two records both additions live.
   */
  @Test
  fun `concurrent additions of different groups both survive a merge`() {
    val room = streamer("1")
    follow(room)
    val game = store.createGroup("游戏", hlc(), 1L, clock)!!
    val music = store.createGroup("音乐", hlc(), 1L, clock)!!

    // Device A (deviceId 1) files it into 游戏.
    store.setMembership(room, setOf(game.id), Hlc(clock, 0), 1L, clock)
    val fromPhone = store.records()

    // Device B starts from the same base and files it into 音乐 instead.
    val base = fromPhone.filter { record ->
      !record.key.startsWith("member:")
    }
    store.replaceAll(base)
    store.setMembership(room, setOf(music.id), Hlc(clock + 1, 0), 2L, clock + 1)
    val fromTablet = store.records()

    // The merge a peer would run.
    val merged = SyncMerge.merge(fromPhone, fromTablet).merged
    store.replaceAll(merged)

    assertEquals(
      "both additions must survive: they are different keys, not two writes to one",
      setOf(game.id, music.id),
      store.groupsOf(room.key),
    )
  }

  @Test
  fun `clearing memberships files the room back into unfiled`() {
    val room = streamer("1")
    follow(room)
    val group = store.createGroup("游戏", hlc(), 1L, clock)!!
    store.setMembership(room, setOf(group.id), hlc(), 1L, clock)

    store.setMembership(room, emptySet(), hlc(), 1L, clock)

    assertTrue(store.groupsOf(room.key).isEmpty())
  }

  @Test
  fun `unfollowing drops the memberships so re-following lands in unfiled`() {
    val room = streamer("1")
    follow(room)
    val group = store.createGroup("游戏", hlc(), 1L, clock)!!
    store.setMembership(room, setOf(group.id), hlc(), 1L, clock)

    follow(room) // unfollow
    follow(room) // follow again

    assertTrue(store.groupsOf(room.key).isEmpty())
  }

  @Test
  fun `a membership pointing at a deleted group is not reported`() {
    val room = streamer("1")
    follow(room)
    val group = store.createGroup("游戏", hlc(), 1L, clock)!!
    store.setMembership(room, setOf(group.id), hlc(), 1L, clock)

    // Simulate a peer that only delivered the group tombstone.
    val records = store.records().map { record ->
      if (record.key == group.key) {
        SyncRecord<kotlinx.serialization.json.JsonObject>(
          key = record.key,
          value = null,
          deleted = true,
          hlc = Hlc(clock + 5, 0),
          deviceId = 2L,
          updatedAt = clock,
        )
      } else {
        record
      }
    }
    store.replaceAll(records)

    assertTrue(store.groupsOf(room.key).isEmpty())
  }

  // -------------------------------------------------------------------- order

  /**
   * The two ordering axes have to stay independent: arranging rooms inside 游戏 must not disturb
   * the global list, and vice versa.
   */
  @Test
  fun `a group order is independent of the global order`() {
    val a = streamer("1")
    val b = streamer("2")
    val c = streamer("3")
    listOf(a, b, c).forEach { follow(it) }
    store.reorderRooms(listOf(a.key, b.key, c.key), hlc(), 1L, clock)
    val group = store.createGroup("游戏", hlc(), 1L, clock)!!
    store.setMembership(a, setOf(group.id), hlc(), 1L, clock)
    store.setMembership(b, setOf(group.id), hlc(), 1L, clock)

    store.reorderWithinGroup(group.id, listOf(b.key, a.key), hlc(), 1L, clock)

    assertEquals(listOf(b.key, a.key), store.roomsInGroup(group.id))
    assertEquals(
      "the global order must not move",
      listOf(a.key, b.key, c.key),
      store.followed().map { it.key },
    )
  }

  @Test
  fun `a newly filed room is appended to its group`() {
    val a = streamer("1")
    val b = streamer("2")
    follow(a)
    follow(b)
    val group = store.createGroup("游戏", hlc(), 1L, clock)!!
    store.setMembership(a, setOf(group.id), hlc(), 1L, clock)
    store.setMembership(b, setOf(group.id), hlc(), 1L, clock)

    assertEquals(listOf(a.key, b.key), store.roomsInGroup(group.id))
  }

  /**
   * The group row is user-ordered, and the order is a synchronised value like the follow order.
   *
   * Without this the row could only ever be creation order: the sheet draws a drag handle and the
   * action exists, so a missing write here would look like a gesture that does nothing.
   */
  @Test
  fun `groups can be reordered`() {
    val game = store.createGroup("游戏", hlc(), 1L, clock)!!
    val music = store.createGroup("音乐", hlc(), 1L, clock)!!
    val chat = store.createGroup("聊天", hlc(), 1L, clock)!!
    assertEquals(listOf("游戏", "音乐", "聊天"), store.groups().map { it.name })

    store.reorderGroups(listOf(chat.id, game.id, music.id), hlc(), 1L, clock)

    assertEquals(listOf("聊天", "游戏", "音乐"), store.groups().map { it.name })
  }

  @Test
  fun `a group reorder leaves the rooms inside untouched`() {
    val room = streamer("1")
    follow(room)
    val game = store.createGroup("游戏", hlc(), 1L, clock)!!
    val music = store.createGroup("音乐", hlc(), 1L, clock)!!
    store.setMembership(room, setOf(game.id), hlc(), 1L, clock)

    store.reorderGroups(listOf(music.id, game.id), hlc(), 1L, clock)

    assertEquals(setOf(game.id), store.groupsOf(room.key))
  }

  @Test
  fun `a newly created group lands at the end of the row`() {
    store.createGroup("游戏", hlc(), 1L, clock)
    store.createGroup("音乐", hlc(), 1L, clock)

    assertEquals(listOf("游戏", "音乐"), store.groups().map { it.name })
  }

  // ---------------------------------------------------------------- favourites

  @Test
  fun `a favourite keeps the slug the platform needs`() {
    val category = category("2_1", "英雄联盟")
    assertTrue(store.toggleFavorite(category, hlc(), 1L, clock))

    val saved = store.favorites().single()
    assertEquals("LOL", saved.slug)
    assertEquals("2_1", saved.id)
    assertEquals(Platform.DOUYU, saved.platform)
  }

  /**
   * 全部直播 is the whole platform, not a partition. Storing it would put a chip in the row that
   * means something different from every chip beside it.
   */
  @Test
  fun `an aggregate category is never stored`() {
    val aggregate = Category.aggregate(Platform.DOUYU, "全部直播")
    assertFalse(store.toggleFavorite(aggregate, hlc(), 1L, clock))
    assertTrue("nothing may be written, not even a tombstone", store.records().isEmpty())
    assertTrue(store.favorites().isEmpty())
  }

  @Test
  fun `a hand written aggregate favourite is filtered out on read`() {
    // A peer, or a hand-edited payload, can carry anything.
    val forged = SyncRecord(
      key = com.livehub.core.model.LibraryKey.Favorite(Platform.DOUYU, "all").encode(),
      value = kotlinx.serialization.json.buildJsonObject {
        put("id", kotlinx.serialization.json.JsonPrimitive("all"))
        put("name", kotlinx.serialization.json.JsonPrimitive("全部直播"))
        put("isAggregate", kotlinx.serialization.json.JsonPrimitive(true))
      },
      deleted = false,
      hlc = Hlc(clock, 0),
      deviceId = 2L,
      updatedAt = clock,
    )
    store.replaceAll(listOf(forged))

    assertTrue(store.favorites().isEmpty())
  }

  @Test
  fun `unpinning a favourite writes a tombstone`() {
    val category = category("2_1")
    store.toggleFavorite(category, hlc(), 1L, clock)
    store.toggleFavorite(category, hlc(), 1L, clock)

    assertTrue(store.favorites().isEmpty())
    assertTrue(store.records().single().deleted)
  }

  // ----------------------------------------------------------------- migration

  @Test
  fun `version 1 keys are rewritten to the prefixed vocabulary`() {
    // The shape the previous build wrote: a bare `<platform>:<roomId>` key.
    val legacy = SyncCodec.encodeRecords(
      listOf(
        SyncRecord(
          key = "douyu:288016",
          value = kotlinx.serialization.json.buildJsonObject {
            put("platform", kotlinx.serialization.json.JsonPrimitive("douyu"))
            put("roomId", kotlinx.serialization.json.JsonPrimitive("288016"))
            put("streamerName", kotlinx.serialization.json.JsonPrimitive("旧主播"))
          },
          deleted = false,
          hlc = Hlc(clock, 0),
          deviceId = 1L,
          updatedAt = clock,
        ),
      ),
    )
    preferences.update { it.copy(libraryRecords = legacy, recordsVersion = 1) }

    val migrated = LibraryStore(preferences).followed()

    assertEquals(1, migrated.size)
    assertEquals("旧主播", migrated.single().streamerName)
    assertEquals(2, preferences.settings.recordsVersion)
    assertTrue(preferences.settings.libraryRecords.contains("room:douyu:288016"))
  }

  @Test
  fun `a version 2 store is not rewritten again`() {
    val room = streamer("1")
    follow(room)
    val before = preferences.settings.libraryRecords

    val reopened = LibraryStore(preferences)
    reopened.followed()

    assertEquals(before, preferences.settings.libraryRecords)
  }

  // ------------------------------------------------------------------- pruning

  @Test
  fun `a tombstone below every peer watermark is pruned`() {
    val room = streamer("1")
    follow(room)
    follow(room)
    val tombstone = store.records().single()
    val peer = TrustedPeer(
      deviceId = 2L,
      deviceName = "平板",
      sharedSecret = "s",
      pairedAt = 0,
      confirmedHlc = mapOf("follows" to Hlc(tombstone.hlc.physical + 1, 0).encode()),
    )

    val pruned = SyncMerge.prune(store.records(), listOf(peer), com.livehub.core.sync.SyncScope.FOLLOWS)

    assertTrue(pruned.isEmpty())
  }
}
