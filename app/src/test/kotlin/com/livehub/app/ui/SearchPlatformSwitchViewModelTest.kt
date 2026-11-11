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
import com.livehub.platform.huya.HuyaAdapter
import com.livehub.platform.api.AssetReader
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
 * Switching platform from the search screen must stay on it and re-run the keyword.
 *
 * `SelectPlatform` — the home screen's switcher — resets the category tree and the room grid and
 * sets `screen = Screen.Home`. That is right on the home tab and wrong on search: it would throw
 * the user back to 首页 and erase what they typed. So the search screen needed its own action, and
 * these tests pin the three properties that make it worth having:
 *
 *  * the screen does not change,
 *  * the keyword is still the one in the field,
 *  * a search for it is actually issued against the new platform.
 *
 * Two adapters are installed so a real switch has somewhere to go; the recorded URLs are what
 * identify which platform was asked, because `UiState.platform` alone cannot prove the request
 * went anywhere.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SearchPlatformSwitchViewModelTest {

  private lateinit var http: RecordingPlatformHttp
  private lateinit var viewModel: MainViewModel

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    http = RecordingPlatformHttp()
    val container = AppContainer(
      context,
      adaptersOverride = listOf(
        DouyuAdapter(http = http, webSocketClient = OkHttpClient()),
        HuyaAdapter(http = http, webSocketClient = OkHttpClient(), assets = AssetReader.Empty),
      ),
    )
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

  /** Every URL the adapters requested, in order. */
  private val urls: List<String> get() = http.urls

  private fun submit(keyword: String) {
    viewModel.onAction(UiAction.UpdateQuery(keyword))
    idle()
    val before = urls.size
    viewModel.onAction(UiAction.SubmitSearch)
    awaitCondition {
      urls.size > before && !viewModel.state.value.loading && !viewModel.state.value.refreshing
    }
  }

  /** The core requirement: the user stays on the search screen. */
  @Test
  fun `switching platform on the search screen does not navigate home`() {
    submit("英雄联盟")
    assertEquals(Screen.Search, viewModel.state.value.screen)

    viewModel.onAction(UiAction.SelectSearchPlatform(Platform.HUYA))
    idle()

    assertEquals(
      "a platform switch from search must stay on search",
      Screen.Search,
      viewModel.state.value.screen,
    )
    assertEquals(Platform.HUYA, viewModel.state.value.platform)
  }

  /**
   * The keyword is re-searched on the new platform.
   *
   * Without this the switch would leave the user on an empty search screen with their text still
   * in the field and nothing happening, which reads as a broken chip.
   */
  @Test
  fun `the keyword is searched again on the new platform`() {
    submit("英雄联盟")
    val douyuRequests = urls.size
    assertTrue("the fixture must have issued a douyu search", douyuRequests > 0)

    viewModel.onAction(UiAction.SelectSearchPlatform(Platform.HUYA))
    awaitCondition { urls.size > douyuRequests && !viewModel.state.value.loading }

    // Huya's search host is distinct, so the URL proves which adapter served the new search.
    assertTrue(
      "the new search must go to huya, got ${urls.drop(douyuRequests)}",
      urls.drop(douyuRequests).any { it.contains("huya", ignoreCase = true) },
    )
    assertEquals("英雄联盟", viewModel.state.value.lastSearchQuery)
  }

  /** The field keeps the text, so a second switch does not need retyping. */
  @Test
  fun `the query field is not cleared by the switch`() {
    submit("英雄联盟")

    viewModel.onAction(UiAction.SelectSearchPlatform(Platform.HUYA))
    awaitCondition { !viewModel.state.value.loading }

    assertEquals("英雄联盟", viewModel.state.value.searchQuery)
  }

  /**
   * A switch with nothing searched lands quietly on the search screen.
   *
   * `submitSearch` refuses an empty keyword with a snackbar ("请输入要搜索的主播或直播间名称"),
   * which would be a scolding for doing something reasonable.
   */
  @Test
  fun `switching with an empty field does not complain`() {
    viewModel.onAction(UiAction.Navigate(Screen.Search))
    idle()

    viewModel.onAction(UiAction.SelectSearchPlatform(Platform.HUYA))
    idle()

    assertEquals(Platform.HUYA, viewModel.state.value.platform)
    assertEquals(Screen.Search, viewModel.state.value.screen)
    assertNull(
      "an empty switch must not pop a message",
      viewModel.state.value.message,
    )
  }

  /** Picking the platform already selected is a no-op, not a redundant request. */
  @Test
  fun `switching to the same platform does nothing`() {
    submit("英雄联盟")
    val before = urls.size

    viewModel.onAction(UiAction.SelectSearchPlatform(Platform.DOUYU))
    idle()

    assertEquals("no request may be issued for the platform already in use", before, urls.size)
    assertEquals("英雄联盟", viewModel.state.value.lastSearchQuery)
  }

  /**
   * A switch made after the field was cleared still re-runs the keyword the results answer.
   *
   * 清空 clears the field and ends the search, but the results of the previous search stay on
   * screen until something replaces them — so the box can be empty while `lastSearchQuery` still
   * names what the user was looking at. Re-running that keyword is right; re-running *nothing*
   * would pop "请输入要搜索的主播或直播间名称" for tapping a platform chip, and this is the case the
   * first version of `selectSearchPlatform` got wrong (found by the local Python model, not by
   * CI).
   */
  @Test
  fun `a switch after clearing the field re-runs the last searched keyword`() {
    submit("英雄联盟")
    // Clear the box the way the 清空 button does: this ends the search and empties the field.
    viewModel.onAction(UiAction.UpdateQuery(""))
    idle()
    val before = urls.size

    viewModel.onAction(UiAction.SelectSearchPlatform(Platform.HUYA))
    awaitCondition { urls.size > before && !viewModel.state.value.loading }

    assertNull("the switch must not scold the user for an empty field", viewModel.state.value.message)
    assertTrue(
      "the last searched keyword must be re-run on the new platform, got ${urls.drop(before)}",
      urls.drop(before).any { it.contains("huya", ignoreCase = true) },
    )
    assertEquals("英雄联盟", viewModel.state.value.searchQuery)
    assertEquals("英雄联盟", viewModel.state.value.lastSearchQuery)
  }
}

