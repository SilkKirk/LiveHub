package com.livehub.app.di

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import com.livehub.core.sync.Hlc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The user's own labels on a followed room.
 *
 * They live in the room's record — next to `order` and `special` — so they travel with the record
 * through sync instead of needing a scope of their own. That choice has one consequence worth
 * pinning here: **every path that rewrites a record must carry the tags through**, and there are
 * three of them (the 特别关注 toggle, the 30-minute snapshot refresh, and the tag write itself). The
 * `special` flag was lost exactly this way once; a test that only covers "set then read" would not
 * have caught it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FollowTagStoreTest {

  private lateinit var store: FollowStore
  private var clock = 1_700_000_000_000L

  @Before
  fun setUp() {
    val preferences = UserPreferences(ApplicationProvider.getApplicationContext())
    preferences.update { UserSettings() }
    store = FollowStore(preferences)
    store.replaceAll(emptyList())
  }

  private fun hlc(): Hlc = Hlc(clock++, 0)

  private fun streamer(roomId: String, name: String = "主播$roomId") = Streamer(
    platform = Platform.DOUYU,
    roomId = roomId,
    streamerName = name,
    title = "$name 的直播",
    isLive = true,
  )

  private fun follow(room: Streamer) {
    store.toggle(room, hlc(), 7L, clock)
  }

  @Test
  fun `tags are read back per room and listed once`() {
    val first = streamer("1")
    val second = streamer("2")
    follow(first)
    follow(second)

    assertTrue(store.setTags(first, listOf("常看", "英雄联盟"), hlc(), 7L, clock))
    assertTrue(store.setTags(second, listOf("常看"), hlc(), 7L, clock))

    assertEquals(listOf("常看", "英雄联盟"), store.tagsByKey()[first.key])
    assertEquals("every tag in use, once, sorted", listOf("常看", "英雄联盟"), store.allTags())
  }

  /** The star toggle rebuilds the record from a `Streamer`; the tags have to be passed back in. */
  @Test
  fun `starring a tagged room keeps its tags`() {
    val room = streamer("3")
    follow(room)
    store.setTags(room, listOf("常看"), hlc(), 7L, clock)

    assertTrue(store.toggleSpecial(room, hlc(), 7L, clock))

    assertEquals(listOf("常看"), store.tagsByKey()[room.key])
    assertTrue("and the star itself stuck", room.key in store.specialKeys())
  }

  /** The 30-minute refresh rewrites every followed record from the network; same trap. */
  @Test
  fun `a snapshot refresh keeps the tags`() {
    val room = streamer("4")
    follow(room)
    store.setTags(room, listOf("常看"), hlc(), 7L, clock)

    store.updateSnapshot(room.copy(viewerCount = 12_345), hlc(), 7L, clock)

    assertEquals(listOf("常看"), store.tagsByKey()[room.key])
    assertEquals(12_345, store.followed().single().viewerCount.toInt())
  }

  @Test
  fun `an empty list removes the tags and unfollowing drops them`() {
    val room = streamer("5")
    follow(room)
    store.setTags(room, listOf("常看"), hlc(), 7L, clock)

    assertTrue(store.setTags(room, emptyList(), hlc(), 7L, clock))
    assertTrue("removing every tag leaves no entry", store.tagsByKey().isEmpty())

    store.setTags(room, listOf("常看"), hlc(), 7L, clock)
    store.toggle(room, hlc(), 7L, clock)
    assertTrue("the record is a tombstone now", store.tagsByKey().isEmpty())
  }

  /** A room that is not followed has no record to hold labels. */
  @Test
  fun `tagging a room that is not followed is refused`() {
    assertFalse(store.setTags(streamer("6"), listOf("常看"), hlc(), 7L, clock))
    assertTrue(store.tagsByKey().isEmpty())
  }

  /** Tags are trimmed and de-duplicated on the way in, so "常看" and " 常看 " are one tag. */
  @Test
  fun `tags are trimmed and de-duplicated`() {
    val room = streamer("7")
    follow(room)

    store.setTags(room, listOf(" 常看 ", "常看", "", "  "), hlc(), 7L, clock)

    assertEquals(listOf("常看"), store.tagsByKey()[room.key])
  }
}
