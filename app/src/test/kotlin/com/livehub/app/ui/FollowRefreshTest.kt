package com.livehub.app.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livehub.app.di.AppContainer
import com.livehub.app.di.UserSettings
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import com.livehub.platform.douyu.DouyuAdapter
import kotlinx.coroutines.yield
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * What a refresh on the follow screen actually does.
 *
 * Two defects lived here, and both were invisible in the state:
 *
 *  * [MainViewModel]'s `refresh` had one behaviour for every screen — reload the room grid. Pulling
 *    on the follow list therefore spent a request on a list that was not on screen, and because
 *    that request owned the same `refreshing` flag as the follow refresh, its completion switched
 *    the indicator off while dozens of live checks were still running.
 *  * the checks themselves ran as a serial `map`, paying the *sum* of the round trips, and every
 *    visit to the tab started a fresh round of them.
 *
 * So the assertions are on the outgoing traffic and on how many requests overlapped, because both
 * refreshes end with `refreshing == false` and only the traffic says which one ran.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class FollowRefreshTest {

  private lateinit var http: RecordingDouyuHttp
  private lateinit var viewModel: MainViewModel

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    http = RecordingDouyuHttp()
    val container = AppContainer(
      context,
      adaptersOverride = listOf(DouyuAdapter(http = http, webSocketClient = OkHttpClient())),
    )
    // DataStore is a process wide file, so the platform and the follow list a previous test left
    // behind would decide what this one starts with.
    container.preferences.update { UserSettings() }
    container.followStore.replaceAll(emptyList())

    viewModel = MainViewModel(container)
    // Let the catalogue load, select its first partition and fetch that partition's rooms. The
    // selection is what gives `refresh` a category to fall back to — that fallback is exactly what
    // the test has to prove does not happen — and waiting for the room request keeps it out of the
    // "requests issued after the pull" count.
    awaitCondition { viewModel.state.value.selectedCategory != null }
    awaitCondition { roomsRequests() > 0 && !viewModel.state.value.loading }
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

  private fun roomsRequests(): Int = http.urls.count { it.contains("mixListV1") }

  private fun liveChecks(): Int = http.urls.count { it.contains("/betard/") }

  private val state: UiState get() = viewModel.state.value

  private fun room(roomId: String) = Streamer(
    platform = Platform.DOUYU,
    roomId = roomId,
    streamerName = "主播$roomId",
    title = "标题$roomId",
    isLive = false,
  )

  @Test
  fun `a pull on the follow screen re-checks the follows and leaves the home grid alone`() {
    viewModel.onAction(UiAction.ToggleFollow(room("room-1")))
    idle()
    assertEquals(1, state.followed.size)

    viewModel.onAction(UiAction.Navigate(Screen.Follow))
    awaitCondition { liveChecks() > 0 }

    // From here on, the only request allowed is a live check.
    val roomsBefore = roomsRequests()
    val checksBefore = liveChecks()

    viewModel.onAction(UiAction.Refresh)
    awaitCondition { liveChecks() > checksBefore && !state.refreshing }

    assertEquals(
      "a refresh on the follow screen must not fetch the home grid",
      roomsBefore,
      roomsRequests(),
    )
    assertEquals("the followed room must survive the refresh", 1, state.followed.size)
    assertTrue("the refresh has to apply what the platform answered", state.followed.single().isLive)
    assertFalse("the indicator has to end up off", state.refreshing)
  }

  /**
   * The checks run together, not one after another.
   *
   * The serial loop paid the *sum* of the round trips — forty follows meant forty waits end to end,
   * and the list looked frozen while they drained. The fake suspends on `yield()` rather than
   * blocking, so a concurrent refresh has several requests in flight at the same moment while a
   * serial one can never exceed one. The assertion is that peak, not wall-clock time, which would
   * be flaky on CI.
   */
  @Test
  fun `the live checks run together instead of one after another`() {
    repeat(3) { index -> viewModel.onAction(UiAction.ToggleFollow(room("room-$index"))) }
    idle()

    viewModel.onAction(UiAction.Navigate(Screen.Follow))
    awaitCondition { liveChecks() >= 3 }
    idle()

    assertTrue(
      "the checks never overlapped (peak ${http.peakLiveChecks}), so they ran serially",
      http.peakLiveChecks >= 2,
    )
    assertEquals("every followed room is checked", 3, liveChecks())
  }

  /**
   * Re-entering 关注 inside the window reuses the last answer.
   *
   * Every navigation to the tab starts a refresh — including the tap that lands on the tab already
   * shown — so flicking between two tabs used to queue a whole round of live checks per tap. The
   * pull-to-refresh path ignores the window on purpose, which the first test pins.
   */
  @Test
  fun `re-entering the follow tab inside the window reuses the last check`() {
    viewModel.onAction(UiAction.ToggleFollow(room("room-1")))
    idle()

    viewModel.onAction(UiAction.Navigate(Screen.Follow))
    awaitCondition { liveChecks() == 1 }

    viewModel.onAction(UiAction.Navigate(Screen.Home))
    idle()
    viewModel.onAction(UiAction.Navigate(Screen.Follow))
    idle()
    idle()

    assertEquals("a second visit must not re-check every room", 1, liveChecks())
  }

  /**
   * Answers Douyu's four endpoints with canned JSON and records every URL it was asked for.
   *
   * The adapter under test is the real [DouyuAdapter]: which endpoint a screen refreshes is only
   * observable in the traffic, and a fake adapter would have replaced the very thing under test.
   */
  private class RecordingDouyuHttp : HttpClient {
    val urls = mutableListOf<String>()

    /** The most `/betard/` requests that were ever in flight at one moment. */
    var peakLiveChecks = 0
      private set

    private var liveChecksInFlight = 0

    override suspend fun execute(request: Request): HttpResponse {
      val url = request.url.toString()
      urls += url
      val liveCheck = url.contains("/betard/")
      if (liveCheck) {
        liveChecksInFlight += 1
        peakLiveChecks = maxOf(peakLiveChecks, liveChecksInFlight)
      }
      try {
        // Suspending (not blocking) is what lets a sibling request start while this one waits, so
        // the peak above is a property of the caller and not of the fake.
        yield()
        val body = when {
          url.contains("getThreeCate") -> """{"error":0,"data":[]}"""
          url.contains("mixListV1") -> pageJson()
          url.contains("/betard/") -> betardJson()
          url.contains("cate/list") -> catalogueJson()
          else -> "{}"
        }
        return HttpResponse(code = 200, body = body, headers = emptyMap(), url = url)
      } finally {
        if (liveCheck) liveChecksInFlight -= 1
      }
    }

    /** One partition in one group, so the view model selects it on its own. */
    private fun catalogueJson(): String = """
      {"data":{
        "cate1Info":[{"cate1Id":"1","cate1Name":"网游"}],
        "cate2Info":[{"cate2Id":"1","cate2Name":"英雄联盟","cate1Id":"1","shortName":"LOL"}]
      }}
    """.trimIndent()

    private fun pageJson(): String {
      val rooms = (1..3).joinToString(",") { index ->
        """{"rid":"r$index","nn":"主播$index","rn":"标题$index","ol":"$index"}"""
      }
      return """{"code":0,"data":{"rl":[$rooms]}}"""
    }

    /** The minimum `DouyuParser.betard` needs to report a live room with a viewer count. */
    private fun betardJson(): String = """
      {"room":{"room_id":"room-1","room_name":"标题","nickname":"主播",
       "show_status":1,"videoLoop":0,"room_biz_all":{"hot":"1234"},"cate2_name":"英雄联盟"},
       "game":{"tag_name":"英雄联盟"}}
    """.trimIndent()
  }
}