/**
 * Records every URL and answers with an empty result set.
 *
 * Empty is enough here: these tests assert *which* endpoint was asked and whether the screen
 * moved, not what came back. The bodies are shaped so neither adapter throws — an empty category
 * list keeps `init` from selecting a category of its own, so each test starts from exactly the
 * requests it makes.
 */
private class RecordingPlatformHttp : HttpClient {
  private val recorded = java.util.Collections.synchronizedList(mutableListOf<String>())

  val urls: List<String> get() = recorded.toList()

  override suspend fun execute(request: Request): HttpResponse {
    val url = request.url.toString()
    recorded += url
    val body = when {
      // Douyu's search returns `data.rl`; an empty list is "no results".
      url.contains("searchUser", ignoreCase = true) -> """{"error":0,"data":{"rl":[]}}"""
      // Huya's search returns `response.3.docs`.
      url.contains("search", ignoreCase = true) -> """{"response":{"3":{"docs":[],"numFound":0}}}"""
      // Douyu's catalogue, kept empty on purpose.
      url.contains("cate/list", ignoreCase = true) -> """{"data":{"cate1Info":[],"cate2Info":[]}}"""
      // Huya reads its catalogue from assets; an empty document is a valid empty tree.
      url.contains("cache.php", ignoreCase = true) -> """{"data":[]}"""
      else -> "{}"
    }
    return HttpResponse(code = 200, body = body, headers = emptyMap(), url = url)
  }
}
