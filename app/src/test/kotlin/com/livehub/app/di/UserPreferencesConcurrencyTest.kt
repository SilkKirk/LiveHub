package com.livehub.app.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * `update` has to be a read-modify-write, not a read and a later write.
 *
 * The write queue only ever guaranteed *order*: writes reach the file in the order they were issued.
 * The value each write carries is computed from `cached` on the **caller's** thread, so two callers
 * that read the same base produce two whole-record candidates and the second publication discards
 * the first one's field — in memory and on disk. `UserSettings` is a single flattened record, so
 * "one field lost" is "one user action lost".
 *
 * Both halves of that are reachable in production and neither is hypothetical:
 *
 *  * the UI thread writes (a star tap in `MainViewModel.toggleFollow`, a danmaku slider drag), and
 *  * the sync server's connection threads write (`FollowRecordSet` and `CredentialStore` both
 *    persist from inside `SyncEngine.applyPayload`).
 *
 * A tap that lands during an exchange was therefore able to be erased by the exchange, or the other
 * way round, with no error anywhere.
 *
 * The transforms below sleep for a millisecond on purpose: it widens the window between "read the
 * record" and "publish it" so the race is the normal case rather than a rare interleaving. The
 * assertion is on the *outcome* (every change survives), not on timing, so the test is not flaky —
 * with the lock held across the read-modify-write it passes every time, and without it the changes
 * collapse.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class UserPreferencesConcurrencyTest {

  private val context get() = ApplicationProvider.getApplicationContext<Context>()

  private lateinit var preferences: UserPreferences

  @Before
  fun setUp() {
    preferences = UserPreferences(context)
    preferences.update { UserSettings() }
    preferences.awaitWrites()
    // Warm the cache: the first read is a blocking DataStore read, and it must not happen inside
    // the race.
    preferences.settings
  }

  /**
   * One writer per field, all starting together.
   *
   * These are exactly the fields the UI and the sync engine write through different threads, and
   * each distinct value differs from the record's default — so an update that is lost shows up as
   * "the field went back to its default" rather than as a subtler wrong value.
   */
  @Test
  fun `concurrent writers of different fields all survive`() {
    val writers = listOf<(UserSettings) -> UserSettings>(
      { it.copy(themeMode = "dark") },
      { it.copy(danmakuOpacity = 0.11f) },
      { it.copy(danmakuFontScale = 1.22f) },
      { it.copy(danmakuAreaFraction = 0.33f) },
      { it.copy(landscapeDanmakuFontScale = 1.44f) },
      { it.copy(backgroundRefresh = false) },
      { it.copy(refreshOnWifiOnly = true) },
      { it.copy(autoSync = false) },
    )
    val start = CountDownLatch(1)
    val done = CountDownLatch(writers.size)
    val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

    writers.forEach { transform ->
      Thread {
        try {
          start.await()
          preferences.update {
            // Widen the read-to-publish window. This is the only artificial part of the test.
            Thread.sleep(1)
            transform(it)
          }
        } catch (error: Throwable) {
          failures += error
        } finally {
          done.countDown()
        }
      }.start()
    }

    start.countDown()
    assertTrue("every writer must finish", done.await(10, TimeUnit.SECONDS))
    assertTrue("no writer may throw: $failures", failures.isEmpty())

    val settings = preferences.settings
    assertEquals("themeMode was overwritten by another writer", "dark", settings.themeMode)
    assertEquals("danmakuOpacity was overwritten", 0.11f, settings.danmakuOpacity, 0.0001f)
    assertEquals("danmakuFontScale was overwritten", 1.22f, settings.danmakuFontScale, 0.0001f)
    assertEquals("danmakuAreaFraction was overwritten", 0.33f, settings.danmakuAreaFraction, 0.0001f)
    assertEquals(
      "landscapeDanmakuFontScale was overwritten",
      1.44f,
      settings.landscapeDanmakuFontScale,
      0.0001f,
    )
    assertFalse("backgroundRefresh was overwritten", settings.backgroundRefresh)
    assertTrue("refreshOnWifiOnly was overwritten", settings.refreshOnWifiOnly)
    assertFalse("autoSync was overwritten", settings.autoSync)

    // The file has to agree with the cache: a version that only fixed the in-memory copy would
    // still lose the change on the next launch.
    preferences.awaitWrites()
    val restarted = UserPreferences(context)
    val persisted = restarted.settings
    assertEquals("themeMode did not reach the file", "dark", persisted.themeMode)
    assertEquals("danmakuOpacity did not reach the file", 0.11f, persisted.danmakuOpacity, 0.0001f)
    assertFalse("autoSync did not reach the file", persisted.autoSync)
    assertTrue("refreshOnWifiOnly did not reach the file", persisted.refreshOnWifiOnly)
  }

  /**
   * The pairing secret is resolved once, for every caller.
   *
   * Four call sites reach `selfSecretOrGenerate` and they can overlap: the `/pair` handler runs on
   * a server connection thread, while transport construction, `pairByCode` and the peer sweep run
   * on `Dispatchers.IO`. The rule under test is not "the generator is called once" — losing
   * callers may legitimately generate a candidate — but that **every caller returns the stored
   * value**, because that is the value a peer will be holding.
   *
   * The old shape (`it.copy(selfSecret = generated)`, then `return generated`) failed exactly here:
   * the candidate was generated *between* "read the blank secret" and "publish it", so two callers
   * that overlapped both handed their **own** value to a peer while only one of them reached the
   * file. The peer then presented a secret this device had never stored and got a permanent 403.
   *
   * The generator sleeps for a millisecond for the same reason as the field test above: it sits in
   * the middle of that read-to-publish window, so it makes the overlap the normal case instead of a
   * scheduling accident. Under the fixed shape the generator runs inside the lock, so the sleep
   * only serialises the callers — it cannot deadlock.
   */
  @Test
  fun `concurrent callers all agree on one pairing secret`() {
    val callers = 8
    val start = CountDownLatch(1)
    val done = CountDownLatch(callers)
    val results = java.util.Collections.synchronizedList(mutableListOf<String>())

    repeat(callers) { index ->
      Thread {
        try {
          start.await()
          results += preferences.selfSecretOrGenerate {
            Thread.sleep(1)
            // Distinct per caller, so a clobbered value cannot accidentally match.
            "secret-$index"
          }
        } finally {
          done.countDown()
        }
      }.start()
    }

    start.countDown()
    assertTrue("every caller must finish", done.await(10, TimeUnit.SECONDS))

    assertEquals("every caller must report a secret", callers, results.size)
    assertEquals(
      "every caller must return the same secret, or a peer authenticates against one this device " +
        "never stored: $results",
      1,
      results.distinct().size,
    )
    assertEquals(
      "and it must be the one on disk",
      results.first(),
      preferences.settings.selfSecret,
    )
    assertNotEquals("the secret must not be blank", "", results.first())
  }

  /** An existing secret is never rewritten — that would lock out every already-paired device. */
  @Test
  fun `an existing pairing secret is kept`() {
    preferences.update { it.copy(selfSecret = "already-paired") }
    var generated = false

    val secret = preferences.selfSecretOrGenerate {
      generated = true
      "should-not-be-used"
    }

    assertEquals("already-paired", secret)
    assertFalse("the generator must not run when a secret exists", generated)
    assertEquals("already-paired", preferences.settings.selfSecret)
  }
}
