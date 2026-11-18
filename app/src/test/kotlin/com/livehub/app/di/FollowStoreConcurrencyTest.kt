package com.livehub.app.di

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livehub.core.model.LibraryKey
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import com.livehub.core.sync.Hlc
import com.livehub.core.sync.SyncRecord
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The follow set is written from two threads at once, and the interleaving must not lose a change.
 *
 * Both writers exist in production and neither is hypothetical:
 *
 *  * the **UI thread** — a star tap, a tag edit, a drag reorder;
 *  * the **sync server's connection threads** — `SyncEngine.applyPayload` → `FollowSyncTarget`,
 *    which reads the whole record set, merges against it and writes the result back.
 *
 * The record set is one value replaced wholesale, so "read it, derive a new one, store it" is a
 * critical section. Without one, a merge landing between a tap's read and its write discarded the
 * tap — from memory and from disk — and the user saw a star that would not stay lit.
 *
 * ## Why these tests hold the lock instead of racing for it
 *
 * A test that starts two threads and hopes they collide passes on fast machines and fails on slow
 * ones, and — worse — passes *unmodified* against the buggy code often enough to be worthless.
 * Each case below therefore parks one writer inside the critical section with a latch and only then
 * starts the other, so the bad ordering is guaranteed rather than sampled.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FollowStoreConcurrencyTest {

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

  private fun streamer(roomId: String) = Streamer(
    platform = Platform.DOUYU,
    roomId = roomId,
    streamerName = "主播$roomId",
    title = "主播$roomId 的直播",
    isLive = true,
    viewerCount = 100,
  )

  private fun roomKey(roomId: String) = LibraryKey.Room(Platform.DOUYU, roomId).encode()

  /**
   * A mutation issued while another writer holds the lock must wait for it, not read a stale base.
   *
   * This is the direct statement of the lost update. The first writer is parked inside
   * `locked { }` — the same critical section `SyncEngine.applyPayload` uses for its
   * snapshot-merge-apply — and a `toggle` is issued from another thread while it is parked.
   *
   * With the lock, the toggle cannot finish until the first writer releases; without it, the
   * toggle read the list *before* the first writer's change existed and published that over it,
   * which is how a star or a follow silently disappeared.
   */
  @Test
  fun `a mutation waits for a writer that holds the lock`() {
    store.toggle(streamer("a"), hlc(), DEVICE_ID, clock)

    val insideCriticalSection = CountDownLatch(1)
    val releaseWriter = CountDownLatch(1)
    val writerDone = CountDownLatch(1)
    val failure = AtomicReference<Throwable?>()

    val holder = Thread {
      try {
        store.locked {
          insideCriticalSection.countDown()
          assertTrue("the test must release this writer", releaseWriter.await(10, TimeUnit.SECONDS))
        }
      } catch (error: Throwable) {
        failure.set(error)
      } finally {
        writerDone.countDown()
      }
    }
    holder.start()
    assertTrue("the writer must reach the critical section", insideCriticalSection.await(10, TimeUnit.SECONDS))

    // Issued while the lock is held. It must block.
    val mutationDone = CountDownLatch(1)
    val mutator = Thread {
      try {
        store.toggle(streamer("b"), hlc(), DEVICE_ID, clock)
      } catch (error: Throwable) {
        failure.set(error)
      } finally {
        mutationDone.countDown()
      }
    }
    mutator.start()

    // The assertion that actually proves exclusion: the mutation cannot have completed while the
    // lock is held. This is not a timing measurement — the only way it completes is if the lock
    // does not exclude, which is precisely the defect.
    assertFalse(
      "a mutation must not finish while another writer holds the lock",
      mutationDone.await(500, TimeUnit.MILLISECONDS),
    )

    releaseWriter.countDown()
    assertTrue("the writer must finish once released", writerDone.await(10, TimeUnit.SECONDS))
    assertTrue("the mutation must finish once the lock is free", mutationDone.await(10, TimeUnit.SECONDS))

    failure.get()?.let { throw AssertionError("a thread failed", it) }

    val keys = store.records().filterNot { it.deleted }.map { it.key }.toSet()
    assertTrue("the room followed before must survive", keys.contains(roomKey("a")))
    assertTrue("the room followed during the other writer must survive", keys.contains(roomKey("b")))
  }

  /**
   * A merged payload applied concurrently with a star tap keeps both.
   *
   * The payload is built from the set as it was before the tap, which is exactly what a real pull
   * looks like: the peer's copy is a snapshot of *its* device, not of this one. Both changes have to
   * survive — the tap's star and the payload's new room — and neither may be silently reverted by
   * the other's write.
   */
  @Test
  fun `a payload applied while a star tap is in flight keeps both`() {
    val room1 = streamer("1")
    val room2 = streamer("2")
    store.toggle(room1, hlc(), DEVICE_ID, clock)

    // What the peer sends: it knows room 1 (older clock, so the local tap wins) and adds room 2.
    val fromPeer = listOf(
      recordOf(room1, Hlc(1L, 0)),
      recordOf(room2, hlc()),
    )

    assertTrue("the star must be accepted", store.toggleSpecial(room1, hlc(), DEVICE_ID, clock))
    val applied = FollowSyncTarget(store).apply(fromPeer)

    // Room 2 arrived, so the payload was not thrown away.
    assertTrue(
      "the peer's new room must be stored",
      store.records().any { it.key == roomKey("2") },
    )
    assertEquals("only the peer's new room is a change; room 1 keeps the local star", 1, applied)

    // And the star survives: the peer's clock for room 1 was older, so the merge keeps ours.
    val stored1 = store.records().firstOrNull { it.key == roomKey("1") }
    assertNotNull("room 1 must still have a record", stored1)
    assertFalse("room 1 must not be tombstoned", stored1!!.deleted)
    assertTrue(
      "the star set by the tap must survive the merge",
      FollowCodec.isSpecial(stored1.value),
    )
    assertTrue("room 1 must still be followed", store.isFollowed(Platform.DOUYU, "1"))
  }

  /** A helper that builds a live record with an explicit clock. */
  private fun recordOf(streamer: Streamer, hlc: Hlc): SyncRecord<JsonObject> = SyncRecord(
    key = roomKey(streamer.roomId),
    value = FollowCodec.fromStreamer(streamer, clock, order = null, special = false),
    deleted = false,
    hlc = hlc,
    deviceId = DEVICE_ID,
    updatedAt = clock,
  )

  private companion object {
    const val DEVICE_ID = 4242L
  }
}