package com.livehub.app.ui

import android.content.Context
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.livehub.app.di.AppContainer
import com.livehub.app.di.UserSettings
import com.livehub.core.model.Category
import com.livehub.core.network.HttpClient
import com.livehub.core.network.HttpResponse
import com.livehub.platform.douyu.DouyuAdapter
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
 * Where the home screen lands, and how it leaves.
 *
 * Every platform's adapter now leads its category row with an aggregate entry
 * (`Category.isAggregate`, id `Category.ALL_ID`) — 斗鱼's `mixListV1/0_0` cross-group feed,
 * 虎牙's `getLiveList?iGid=0`, 哔哩哔哩's hot tab. The landing rule has to hold in both
 * directions, and neither direction is visible from the adapter tests:
 *
 *  * a cold start must **not** land on it — the aggregate belongs to no group, so the row
 *    would open with no group chip and no third level, which is the state the old
 *    `categories.firstOrNull()` produced on every launch;
 *  * tapping it must request the aggregate feed, which is only observable in the outgoing URL.
 *
 * The third assertion is the one that keeps this honest: an aggregate that is merely *listed*
 * but that the view model refuses to open would pass a state-only test.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AggregateLandingTest {

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
    // DataStore is a process wide file, so the platform a previous test selected would decide
    // which adapter this view model asks for.
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

  private val lastRoomsUrl: String get() = http.urls.last { it.contains("mixListV1") }

  @Test
  fun `a cold start lands on a real category, not the aggregate`() {
    awaitCondition {
      val state = viewModel.state.value
      state.selectedCategory != null && !state.loading
    }

    val state = viewModel.state.value
    assertEquals(Category.ALL_ID, state.categories.first().id)
    assertTrue(state.categories.first().isAggregate)
    assertFalse(
      "landing on the aggregate leaves the row with no group and no third level",
      state.selectedCategory!!.isAggregate,
    )
    assertEquals("英雄联盟", state.selectedCategory!!.name)
    // The landing category's own feed, not the cross-group one.
    assertTrue("got $lastRoomsUrl", lastRoomsUrl.contains("/mixListV1/2_1/"))
  }

  @Test
  fun `opening the aggregate requests the cross group feed`() {
    awaitCondition { viewModel.state.value.selectedCategory != null }
    val aggregate = viewModel.state.value.categories.first { it.isAggregate }

    viewModel.onAction(UiAction.SelectCategory(aggregate))
    awaitCondition { http.urls.any { it.contains("/mixListV1/0_0/") } }

    assertTrue("got $lastRoomsUrl", lastRoomsUrl.contains("/mixListV1/0_0/"))
    assertEquals(aggregate.key, viewModel.state.value.selectedCategory?.key)
  }

  /**
   * The aggregate publishes no third level, so its row must not keep the previous one.
   *
   * `selectCategory` clears `subCategories` before the lookup answers, and the adapter returns
   * an empty list for the aggregate id — but the clearing is what the screen actually renders,
   * so a regression there would show 英雄联盟's 峡谷之巅 under a list spanning every 大区.
   */
  @Test
  fun `the aggregate carries no third level row`() {
    awaitCondition {
      viewModel.state.value.selectedCategory != null && !viewModel.state.value.loading
    }
    awaitCondition { viewModel.state.value.subCategories.isNotEmpty() }

    viewModel.onAction(
      UiAction.SelectCategory(viewModel.state.value.categories.first { it.isAggregate }),
    )
    awaitCondition { http.urls.any { it.contains("/mixListV1/0_0/") } }

    assertTrue(
      "the aggregate has no third level, got ${viewModel.state.value.subCategories.map { it.name }}",
      viewModel.state.value.subCategories.isEmpty(),
    )
  }

  /**
   * Answers Douyu's three endpoints and records every URL it was asked for.
   *
   * `cate/list` carries a real catalogue so the landing rule has something to choose between;
   * `mixListV1` answers three rooms on every page, which is a legal page size for Douyu.
   */
  private class RecordingDouyuHttp : HttpClient {
    val urls = mutableListOf<String>()

    override suspend fun execute(request: Request): HttpResponse {
      val url = request.url.toString()
      urls += url
      val body = when {
        url.contains("getThreeCate") -> """{"error":0,"data":[{"id":"3683","name":"峡谷之巅"}]}"""
        url.contains("mixListV1") -> pageJson()
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

    private fun pageJson(): String {
      val rooms = (1..3).joinToString(",") { index ->
        """{"rid":"r$index","nn":"主播$index","rn":"标题$index","ol":"$index"}"""
      }
      return """{"code":0,"data":{"rl":[$rooms]}}"""
    }
  }
}
