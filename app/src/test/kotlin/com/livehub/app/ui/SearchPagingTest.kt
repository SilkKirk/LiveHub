package com.livehub.app.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livehub.app.di.AppContainer
import com.livehub.app.di.UserSettings
import com.livehub.core.model.Platform
import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import com.livehub.platform.douyu.DouyuAdapter
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Which keyword the search screen actually searches with.
 *
 * Two defects shared one cause: the view model read the *text field* where it needed the
 * keyword a page had been fetched for. "加载更多" sent the old cursor together with whatever had
 * since been typed, so the appended page belonged to a search the user never made; and a failed
 * second search left the first keyword's rooms on screen, where the search screen's error state
 * (which only renders when there is nothing else to show) could not appear.
 *
 * The assertions are on the outgoing URL, because the state alone cannot tell the two keywords
 * apart: `searchQuery` is the field and `lastSearchQuery` the search, and only the request says
 * which one was sent.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SearchPagingTest {

  private lateinit var http: RecordingSearchHttp
  private lateinit var viewModel: MainViewModel

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    http = RecordingSearchHttp()
    val container = AppContainer(
      context,
      adaptersOverride = listOf(DouyuAdapter(http = http, webSocketClient = OkHttpClient())),
    )
    // DataStore is a process wide file: the platform a previous test left behind decides which
    // adapter this view model asks for. Reset it the way MainViewModelTest does.
    container.preferences.update { UserSettings() }

    viewModel = MainViewModel(container)
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

  /** The keyword each recorded request asked for, decoded back out of the URL. */
  private val searchedKeywords: List<String>
    get() = http.urls.filter { it.contains("searchUser") }.map { url ->
      val raw = url.substringAfter("kw=").substringBefore("&")
      java.net.URLDecoder.decode(raw, "UTF-8")
    }

  private fun submit(keyword: String) {
    val before = searchedKeywords.size
    viewModel.onAction(UiAction.UpdateQuery(keyword))
    idle()
    viewModel.onAction(UiAction.SubmitSearch)
    // Waiting on the request as well as on the flags: the coroutine may not have started when
    // the action returns, and "not loading yet" would pass for the wrong reason.
    awaitCondition {
      searchedKeywords.size > before && !viewModel.state.value.loading &&
        !viewModel.state.value.refreshing
    }
  }

  @Test
  fun `the next page is requested for the keyword that was searched`() {
    submit("英雄联盟")
    assertEquals(listOf("英雄联盟"), searchedKeywords)
    assertTrue("the fixture must offer a second page", viewModel.state.value.searchHasMore)

    // The user edits the field without submitting: the results on screen still answer 英雄联盟.
    viewModel.onAction(UiAction.UpdateQuery("户外"))
    idle()
    viewModel.onAction(UiAction.LoadMoreSearch)

    awaitCondition { searchedKeywords.size == 2 }
    assertEquals(
      "the second page must repeat the searched keyword, not the edited text",
      listOf("英雄联盟", "英雄联盟"),
      searchedKeywords,
    )
  }

  @Test
  fun `editing the field does not rewrite the keyword the results belong to`() {
    submit("英雄联盟")

    viewModel.onAction(UiAction.UpdateQuery("户外"))
    idle()

    assertEquals("户外", viewModel.state.value.searchQuery)
    assertEquals(
      "the results still answer the keyword that was searched",
      "英雄联盟",
      viewModel.state.value.lastSearchQuery,
    )
  }

  /**
   * A failed search drops the previous keyword's results.
   *
   * They were kept, so the screen showed them as if they answered the new keyword — and the
   * error state never appeared, because it only renders when there is nothing else to show.
   */
  @Test
  fun `a failed search clears the previous results and reports the failure`() {
    submit("英雄联盟")
    assertTrue("the fixture must return rooms", viewModel.state.value.searchResults.isNotEmpty())

    http.failSearch = true
    submit("户外")

    val state = viewModel.state.value
    assertTrue("the stale results must go: ${state.searchResults.size} left", state.searchResults.isEmpty())
    assertTrue("no further page can follow a failed page", !state.searchHasMore)
    assertEquals("户外", state.lastSearchQuery)
    assertTrue(
      "the platform's own message has to reach the screen: ${state.error}",
      state.error?.contains("网页而不是数据") == true,
    )
  }

  /** Re-running the same keyword keeps the list, so a pull-to-refresh does not blank the page. */
  @Test
  fun `re-running the same keyword keeps the results on screen`() {
    submit("英雄联盟")
    val rooms = viewModel.state.value.searchResults
    assertTrue("the fixture must return rooms", rooms.isNotEmpty())

    submit("英雄联盟")

    assertEquals(rooms.map { it.key }, viewModel.state.value.searchResults.map { it.key })
    assertNull("a successful refresh leaves no error behind", viewModel.state.value.error)
  }

  /**
   * Emptying the field ends the search.
   *
   * The 清空 button in the "没有找到" state only cleared the text, so the keyword that had been
   * searched stayed behind and the page went on answering it with an empty field.
   */
  @Test
  fun `clearing the field ends the search that was on screen`() {
    submit("英雄联盟")
    assertTrue(viewModel.state.value.searchResults.isNotEmpty())

    viewModel.onAction(UiAction.UpdateQuery(""))
    idle()

    assertEquals("", viewModel.state.value.searchQuery)
    assertEquals("", viewModel.state.value.lastSearchQuery)
    assertTrue("the previous keyword's results must go", viewModel.state.value.searchResults.isEmpty())
    assertTrue("nothing may be left loading", !viewModel.state.value.loading)
  }

  /** Guards the platform the tests assume; a leaked preference would silently switch adapters. */
  @Test
  fun `the tests run on douyu`() {
    assertEquals(Platform.DOUYU, viewModel.state.value.platform)
  }

  /**
   * Answers Douyu's endpoints with canned JSON and records every URL it was asked for.
   *
   * The adapter is the real [DouyuAdapter]: the bug was in the keyword the view model chose to
   * send, and the URL is the only place that choice is visible.
   */
  private class RecordingSearchHttp : HttpClient {
    val urls = mutableListOf<String>()

    /**
     * Makes the next search answer with an HTML notice instead of JSON.
     *
     * `DouyuResponseRules` raises `PlatformOfflineException` for an HTML body, and that is
     * deliberately not retried — so a failing request here fails once and immediately, unlike an
     * HTTP error, which would be retried three times with backoff. The body deliberately does
     * *not* carry one of the rule's "room is closed" markers: this is the generic
     * "斗鱼返回了网页而不是数据" path, which is what a search would hit when the endpoint is
     * degraded.
     */
    var failSearch = false

    override suspend fun execute(request: Request): HttpResponse {
      val url = request.url.toString()
      urls += url
      val body = when {
        url.contains("searchUser") -> if (failSearch) HTML_NOTICE else searchJson()
        // An empty catalogue keeps `init` from selecting a category of its own, so every test
        // starts from exactly the searches it makes itself.
        url.contains("cate/list") -> """{"data":{"cate1Info":[],"cate2Info":[]}}"""
        else -> "{}"
      }
      return HttpResponse(code = 200, body = body, headers = emptyMap(), url = url)
    }

    /** Three anchors and `total` 90, so a page always has a successor to ask for. */
    private fun searchJson(): String {
      val rooms = (1..3).joinToString(",") { index ->
        """{"type":1,"anchorInfo":{"rid":"r$index","nickName":"主播$index",""" +
          """"roomName":"房间$index","isLive":1,"avatar":"https://example.com/a$index.jpg"}}"""
      }
      return """{"data":{"total":90,"relateUser":[$rooms]}}"""
    }

    private companion object {
      /** An HTML page where JSON was expected; see `DouyuResponseRules`. */
      const val HTML_NOTICE =
        "<html><body><h1>服务暂时不可用，请稍后再试</h1></body></html>"
    }
  }
}
