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
import com.livehub.platform.api.PlatformProtocolException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * One keyword, four platforms.
 *
 * The app's headline is that it is an aggregator, and yet comparing the same streamer on 斗鱼 and
 * 虎牙 meant switching the chip and retyping the keyword four times. `SelectSearchPlatform(null)`
 * now means "all of them", and the two properties that matter are that the answers are grouped by
 * platform (the list is not interleaved) and that **a platform failing is not a failing search** —
 * 抖音 refuses keyword search to anonymous callers, and that has to sit next to the three answers
 * instead of emptying the page.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SearchAllPlatformsTest {

  private lateinit var viewModel: MainViewModel

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val container = AppContainer(
      context,
      adaptersOverride = listOf(
        fake(Platform.DOUYU, rooms = listOf(room(Platform.DOUYU, "1"))),
        fake(Platform.HUYA, failure = "虎牙超时了"),
        fake(Platform.DOUYIN, failure = "抖音 2483：请先登录"),
        fake(Platform.BILIBILI, rooms = listOf(room(Platform.BILIBILI, "2"))),
      ),
    )
    // DataStore is a process wide file, so the platform a previous test left behind would decide
    // which adapter this view model asks for.
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

  private fun search(keyword: String) {
    viewModel.onAction(UiAction.UpdateQuery(keyword))
    idle()
    viewModel.onAction(UiAction.SubmitSearch)
    awaitCondition { !state.loading && state.lastSearchQuery == keyword }
  }

  @Test
  fun `the answers are grouped by platform and the failures are named`() {
    viewModel.onAction(UiAction.SelectSearchPlatform(null))
    idle()
    assertTrue("choosing 全部 has to reach the state", state.searchAllPlatforms)

    search("团团")

    assertEquals(
      "one platform's rooms after another, in Platform order",
      listOf("douyu:1", "bilibili:2"),
      state.searchResults.map { it.key },
    )
    assertEquals(
      "the platforms that could not answer are named one by one",
      setOf(Platform.HUYA, Platform.DOUYIN),
      state.searchFailures.keys,
    )
    assertEquals("抖音 2483：请先登录", state.searchFailures[Platform.DOUYIN])
    assertFalse("four cursors would be needed; this mode is one page per platform", state.searchHasMore)
  }

  @Test
  fun `picking one platform again leaves the cross platform mode`() {
    viewModel.onAction(UiAction.SelectSearchPlatform(null))
    idle()
    search("团团")
    assertTrue(state.searchFailures.isNotEmpty())

    viewModel.onAction(UiAction.SelectSearchPlatform(Platform.DOUYU))
    awaitCondition { !state.searchAllPlatforms && !state.loading }

    assertEquals(Platform.DOUYU, state.platform)
    assertTrue("one platform answers for itself", state.searchFailures.isEmpty())
    assertEquals(listOf("douyu:1"), state.searchResults.map { it.key })
  }

  @Test
  fun `choosing 全部 twice does not search twice`() {
    viewModel.onAction(UiAction.SelectSearchPlatform(null))
    idle()
    search("团团")
    val keys = state.searchResults.map { it.key }

    // The chip is already selected; a second tap must not restart anything.
    viewModel.onAction(UiAction.SelectSearchPlatform(null))
    idle()

    assertEquals(keys, state.searchResults.map { it.key })
    assertEquals("团团", state.lastSearchQuery)
  }

  private fun room(platform: Platform, roomId: String) = Streamer(
    platform = platform,
    roomId = roomId,
    streamerName = "团团",
    title = "$platform 的直播间",
    isLive = true,
  )

  private fun fake(
    platform: Platform,
    rooms: List<Streamer> = emptyList(),
    failure: String? = null,
  ): LivePlatformAdapter = object : LivePlatformAdapter {
    override val platform: Platform = platform

    override suspend fun categories(): List<Category> = emptyList()

    override suspend fun rooms(categoryId: String, cursor: String?, slug: String?): RoomPage =
      RoomPage(rooms = emptyList())

    override suspend fun search(keyword: String, cursor: String?): RoomPage {
      failure?.let { throw PlatformProtocolException(it, platform) }
      return RoomPage(rooms = rooms)
    }

    override suspend fun roomDetail(roomId: String): Streamer? = null

    override suspend fun liveStatus(roomId: String): LiveStatus? = null

    override suspend fun playback(
      roomId: String,
      preferredQuality: Int?,
      preferredLine: String?,
    ): PlaybackSource = throw PlatformNotFoundException("没有这个房间", platform)

    override fun danmaku(roomId: String): Flow<DanmakuEvent> = emptyFlow()
  }
}
