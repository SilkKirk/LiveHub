package com.livehub.app.di

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livehub.core.model.LibraryKey
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import com.livehub.core.sync.Hlc
import com.livehub.core.sync.SyncCodec
import com.livehub.core.sync.SyncMerge
import com.livehub.core.sync.SyncRecord
import com.livehub.core.sync.TrustedPeer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The follow record set: followed rooms, their manual order and their tombstones.
 *
 * The behaviour worth pinning is not "add then read back" — it is the places the design makes a
 * claim:
 *
 *  1. a background snapshot refresh keeps the manual order and cannot resurrect a follow;
 *  2. a version-2 record set (which also carried `group:` / `member:` / `fav:` records) loads with
 *     those keys **dropped** and a version-3 line written back;
 *  3. a retired key that arrives from a peer is dropped on apply, not stored and echoed back.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FollowStoreTest {

  private lateinit var preferences: UserPreferences
  private lateinit var store: FollowStore

  private var clock = 1_700_000_000_000L

  @Before
  fun setUp() {
    preferences = UserPreferences(ApplicationProvider.getApplicationContext())
    preferences.update { UserSettings() }
    store = FollowStore(preferences)
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
  fun `unfollowing writes a tombstone rather than dropping the record`() {
    val room = streamer("1")
    follow(room)
    follow(room)

    val record = store.records().single()
    assertEquals(LibraryKey.Room(Platform.DOUYU, "1").encode(), record.key)
    assertTrue("the delete must be an HLC-stamped tombstone", record.deleted)
  }

  /**
   * A re-followed room comes back as a new record, at the end of the list and unstarred.
   *
   * The tombstone carries no payload, so there is nothing to inherit — and that is the semantic we
   * want: unfollowing means "I am done with this room", and a position or a 特别关注 mark the user
   * never re-gave must not silently return.
   *
   * (The test used to assert the opposite. It passed only because it never actually re-followed a
   * room whose record had been tombstoned — the assertion described a behaviour the code never
   * had.)
   */
  @Test
  fun `re-following starts from scratch`() {
    val a = streamer("1")
    val b = streamer("2")
    val c = streamer("3")
    listOf(a, b, c).forEach { follow(it) }
    // Arrange so `a` sits first; re-following must not restore that.
    store.reorder(listOf(a.key, b.key, c.key), hlc(), 1L, clock)
    assertEquals(listOf(a.key, b.key, c.key), store.followed().map { it.key })

    follow(a) // unfollow
    follow(a) // follow again

    assertEquals(
      "a re-followed room is a new record, so it lands at the end",
      listOf(b.key, c.key, a.key),
      store.followed().map { it.key },
    )
    assertTrue("and it is not starred", store.specialKeys().isEmpty())
  }

  @Test
  fun `a reorder writes the order into the records`() {
    val a = streamer("1")
    val b = streamer("2")
    follow(a)
    follow(b)

    store.reorder(listOf(b.key, a.key), hlc(), 1L, clock)

    assertEquals(listOf(b.key, a.key), store.followed().map { it.key })
  }

  @Test
  fun `a snapshot refresh keeps the manual order`() {
    val a = streamer("1")
    val b = streamer("2")
    follow(a)
    follow(b)
    store.reorder(listOf(b.key, a.key), hlc(), 1L, clock)

    store.updateSnapshot(b.copy(viewerCount = 999), hlc(), 1L, clock)

    assertEquals(listOf(b.key, a.key), store.followed().map { it.key })
    assertEquals(999, store.followed().first().viewerCount.toInt())
  }

  @Test
  fun `a snapshot refresh never resurrects an unfollowed room`() {
    val room = streamer("1")
    follow(room)
    follow(room) // unfollow

    store.updateSnapshot(room, hlc(), 1L, clock)

    assertTrue(store.followed().isEmpty())
  }

  @Test
  fun `a snapshot refresh never adds a room that was never followed`() {
    store.updateSnapshot(streamer("404"), hlc(), 1L, clock)

    assertTrue(store.followed().isEmpty())
    assertTrue(store.records().isEmpty())
  }

  /** A peer can be on a different platform, and the two must not collide by room id. */
  @Test
  fun `the same room id on two platforms is two records`() {
    follow(streamer("1", platform = Platform.DOUYU))
    follow(streamer("1", platform = Platform.HUYA))

    assertEquals(2, store.followed().size)
    assertEquals(
      setOf(Platform.DOUYU, Platform.HUYA),
      store.followed().map { it.platform }.toSet(),
    )
  }

  // ------------------------------------------------------------ the merge contract

  /**
   * The reason the follow list is a record set rather than a list field: two devices must be able
   * to add different rooms at the same time without one wiping the other.
   */
  @Test
  fun `a follow added on two devices survives a merge`() {
    follow(streamer("1"))
    val fromPhone = store.records()

    store.replaceAll(emptyList())
    follow(streamer("2"))
    val fromTablet = store.records()

    store.replaceAll(SyncMerge.merge(fromPhone, fromTablet).merged)

    assertEquals(
      setOf(LibraryKey.Room(Platform.DOUYU, "1").encode(), LibraryKey.Room(Platform.DOUYU, "2").encode()),
      store.records().map { it.key }.toSet(),
    )
  }

  // ----------------------------------------------------------------- metadata

  @Test
  fun `every streamer field survives the round trip`() {
    val room = Streamer(
      platform = Platform.BILIBILI,
      roomId = "20",
      streamerName = "某幻君",
      title = "今晚八点档",
      coverUrl = "https://example.invalid/cover.jpg",
      avatarUrl = "https://example.invalid/avatar.jpg",
      viewerCount = 45_000,
      isLive = false,
      categoryName = "单机游戏",
      updatedAt = 1_700_000_000_500,
    )
    follow(room)

    assertEquals(room.copy(updatedAt = 1_700_000_000_500), store.followed().single())
  }

  // ------------------------------------------------------- 特别关注 (special) ---

  @Test
  fun `a room can be starred and unstarred`() {
    val room = streamer("1")
    follow(room)
    assertTrue(store.specialKeys().isEmpty())

    assertTrue(store.toggleSpecial(room, hlc(), 1L, clock))
    assertEquals(listOf(room.key), store.specialKeys())

    assertTrue(store.toggleSpecial(room, hlc(), 1L, clock))
    assertTrue(store.specialKeys().isEmpty())
  }

  /**
   * The flag lives on the room's own record, so the two things that rewrite that record — the
   * background refresh and a follow/unfollow round trip — both have to carry it over. Losing it
   * here is invisible in a screenshot: the avatar row would just quietly be empty again.
   */
  @Test
  fun `a snapshot refresh keeps the special mark`() {
    val room = streamer("1")
    follow(room)
    store.toggleSpecial(room, hlc(), 1L, clock)

    store.updateSnapshot(room.copy(viewerCount = 999, isLive = false), hlc(), 1L, clock)

    assertEquals(listOf(room.key), store.specialKeys())
    assertEquals(999, store.followed().single().viewerCount.toInt())
  }

  /**
   * Unfollowing clears the 特别关注 mark, and re-following does **not** bring it back.
   *
   * The tombstone has no payload, so there is nothing to inherit from — and inheriting would be
   * wrong anyway: the user chose to stop following, so a mark they never re-gave must not
   * reappear. This is the case the first version of this feature got wrong in a comment (it
   * claimed the opposite) while the code already behaved correctly.
   */
  @Test
  fun `unfollowing clears the special mark for good`() {
    val room = streamer("1")
    follow(room)
    store.toggleSpecial(room, hlc(), 1L, clock)
    assertEquals(listOf(room.key), store.specialKeys())

    follow(room) // unfollow

    assertTrue(store.specialKeys().isEmpty())

    follow(room) // follow again
    assertTrue("a re-followed room must not silently return to the avatar row", store.specialKeys().isEmpty())
  }

  @Test
  fun `starring a room that is not followed writes nothing`() {
    assertFalse(store.toggleSpecial(streamer("404"), hlc(), 1L, clock))
    assertTrue(store.records().isEmpty())
  }

  /** The avatar row is ordered by the same manual arrangement the grid falls back to. */
  @Test
  fun `special keys follow the stored order`() {
    val a = streamer("1")
    val b = streamer("2")
    follow(a)
    follow(b)
    store.toggleSpecial(a, hlc(), 1L, clock)
    store.toggleSpecial(b, hlc(), 1L, clock)

    store.reorder(listOf(b.key, a.key), hlc(), 1L, clock)

    assertEquals(listOf(b.key, a.key), store.specialKeys())
  }

  /** A record written before this feature simply has no `special` field, and reads as false. */
  @Test
  fun `a record without the field is not special`() {
    follow(streamer("1"))

    assertTrue(store.specialKeys().isEmpty())
    val record = store.records().single()
    assertNull(record.value?.get("special"))
  }

  // ---------------------------------------------------------------- migration

  @Test
  fun `version 1 keys are rewritten to the prefixed vocabulary`() {
    // The shape the previous build wrote: a bare `<platform>:<roomId>` key.
    val legacy = SyncCodec.encodeRecords(
      listOf(
        SyncRecord(
          key = "douyu:288016",
          value = buildJsonObject {
            put("platform", JsonPrimitive("douyu"))
            put("roomId", JsonPrimitive("288016"))
            put("streamerName", JsonPrimitive("旧主播"))
          },
          deleted = false,
          hlc = Hlc(clock, 0),
          deviceId = 1L,
          updatedAt = clock,
        ),
      ),
    )
    preferences.update { it.copy(followRecords = legacy, recordsVersion = 1) }

    val migrated = FollowStore(preferences).followed()

    assertEquals(1, migrated.size)
    assertEquals("旧主播", migrated.single().streamerName)
    assertEquals(3, preferences.settings.recordsVersion)
    assertTrue(preferences.settings.followRecords.contains("room:douyu:288016"))
  }

  /**
   * Round 50 introduced `group:` / `member:` / `fav:` records for user-created groups and pinned
   * categories. Round 53 removed those features, so version 3 has to *drop* those keys — showing
   * them in the list is impossible, and keeping them would ship dead weight to every peer.
   */
  @Test
  fun `version 2 group membership and favourite records are dropped on load`() {
    val groupId = "6f1c9a2e-7b3d-4e55-9a1c-2d8e4f6a0b71"
    val room = LibraryKey.Room(Platform.DOUYU, "288016").encode()
    val v2 = SyncCodec.encodeRecords(
      listOf(
        SyncRecord(
          key = room,
          value = buildJsonObject {
            put("platform", JsonPrimitive("douyu"))
            put("roomId", JsonPrimitive("288016"))
            put("streamerName", JsonPrimitive("留下的主播"))
          },
          deleted = false,
          hlc = Hlc(clock, 0),
          deviceId = 1L,
          updatedAt = clock,
        ),
        SyncRecord(
          key = "group:$groupId",
          value = buildJsonObject { put("name", JsonPrimitive("游戏")) },
          deleted = false,
          hlc = Hlc(clock, 0),
          deviceId = 1L,
          updatedAt = clock,
        ),
        SyncRecord(
          key = "member:$groupId:douyu:288016",
          value = buildJsonObject { put("order", JsonPrimitive(0)) },
          deleted = false,
          hlc = Hlc(clock, 0),
          deviceId = 1L,
          updatedAt = clock,
        ),
        SyncRecord(
          key = "fav:douyu:2_1",
          value = buildJsonObject { put("name", JsonPrimitive("英雄联盟")) },
          deleted = false,
          hlc = Hlc(clock, 0),
          deviceId = 1L,
          updatedAt = clock,
        ),
      ),
    )
    preferences.update { it.copy(followRecords = v2, recordsVersion = 2) }

    val reopened = FollowStore(preferences)

    // `Streamer.key` is the bare `douyu:288016` identity the UI uses; the *record* key is the
    // prefixed one, so the two assertions below read different maps on purpose.
    assertEquals("only the room may survive", listOf("douyu:288016"), reopened.followed().map { it.key })
    assertEquals(3, preferences.settings.recordsVersion)
    // The dropped keys are gone from disk too, not merely filtered on read: leaving them would
    // mean re-parsing and re-dropping them on every cold start.
    val rewritten = SyncCodec.decodeRecords(preferences.settings.followRecords)
    assertEquals(listOf(room), rewritten.map { it.key })
  }

  /**
   * A paired device that is still on version 2 keeps sending `group:` records. They have to be
   * dropped at apply time, because a store that kept them would echo them straight back.
   */
  @Test
  fun `a retired record kind arriving from a peer is dropped on apply`() {
    follow(streamer("1"))
    val groupRecord = SyncRecord<JsonObject>(
      key = "group:legacy",
      value = buildJsonObject { put("name", JsonPrimitive("旧分组")) },
      deleted = false,
      hlc = Hlc(clock + 10, 0),
      deviceId = 2L,
      updatedAt = clock,
    )

    val applied = FollowSyncTarget(store).apply(store.records() + groupRecord)

    assertEquals("only the room is a change", 0, applied)
    assertTrue(store.records().none { it.key.startsWith("group:") })
  }

  @Test
  fun `a version 3 store is not rewritten again`() {
    val room = streamer("1")
    follow(room)
    val before = preferences.settings.followRecords

    val reopened = FollowStore(preferences)
    reopened.followed()

    assertEquals(before, preferences.settings.followRecords)
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
