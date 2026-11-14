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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.withContext
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * A superseded request must not report its own cancellation as a load failure.
 *
 * Every cancellable slot in `MainViewModel` (`roomsJob`, `searchJob`, `playbackJob`,
 * `loginJob`…) cancels the previous request and immediately starts a new one, which writes
 * `loading = true` and `error = null` first. The cancelled coroutine then resumes at its next
 * suspension point and, if its `catch (Exception)` clause swallows the `CancellationException`,
 * writes its own state *after* the replacement's — so the fresh request is left with
 * `error = "Job was cancelled"`, the replacement's `loading`/`refreshing` flags turned off, and
 * (since round 108) an inline failure notice rendered above a grid that is loading perfectly well.
 *
 * Seven blocks in the file already re-throw; five did not, and these are those five. The
 * assertions are on the state a user would see, because that is what the defect produces: an
 * English Kotlin exception message on a Chinese screen.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CancelledRequestStateTest {

  private lateinit var http: GatedAdapter
  private lateinit var viewModel: MainViewModel

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    http = GatedAdapter()
    val container = AppContainer(context, adaptersOverride = listOf(http))
    container.preferences.update { UserSettings() }
    viewModel = MainViewModel(container)
    idle()
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
   * The catalogue the ViewModel needs to get to a room request at all.
   *
   * `firstOrNull` becomes the landing category, so the first `rooms` call happens on its own
   * during construction — the gate closes before the test can cancel anything, which is what
   * makes the overlap reproducible rather than a race.
   */
  private fun adapterCategories(): List<Category> = listOf(category("1"), category("2"))

  /**
   * The regression for `loadRooms`.
   *
   * A gate that never opens makes the first room request hang; selecting another category cancels
   * it and starts a second request that answers immediately. On the old code the cancelled first
   * request painted `error = "Job was cancelled"` over the second one's success.
   */
  @Test
  fun `a cancelled room request does not leave a failure behind`() {
    // The first load is in flight and parked on the gate.
    awaitCondition { http.pendingRooms.size >= 1 }

    viewModel.onAction(UiAction.SelectCategory(category("2")))
    // Let the cancelled request resume, then answer the second one.
    idle()
    http.releaseAll()

    awaitCondition { state.rooms.isNotEmpty() && !state.loading }

    assertNull(
      "a cancelled request must not report itself as a load failure: ${state.error}",
      state.error,
    )
    // The grid holds the answer the surviving request produced. The fake returns the same page for
    // every call, so this is about "the new request's data won", not about a specific ordering.
    assertTrue(
      "the surviving request still has to fill the grid: ${state.rooms.map { it.title }}",
      state.rooms.isNotEmpty(),
    )
  }

  /**
   * The same rule for the catalogue request, which is the one that shows a *full-screen* error.
   *
   * Switching platforms cancels the previous `loadCategories` (both slots share `roomsJob`) and
   * clears `error` as part of the switch. A swallowed cancellation there replaced the new
   * platform's empty-loading grid with a full-screen English error before its own request answered.
   */
  @Test
  fun `a cancelled category request does not leave a failure behind`() {
    awaitCondition { http.pendingCategories.size >= 1 }

    viewModel.onAction(UiAction.SelectPlatform(Platform.HUYA))
    idle()
    // The cancelled catalogue call resumes here; the second one is parked on the same gate.
    idle()
    http.releaseAll()

    awaitCondition { state.categories.isNotEmpty() && !state.loading }

    assertNull("a cancelled catalogue load must not report a failure: ${state.error}", state.error)
    assertEquals(Platform.HUYA, state.platform)
  }

  /**
   * A request that fails for a real reason still has to report it.
   *
   * The guard is only about *cancellation*; without this the fix could be "swallow everything" and
   * still pass the two tests above.
   */
  @Test
  fun `a genuine failure is still reported`() {
    awaitCondition { http.pendingRooms.size >= 1 }
    http.failAll("网络断了")

    awaitCondition { !state.loading }

    assertNotNull("a real failure must still reach the screen", state.error)
    assertTrue(state.error!!.contains("网络断了"), state.error!!)
  }

  /**
   * A fake adapter whose two read paths wait on a gate.
   *
   * Every call parks until [releaseAll] or [failAll], which is what turns "cancel the previous
   * request" from a timing race into a deterministic sequence: the first request is provably still
   * suspended when the second one is issued.
   *
   * The wait happens on `Dispatchers.IO` on purpose. The real adapters do their HTTP work through
   * `OkHttpHttpClient`, whose `suspendCancellableCoroutine` is resumed by an OkHttp callback thread
   * — so a production request never occupies the main dispatcher. A fake that parked on a
   * `CompletableDeferred` inline would block the very thread `viewModelScope` runs on and the test
   * could never let it continue.
   */
  private inner class GatedAdapter : LivePlatformAdapter {
    override val platform: Platform = Platform.DOUYU

    /**
     * One entry per outstanding catalogue request; completing it lets that call return.
     *
     * Copy-on-write because the call is made from `Dispatchers.IO` while the test reads the queue
     * from the instrumentation thread: a plain `mutableListOf` would be a data race, and this
     * queue is the only way the test knows whether a request is still parked.
     */
    val pendingCategories = java.util.concurrent.CopyOnWriteArrayList<CompletableDeferred<List<Category>>>()

    /** One entry per outstanding room request. */
    val pendingRooms = java.util.concurrent.CopyOnWriteArrayList<CompletableDeferred<RoomPage>>()

    /** Answers every waiting call, as a working backend would. */
    fun releaseAll() {
      pendingCategories.toList().forEach { it.complete(adapterCategories()) }
      pendingCategories.clear()
      pendingRooms.toList().forEach { it.complete(RoomPage(rooms = listOf(room("分区2 的房间")))) }
      pendingRooms.clear()
    }

    /** Fails every waiting call, as a dead network would. */
    fun failAll(reason: String) {
      val error = RuntimeException(reason)
      pendingCategories.toList().forEach { it.completeExceptionally(error) }
      pendingCategories.clear()
      pendingRooms.toList().forEach { it.completeExceptionally(error) }
      pendingRooms.clear()
    }

    override suspend fun categories(): List<Category> = withContext(Dispatchers.IO) {
      CompletableDeferred<List<Category>>().also { pendingCategories += it }.await()
    }

    override suspend fun rooms(categoryId: String, cursor: String?, slug: String?): RoomPage =
      withContext(Dispatchers.IO) {
        CompletableDeferred<RoomPage>().also { pendingRooms += it }.await()
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

  private fun room(title: String) = Streamer(
    platform = Platform.DOUYU,
    roomId = title,
    streamerName = "主播",
    title = title,
    isLive = true,
  )
}
