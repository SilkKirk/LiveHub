package com.livehub.core.sync

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The engine's own state is shared by three kinds of thread, and two of its operations are
 * read-modify-write over one field.
 *
 * `nextHlc` and `observeRemote` both read the clock state and write it back, and they are reached
 * from the UI (every follow, unfollow and reorder), from `FollowRefreshWorker`'s thread, and from
 * the sync server's connection threads (`applyPayload` → `observeRemote`). Unguarded, two callers
 * can hand out the same timestamp, and — worse — a merge computed from a stale base can move the
 * clock **backwards**, after which a local edit carries an older clock than the record it is meant
 * to replace and loses every merge. The user sees "I changed it on the phone and the sync did
 * nothing".
 *
 * **How the race is made deterministic.** The clock is an injected `() -> Long`, so the tests make
 * it sleep for a millisecond. Under the lock that sleep happens inside the critical section and
 * only serialises callers; without the lock it sits in the middle of the read-modify-write and
 * guarantees that two callers compute from the same base. That is the same technique the
 * `UserPreferences` concurrency tests use, and it is the difference between a test that reports the
 * defect every time and one that depends on how the scheduler happened to feel.
 *
 * JUnit 5 in this module: assertion messages go **last**.
 */
class SyncEngineConcurrencyTest {

  /** A clock that widens the read-to-write window by a millisecond per call. */
  private fun slowClock(): () -> Long = {
    Thread.sleep(1)
    1_000L
  }

  private fun engine(clock: () -> Long): SyncEngine = SyncEngine(
    targets = MapSyncTargets(emptyMap<SyncScope, SyncTarget>()),
    deviceIdProvider = { 1L },
    deviceNameProvider = { "phone" },
    peersProvider = { emptyList() },
    peersWriter = { },
    clock = clock,
    idGenerator = { "token" },
  )

  /**
   * Every timestamp handed out is distinct.
   *
   * `tick` guarantees that on its own — the second call in the same millisecond increments the
   * counter. Two threads that read the same base before either writes both get the *first* value,
   * so a duplicate here means the read-modify-write was not atomic.
   */
  @Test
  fun `concurrent timestamps are all distinct`() {
    val engine = engine(slowClock())
    val threads = 4
    val perThread = 25
    val start = CountDownLatch(1)
    val done = CountDownLatch(threads)
    val results = java.util.Collections.synchronizedList(mutableListOf<Hlc>())

    repeat(threads) {
      Thread {
        try {
          start.await()
          repeat(perThread) { results += engine.nextHlc() }
        } finally {
          done.countDown()
        }
      }.start()
    }

    start.countDown()
    assertTrue(done.await(30, TimeUnit.SECONDS), "every thread must finish")
    assertEquals(
      threads * perThread,
      results.distinct().size,
      "a duplicated timestamp means two callers read the same base and both incremented it",
    )
  }

  /**
   * Observing a remote clock cannot be undone by a concurrent local mutation.
   *
   * `merge` always ends up at least at the remote's physical time, so the clock is monotonic in the
   * physical component as long as the whole read-merge-write is atomic. A regression here is what
   * makes a local edit look older than the record it is replacing.
   */
  @Test
  fun `a remote clock survives concurrent local ticks`() {
    val engine = engine(slowClock())
    val remote = Hlc(9_000_000, 0)
    val start = CountDownLatch(1)
    val observerDone = CountDownLatch(1)
    val tickerDone = CountDownLatch(1)

    Thread {
      try {
        start.await()
        repeat(20) { engine.observeRemote(remote) }
      } finally {
        observerDone.countDown()
      }
    }.start()

    Thread {
      try {
        start.await()
        repeat(20) { engine.nextHlc() }
      } finally {
        tickerDone.countDown()
      }
    }.start()

    start.countDown()
    assertTrue(observerDone.await(30, TimeUnit.SECONDS), "the observer must finish")
    assertTrue(tickerDone.await(30, TimeUnit.SECONDS), "the ticker must finish")

    val after = engine.nextHlc()
    assertTrue(
      after > remote,
      "the clock went backwards: observed $remote but the next tick returned $after",
    )
  }

  /**
   * The history stays bounded and readable while another thread is writing to it.
   *
   * A smoke test rather than a race detector: an unguarded `log.toList()` over an `ArrayList` that
   * is being prepended to usually returns a shifted or duplicated element instead of throwing, so
   * this cannot prove the lock is needed. What it does pin is the cap and the shape of what the
   * sync screen renders — and it is written so that it always reads at least once, because a reader
   * loop that can run zero times would quietly assert nothing.
   */
  @Test
  fun `the history stays bounded while it is being written`() {
    val engine = engine(slowClock())
    val peer = TrustedPeer(deviceId = 2, deviceName = "tablet", sharedSecret = "s", pairedAt = 0)
    val done = CountDownLatch(1)

    Thread {
      try {
        repeat(300) { index ->
          engine.recordPush(peer, SyncScope.FOLLOWS, itemCount = index, success = true)
          // Give the reader a window to observe the history mid-write.
          if (index % 10 == 0) Thread.sleep(1)
        }
      } finally {
        done.countDown()
      }
    }.start()

    var reads = 0
    do {
      val snapshot = engine.logEntries()
      assertTrue(
        snapshot.size <= SyncEngine.MAX_LOG_ENTRIES,
        "the history must stay capped, was ${snapshot.size}",
      )
      assertTrue(snapshot.all { it.peerName == "tablet" }, "every entry must be readable")
      reads++
    } while (!done.await(1, TimeUnit.MILLISECONDS))

    assertTrue(reads > 0, "the reader must have observed the history")
    assertTrue(
      engine.logEntries().size <= SyncEngine.MAX_LOG_ENTRIES,
      "and it is still capped at the end",
    )
  }
}
