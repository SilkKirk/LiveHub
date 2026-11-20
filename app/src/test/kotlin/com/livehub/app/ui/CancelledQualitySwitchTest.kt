package com.livehub.app.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livehub.app.di.AppContainer
import com.livehub.app.di.UserSettings
import com.livehub.core.model.Category
import com.livehub.core.model.LiveStatus
import com.livehub.core.model.Platform
import com.livehub.core.model.PlaybackSource
import com.livehub.core.model.RoomPage
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import com.livehub.platform.api.DanmakuEvent
import com.livehub.platform.api.LivePlatformAdapter
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Cancelling a quality switch must take its spinner with it.
 *
 * `selectQuality` raises `switchingQuality` inside the coroutine it is about to run and lowers it in
 * two places only — after a successful re-resolve, and in the `catch` that reports a failure. The
 * third way out is cancellation, and that branch rethrows on purpose ("only the newest switch may
 * report"), so the flag was left up.
 *
 * The user-visible consequence is not a stuck screen but a stuck *panel*, in every room from then
 * on: `PlayerSettings` draws 正在切换清晰度… plus a spinner whenever the flag is set, so after one
 * interrupted switch the settings sheet of every room claimed a switch was in progress and nothing
 * ever finished it.
 *
 * Both cancellers are covered here — closing the player and opening another room — because the two
 * `copy(...)` blocks are separate places in `MainViewModel` and only one of them had a reason to be
 * touched by the switch path.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CancelledQualitySwitchTest {

  private lateinit var adapter: SwitchablePlaybackAdapter
  private lateinit var viewModel: MainViewModel

  private val room = Streamer(
    platform = Platform.DOUYU,
    roomId = "1",
    streamerName = "一条小团团",
    title = "今天继续冲分",
    isLive = true,
  )

  private val other = Streamer(
    platform = Platform.DOUYU,
    roomId = "2",
    streamerName = "旭旭宝宝",
    title = "DNF 深渊日常",
    isLive = true,
  )

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    adapter = SwitchablePlaybackAdapter()
    val container = AppContainer(context, adaptersOverride = listOf(adapter))
    container.preferences.update { UserSettings() }
    viewModel = MainViewModel(container)
    idle()
    awaitCondition { !viewModel.state.value.loading }
  }

  private fun idle() {
    shadowOf(Looper.getMainLooper()).idle()
  }

  private fun awaitCondition(timeoutMillis: Long = 5_000, condition: () -> Boolean) {
    val deadline = System.currentTimeMillis() + timeoutMillis
    while (System.currentTimeMillis() < deadline) {
      idle()
      if (condition()) return
      Thread.sleep(10)
    }
    idle()
    assertTrue("condition not met within ${timeoutMillis}ms", condition())
  }

  private val state: UiState get() = viewModel.state.value

  /**
   * Opens [room] and waits for its variants, which is what makes a quality switch possible at all.
   *
   * The first resolve is deliberately *not* the blocked one: the defect needs a switch that is
   * already in flight when the cancellation arrives.
   */
  private fun openAndWait(room: Streamer) {
    viewModel.onAction(UiAction.OpenRoom(room))
    awaitCondition { state.variants.isNotEmpty() }
  }

  /** Raises the flag for real, so a passing assertion below is about the *clearing*. */
  private fun startInterruptedSwitch() {
    adapter.blockNextPlayback = true
    viewModel.onAction(UiAction.SelectQuality(SECOND_QUALITY))
    awaitCondition { state.switchingQuality }
    assertTrue("the premise: the switch really started", state.switchingQuality)
  }

  @Test
  fun `closing the player during a switch does not leave the flag up`() {
    openAndWait(room)
    startInterruptedSwitch()

    viewModel.onAction(UiAction.ClosePlayer)
    awaitCondition { state.room == null }

    assertFalse(
      "a cancelled switch must not leave a spinner behind",
      state.switchingQuality,
    )
  }

  @Test
  fun `opening another room during a switch does not leave the flag up`() {
    openAndWait(room)
    startInterruptedSwitch()

    adapter.blockNextPlayback = false
    viewModel.onAction(UiAction.OpenRoom(other))
    awaitCondition { state.room?.key == other.key && state.variants.isNotEmpty() }

    assertFalse(
      "the new room must not inherit the previous switch",
      state.switchingQuality,
    )
    assertNull("and it must not inherit a failure either", state.playerError)
  }

  /**
   * A fake adapter that can hold a `playback` call open.
   *
   * `awaitCancellation()` rather than a latch next to a `delay`: the switch has to stay suspended
   * *until it is cancelled*, so that the cancellation is what ends it — a test that released the
   * call first would be testing the success path instead.
   */
  private inner class SwitchablePlaybackAdapter : LivePlatformAdapter {
    override val platform: Platform = Platform.DOUYU

    /** While true, every `playback` call suspends until it is cancelled. */
    @Volatile var blockNextPlayback: Boolean = false

    override suspend fun categories(): List<Category> = listOf(
      Category(platform = Platform.DOUYU, id = "1", name = "网游", parentId = null, parentName = null),
    )

    override suspend fun rooms(categoryId: String, cursor: String?, slug: String?): RoomPage =
      RoomPage(rooms = listOf(room, other))

    override suspend fun search(keyword: String, cursor: String?): RoomPage =
      RoomPage(rooms = emptyList())

    override suspend fun roomDetail(roomId: String): Streamer? = null

    override suspend fun liveStatus(roomId: String): LiveStatus? = null

    override suspend fun playback(
      roomId: String,
      preferredQuality: Int?,
      preferredLine: String?,
    ): PlaybackSource {
      if (blockNextPlayback) awaitCancellation()
      return PlaybackSource(
        room = if (roomId == other.roomId) other else room,
        variants = listOf(
          StreamVariant(
            qualityLabel = "原画",
            url = "https://cdn.example.com/$roomId.flv",
            qualityRank = 0,
            qualityCode = ORIGINAL_QUALITY,
          ),
        ),
      )
    }

    override fun danmaku(roomId: String): Flow<DanmakuEvent> = emptyFlow()
  }

  private companion object {
    /** The quality the room is already playing — see `SwitchablePlaybackAdapter.playback`. */
    const val ORIGINAL_QUALITY = 0

    /** A quality the room does *not* already hold, so the switch has to re-resolve. */
    const val SECOND_QUALITY = 2
  }
}
