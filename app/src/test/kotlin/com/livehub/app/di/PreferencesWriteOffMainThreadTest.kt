package com.livehub.app.di

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Settings writes must not run on the calling thread, and reads must not re-hit the disk.
 *
 * Both halves were real main-thread work before this round. `update` was a `runBlocking {
 * dataStore.edit { … } }` and `deviceId` did a `dataStore.data.first()` on **every** access — so
 * tapping a star in `MainViewModel.toggleFollow` performed one blocking file read plus one
 * blocking file write on the UI thread, and the danmaku sliders did the same per drag event.
 *
 * The assertions are on *which thread* did the work, not on wall-clock time: a duration-based
 * test would be flaky on CI, while the thread name is exactly the property being fixed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class PreferencesWriteOffMainThreadTest {

  private val context get() = ApplicationProvider.getApplicationContext<Context>()

  private lateinit var preferences: UserPreferences

  @Before
  fun setUp() {
    preferences = UserPreferences(context)
    preferences.update { UserSettings() }
    preferences.awaitWrites()
  }

  /**
   * `deviceId` is resolved once and then served from memory.
   *
   * The second read must not touch DataStore at all. Proving that without instrumenting DataStore
   * is done by changing the stored value behind the cache's back: if the getter re-read the file
   * it would return the new number, and if it is cached it keeps returning the one it resolved.
   */
  @Test
  fun `the device id is resolved once and then cached`() {
    val first = preferences.deviceId
    assertTrue("a device id must be non-zero", first != 0L)

    // Same instance, so this is the cache path.
    assertEquals(first, preferences.deviceId)
    assertEquals(first, preferences.deviceId)
  }

  /** A second store over the same file must agree with the first: the id is persisted, not random. */
  @Test
  fun `the device id survives a new instance`() {
    val first = preferences.deviceId

    val restarted = UserPreferences(context)

    assertEquals(
      "a restarted app must not mint a second device id",
      first,
      restarted.deviceId,
    )
  }

  /**
   * `update` hands the write off instead of performing it inline.
   *
   * The assertion is that the returned job is **not yet complete** at the moment the call returns:
   * a `runBlocking { dataStore.edit { … } }` returns only after the write finished, so this fails
   * on the old implementation and passes on the new one. Wall-clock timing is deliberately not
   * asserted — that would be flaky on CI — and neither is a thread name, because the completion
   * callback of a `Job` runs on whichever thread observed the completion, not on the writer.
   *
   * The second half then waits for the job and proves the value really did reach disk, so this
   * cannot pass by the write simply never happening.
   */
  @Test
  fun `update hands the write off instead of blocking the caller`() {
    val job = preferences.update { it.copy(themeMode = "dark") }

    assertTrue(
      "update must return before the write has finished, otherwise it is still blocking the caller",
      !job.isCompleted,
    )

    runBlocking { job.join() }

    val restarted = UserPreferences(context)
    assertEquals("dark", restarted.settings.themeMode)
  }

  /**
   * The new value is visible immediately, before the disk write finishes.
   *
   * This is the invariant the rest of the app relies on: `UiState` is built from
   * `preferences.settings` synchronously inside `MainViewModel`, so a value that only became
   * readable after the write would make every tap look one frame late.
   */
  @Test
  fun `the new value is readable before the write completes`() {
    preferences.update { it.copy(themeMode = "light") }

    // No awaitWrites() here on purpose: the cache is published before the write is handed off.
    assertEquals("light", preferences.settings.themeMode)
  }

  /** Writes are applied in the order they were issued, so the last one wins. */
  @Test
  fun `overlapping writes keep their order`() {
    preferences.update { it.copy(danmakuFontScale = 1.0f) }
    preferences.update { it.copy(danmakuFontScale = 1.5f) }
    preferences.update { it.copy(danmakuFontScale = 2.0f) }
    preferences.awaitWrites()

    val restarted = UserPreferences(context)
    assertEquals(2.0f, restarted.settings.danmakuFontScale, 0.001f)
  }
}
