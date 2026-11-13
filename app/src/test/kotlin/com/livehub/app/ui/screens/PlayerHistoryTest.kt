package com.livehub.app.ui.screens

import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livehub.app.di.AppContainer
import com.livehub.app.di.UserSettings
import com.livehub.app.ui.MainViewModel
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * 最近看过: the rooms the user opened, newest first, kept across restarts.
 *
 * Before this the app remembered nothing about what had been watched. 关注 covers "who I want to
 * keep up with", which is a different question from "the room I had open a minute ago" — that one
 * used to mean searching for it again, or finding it in a category grid.
 *
 * Four properties are pinned here, and the fourth is the one that separates a list that survives a
 * rotation from a list that survives a restart: it builds a **second** [AppContainer] over the same
 * DataStore file, so it reads the record rather than the first instance's cache.
 *
 * The recording happens when the room is *opened*, not when it resolves, so none of these tests
 * needs a working platform: the history is already in the state by the time the network work
 * starts. See `MainViewModel.rememberRecentRoom`.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlayerHistoryTest {

  private lateinit var container: AppContainer
  private lateinit var viewModel: MainViewModel

  @Before
  fun setUp() {
    container = AppContainer(ApplicationProvider.getApplicationContext())
    // DataStore is a process-wide file, so a history recorded by whichever test ran before this
    // one would be read back here. Reset the whole settings record, as `MainViewModelTest` does,
    // and wait for it: the truncation test counts entries, so it must not inherit any.
    container.preferences.update { UserSettings() }
    container.preferences.awaitWrites()
    viewModel = MainViewModel(container)
    idle()
  }

  /** Drains the main looper so `viewModelScope` work scheduled on it completes. */
  private fun idle() {
    shadowOf(Looper.getMainLooper()).idle()
  }

  private val state: UiState get() = viewModel.state.value

  private fun streamer(roomId: String, name: String) = Streamer(
    platform = Platform.DOUYU,
    roomId = roomId,
    streamerName = name,
    title = "${name}的直播",
    isLive = true,
  )

  /** Opens a room the way every screen does, through the one action the UI emits. */
  private fun open(room: Streamer) {
    viewModel.onAction(UiAction.OpenRoom(room))
    idle()
  }

  private fun keys(rooms: List<Streamer>): List<String> = rooms.map { it.key }

  @Test
  fun `the most recently opened room comes first`() {
    open(streamer("1", "第一位"))
    open(streamer("2", "第二位"))
    open(streamer("3", "第三位"))

    assertEquals(
      "the history is newest first, so the room opened last leads",
      listOf("douyu:3", "douyu:2", "douyu:1"),
      keys(state.recentRooms),
    )
  }

  /**
   * Reopening a room is a *move*, not a second entry.
   *
   * The assertion is on the order, not on the length: a list that dropped the duplicate and one
   * that kept it both have a plausible size, and only the order says the room went back to the
   * front — which is what makes the row useful for "the one I had open a moment ago".
   */
  @Test
  fun `reopening a room moves it to the front instead of duplicating it`() {
    open(streamer("1", "第一位"))
    open(streamer("2", "第二位"))
    open(streamer("1", "第一位"))

    assertEquals(
      "a repeated room must be moved to the front, not appended and not left where it was",
      listOf("douyu:1", "douyu:2"),
      keys(state.recentRooms),
    )
  }

  @Test
  fun `at most twelve rooms are kept and they are the newest twelve`() {
    (1..13).forEach { open(streamer("$it", "主播${it}")) }

    assertEquals("the history is capped at 12 rooms", 12, state.recentRooms.size)
    assertEquals(
      "the cap drops the oldest room, not the newest one",
      (13 downTo 2).map { "douyu:$it" },
      keys(state.recentRooms),
    )
  }

  /**
   * The history is on disk, not just in this process.
   *
   * A second [AppContainer] builds its own `UserPreferences`, which reads the DataStore file
   * rather than the first instance's in-memory cache — so this passes only if the write actually
   * landed. `awaitWrites()` is the seam that makes waiting for it possible; production never needs
   * it, but a test that asserts on a *restart* does.
   */
  @Test
  fun `a rebuilt view model still sees the history`() {
    open(streamer("7", "持久化主播"))
    container.preferences.awaitWrites()

    val restarted = AppContainer(ApplicationProvider.getApplicationContext())
    val rebuilt = MainViewModel(restarted)
    idle()

    assertEquals(
      "the history must survive the process that recorded it",
      listOf("douyu:7"),
      keys(rebuilt.state.value.recentRooms),
    )
  }
}
