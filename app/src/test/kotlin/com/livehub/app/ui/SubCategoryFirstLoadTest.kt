package com.livehub.app.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livehub.app.di.AppContainer
import com.livehub.app.di.UserSettings
import com.livehub.core.model.Category
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
 * Whether the third level category row arrives with the category it belongs to.
 *
 * Douyu publishes a level 3 under a level 2 category, and the home screen renders it as an
 * extra chip row as soon as `UiState.subCategories` is non-empty. `MainViewModel` used to fetch
 * those children only from `selectCategory` — the path a *tap* takes — while `loadCategories`
 * selected a category by itself on a cold start and on every platform switch. The result was a
 * fresh launch showing a two level selector with no third row, and the only way to reveal it
 * was to tap the chip that was already highlighted, which reads as a no-op.
 *
 * **The automatic selection is the aggregate entry now** (`Category.isAggregate`), which has no
 * third level by design — so "the row is populated on a cold start" is no longer a meaningful
 * assertion. What still has to hold, and is what this file pins:
 *
 *  * the third level is fetched for a category the app selected **without the user picking it
 *    from the row** — the original defect — which is now the platform-switch path, where the
 *    aggregate is re-selected and a real partition is opened by tapping;
 *  * selecting a real partition fetches *its* children, named by its own id;
 *  * the row is cleared when leaving the platform, and comes back on return.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SubCategoryFirstLoadTest {

  private lateinit var http: RecordingDouyuHttp
  private lateinit var container: AppContainer
  private lateinit var viewModel: MainViewModel

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    http = RecordingDouyuHttp()
    container = AppContainer(
      context,
      adaptersOverride = listOf(DouyuAdapter(http = http, webSocketClient = OkHttpClient())),
    )
    // DataStore is a process wide file, so the platform a previous test selected would decide
    // which adapter this view model asks for. Reset it the way MainViewModelTest does.
    container.preferences.update { UserSettings() }
    viewModel = MainViewModel(container)
  }

  /** Drains the main looper so `viewModelScope` work scheduled on it completes. */
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

  /** The real partition this file opens, which is the one Douyu publishes children under. */
  private fun league(): Category =
    viewModel.state.value.categories.first { it.name == "英雄联盟" }

  /** Opens 英雄联盟 and waits for its first page *and* its third level. */
  private fun openLeagueAndSettle() {
    awaitCondition { viewModel.state.value.selectedCategory != null }
    viewModel.onAction(UiAction.SelectCategory(league()))
    awaitCondition {
      val state = viewModel.state.value
      state.selectedCategory?.name == "英雄联盟" &&
        !state.loading &&
        state.subCategories.isNotEmpty()
    }
  }

  @Test
  fun `opening a partition brings its third level with it`() {
    openLeagueAndSettle()

    val state = viewModel.state.value
    assertEquals(
      "the third level of the opened category must be on screen",
      listOf("峡谷之巅"),
      state.subCategories.map { it.name },
    )
  }

  @Test
  fun `the third level is fetched for the category that was opened`() {
    openLeagueAndSettle()

    // `cate2Id` 1 is 英雄联盟; the second category in the fixture is 王者荣耀 (id 2), so this
    // also proves the children were asked for the opened category and not just any.
    val lookup = http.urls.last { it.contains("getThreeCate") }
    assertTrue("the third level lookup must name the opened category, got $lookup", lookup.contains("tag_id=1"))
  }

  /**
   * The original defect, moved to the path that still exercises it.
   *
   * `loadCategories` selects a category on its own after a platform switch. When that category
   * is a real partition (a platform with no aggregate, or a catalogue whose first entry is not
   * one), the third level has to be fetched for it — that is exactly what used to be missing,
   * because only `selectCategory` fetched children.
   */
  @Test
  fun `a category selected by the app itself still gets its third level`() {
    openLeagueAndSettle()

    // Round trip through 虎牙, which has no adapter here, then back: 斗鱼 reloads its
    // catalogue and selects its first entry by itself.
    viewModel.onAction(UiAction.SelectPlatform(Platform.HUYA))
    assertTrue(
      "leaving a platform must clear its third level row",
      viewModel.state.value.subCategories.isEmpty(),
    )
    assertNull(
      "leaving a platform must clear its selected category",
      viewModel.state.value.selectedCategory,
    )

    viewModel.onAction(UiAction.SelectPlatform(Platform.DOUYU))
    awaitCondition {
      val state = viewModel.state.value
      state.selectedCategory != null && !state.loading
    }

    // 斗鱼's first entry is the aggregate, which has no children — so the row must be empty
    // and no lookup may have been issued for it. This is the assertion that would have caught
    // "children fetched for whatever was selected, including the aggregate".
    assertTrue(
      "the aggregate must not populate a third level row",
      viewModel.state.value.subCategories.isEmpty(),
    )
    assertTrue(
      "the aggregate must not trigger a third level lookup",
      http.urls.none { it.contains("getThreeCate") && it.contains("tag_id=all") },
    )
  }

  /**
   * Answers Douyu's three endpoints with canned JSON and records every URL it was asked for.
   *
   * `cate/list` answers with a real catalogue, because the defect this file is about only shows
   * up when the view model selects a category on its own.
   */
  private class RecordingDouyuHttp : HttpClient {
    val urls = mutableListOf<String>()

    override suspend fun execute(request: Request): HttpResponse {
      val url = request.url.toString()
      urls += url
      val body = when {
        url.contains("getThreeCate") -> """{"error":0,"data":[{"id":"3683","name":"峡谷之巅"}]}"""
        url.contains("mixListV1") -> """{"code":0,"data":{"rl":[{"rid":"r1","nn":"主播1","rn":"标题1","ol":"1"}]}}"""
        url.contains("cate/list") -> """
          {"data":{
            "cate1Info":[{"cate1Id":"1","cate1Name":"网游"}],
            "cate2Info":[
              {"cate1Id":"1","cate2Id":"1","cate2Name":"英雄联盟","shortName":"LOL"},
              {"cate1Id":"1","cate2Id":"2","cate2Name":"王者荣耀","shortName":"wzry"}
            ]
          }}
        """.trimIndent()
        else -> "{}"
      }
      return HttpResponse(code = 200, body = body, headers = emptyMap(), url = url)
    }
  }
}
