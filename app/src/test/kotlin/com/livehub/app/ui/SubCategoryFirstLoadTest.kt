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
 * Whether the third level category row exists before the user touches anything.
 *
 * Douyu publishes a level 3 under a level 2 category, and the home screen renders it as an
 * extra chip row as soon as `UiState.subCategories` is non-empty. `MainViewModel` selects
 * the first category by itself on a cold start and on every platform switch, and it used to
 * fetch the children only from `selectCategory` — the path a *tap* takes. So a fresh launch
 * showed a two level selector with no third row, and the only way to reveal it was to tap the
 * chip that was already highlighted, which reads as a no-op.
 *
 * The reference client fetches on `LaunchedEffect(selectedCate2?.id)`
 * (`ui/screens/douyu/DouyuHomeScreen.kt`), which runs for the initial selection too.
 *
 * The assertions are on the request log as well as on the state: a row can only be correct if
 * the app actually asked for the children of the category it selected.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SubCategoryFirstLoadTest {

  private lateinit var http: RecordingDouyuHttp
  private lateinit var container: AppContainer

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

  /**
   * Waits for the whole initial load, third level included.
   *
   * The children arrive from their own coroutine, so waiting only for the room list would race
   * them and pass or fail depending on which request finished first. On the old code this never
   * becomes true and the test fails on the timeout with a readable message.
   */
  private fun awaitFirstLoadWithSubCategories(viewModel: MainViewModel) = awaitCondition {
    val state = viewModel.state.value
    state.selectedCategory != null && !state.loading && state.subCategories.isNotEmpty()
  }

  @Test
  fun `the third level row is populated on a cold start`() {
    val viewModel = MainViewModel(container)
    // No UiAction is sent anywhere in this test: the row has to come from the view model's own
    // initial load, which is exactly what used to leave it empty.
    awaitFirstLoadWithSubCategories(viewModel)

    val state = viewModel.state.value
    assertEquals(
      "the first category should be selected without any user action",
      "英雄联盟",
      state.selectedCategory?.name,
    )
    assertEquals(
      "the third level of the selected category must be on screen from the first frame",
      listOf("峡谷之巅"),
      state.subCategories.map { it.name },
    )
  }

  @Test
  fun `the third level is fetched for the category the app selected itself`() {
    val viewModel = MainViewModel(container)
    awaitCondition { http.urls.any { it.contains("getThreeCate") } }

    // `cate2Id` 1 is 英雄联盟; the second category in the fixture is 王者荣耀 (id 2), so this
    // also proves the children were asked for the selected category and not just any.
    val lookup = http.urls.last { it.contains("getThreeCate") }
    assertTrue("the third level lookup must name the selected category, got $lookup", lookup.contains("tag_id=1"))
  }

  @Test
  fun `the third level row comes back after a platform round trip`() {
    val viewModel = MainViewModel(container)
    awaitFirstLoadWithSubCategories(viewModel)

    // No 虎牙 adapter is installed, so this switch cannot load anything. What it must still do
    // is drop the 斗鱼 selection and its third level — `selectPlatform` clears both
    // synchronously — otherwise the next platform would open on a chip from the previous one.
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
    awaitFirstLoadWithSubCategories(viewModel)

    assertEquals(
      "switching back must restore the third level of the newly selected category",
      listOf("峡谷之巅"),
      viewModel.state.value.subCategories.map { it.name },
    )
  }

  /**
   * Answers Douyu's three endpoints with canned JSON and records every URL it was asked for.
   *
   * Unlike `CategoryPaginationTest`, `cate/list` answers with a real catalogue: the defect
   * only shows up when the view model selects a category on its own.
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
