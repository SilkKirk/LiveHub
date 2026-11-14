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
import com.livehub.core.model.Streamer
import com.livehub.platform.api.DanmakuEvent
import com.livehub.platform.api.LivePlatformAdapter
import com.livehub.platform.api.PlatformNotFoundException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A request that gets replaced must not report its own cancellation as a load failure.
 *
 * Every cancellable slot in `MainViewModel` cancels the previous request and immediately starts a
 * new one, which writes `loading = true, error = null` first. The cancelled coroutine resumes at its
 * next suspension point *after* that write and, if its `catch (Exception)` swallows the
 * `CancellationException`, overwrites the fresh state with `error = "Job was cancelled"` — an English
 * Kotlin message, and it also switches off the indicators the new request owns. Round 108 added
 * `InlineErrorNotice`, which renders `error` above the grid, so this went from an invisible state
 * wart to text on a Chinese screen.
 *
 * Seven blocks in the file already re-throw; five did not (`loadCategories`, `loadRooms`,
 * `openRoom`, `selectQuality`, `startBilibiliLogin`). This pins the shape they all share: the state
 * a user sees after a request is replaced is the *new* request's, with no failure in it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CancelledRequestStateTest {

  private lateinit var http: SwitchableAdapter
  private lateinit var viewModel: MainViewModel

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    http = SwitchableAdapter()
    val container = AppContainer(context, adaptersOverride = listOf(http))
    container.preferences.update { UserSettings() }
    viewModel = MainViewModel(container)
    idle()
    awaitCondition { viewModel.state.value.rooms.isNotEmpty() && !viewModel.state.value.loading }
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

  private fun category(id: String) = Category(
    platform = Platform.DOUYU,
    id = id,
    name = "分区$id",
    parentId = "1",
    parentName = "网游",
  )

  /**
   * The regression: replacing a load must clear the previous failure, never inherit one.
   *
   * A failed load leaves `error` set (asserted below, so the premise is real). Selecting another
   * category cancels it and starts a fresh request; on the old code the cancelled coroutine wrote
   * `Job was cancelled` over that fresh state, and it was the *only* writer of `error` on this path
   * — the replacement's own successful write does not clear it.
   */
  @Test
  fun `replacing a room load leaves no failure behind`() {
    http.roomsFailures = 1
    viewModel.onAction(UiAction.SelectCategory(category("2")))
    awaitCondition { state.error != null }
    assertTrue("the premise: a failed load does report: ${state.error}", state.error != null)

    // Replace it. The new request succeeds, so the user must see a clean grid.
    http.roomsFailures = 0
    viewModel.onAction(UiAction.SelectCategory(category("3")))
    awaitCondition { !state.loading }

    val error = state.error
    assertNull("the replacement's state must not carry any error: $error", error)
  }

  /**
   * A genuine failure still has to reach the screen, in the platform's words.
   *
   * Without this, "swallow every exception" would pass the test above — the two assertions are a
   * pair, and this one is what makes the fix about *cancellation* rather than about silence.
   */
  @Test
  fun `a genuine failure is still reported`() {
    http.roomsFailures = 1
    viewModel.onAction(UiAction.SelectCategory(category("4")))
    awaitCondition { state.error != null }

    val error = state.error
    assertTrue("a real failure must reach the screen: $error", error != null)
    assertTrue("in the platform's message, not a Kotlin one: $error", error!!.contains("网络断了"))
  }

  /**
   * A fake adapter whose room calls can be told to fail, then work again.
   *
   * `categories()` always answers immediately so `init` gets to the room request under test. The
   * failure arrives as a plain `RuntimeException` from the same dispatcher the real adapters use
   * (OkHttp resumes on its own threads rather than the caller's), which is what the ViewModel's
   * `catch (Exception)` sees in production.
   */
  private inner class SwitchableAdapter : LivePlatformAdapter {
    override val platform: Platform = Platform.DOUYU

    /** How many more room calls should fail; decremented on each failing call. */
    @Volatile var roomsFailures: Int = 0

    override suspend fun categories(): List<Category> =
      listOf(category("1"), category("2"), category("3"), category("4"))

    override suspend fun rooms(categoryId: String, cursor: String?, slug: String?): RoomPage {
      if (roomsFailures > 0) {
        roomsFailures--
        throw RuntimeException("网络断了")
      }
      return RoomPage(
        rooms = listOf(
          Streamer(
            platform = Platform.DOUYU,
            roomId = categoryId,
            streamerName = "主播$categoryId",
            title = "房间$categoryId",
            isLive = true,
          ),
        ),
      )
    }

    override suspend fun search(keyword: String, cursor: String?): RoomPage =
      RoomPage(rooms = emptyList())

    override suspend fun roomDetail(roomId: String): Streamer? = null

    override suspend fun liveStatus(roomId: String): LiveStatus? = null

    override suspend fun playback(
      roomId: String,
      preferredQuality: Int?,
      preferredLine: String?,
    ): PlaybackSource = throw PlatformNotFoundException("没有这个房间", Platform.DOUYU)

    override fun danmaku(roomId: String): Flow<DanmakuEvent> = emptyFlow()
  }
}
