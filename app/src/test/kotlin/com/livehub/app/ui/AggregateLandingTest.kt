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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Where the home screen lands, and how it leaves.
 *
 * Every platform's adapter leads its category row with an aggregate entry
 * (`Category.isAggregate`, id `Category.ALL_ID`) — 斗鱼's `mixListV1/0_0` cross-group feed,
 * 虎牙's `getLiveList?iGid=0`, 哔哩哔哩's hot tab — and the app **opens on it**.
 *
 * That is the whole point of the entry: a cold start used to land on the first real partition
 * (`categories.firstOrNull()`), so 斗鱼 / 虎牙 / 哔哩哔哩 all opened on 英雄联盟 and the user had
 * to pick one of 400+ partitions before seeing any live room. The two directions are pinned
 * separately because they fail differently:
 *
 *  * the landing must request the **cross-group** feed (`0_0`), which is only observable in the
 *    outgoing URL — a state-only assertion would pass for a selection that loads nothing;
 *  * tapping a real partition must leave the aggregate behind and load that partition instead,
 *    or "全部直播" would become a one-way door.
 *
 * The aggregate belongs to no group and publishes no third level, so the third chip row must
 * stay empty while it is selected — see `the aggregate carries no third level row`.
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
  fun `a cold start lands on the aggregate`() {
    awaitCondition {
      val state = viewModel.state.value
      state.selectedCategory != null && !state.loading
    }

    val state = viewModel.state.value
    assertEquals(Category.ALL_ID, state.categories.first().id)
    assertTrue(state.categories.first().isAggregate)
    assertTrue(
      "a cold start must open on 全部直播, got ${state.selectedCategory!!.name}",
      state.selectedCategory!!.isAggregate,
    )
    // The cross-group feed, not the first partition's.
    assertTrue("got $lastRoomsUrl", lastRoomsUrl.contains("/mixListV1/0_0/"))
  }

  @Test
  fun `opening a real partition leaves the aggregate behind`() {
    awaitCondition { viewModel.state.value.selectedCategory != null }
    val league = viewModel.state.value.categories.first { it.name == "英雄联盟" }

    viewModel.onAction(UiAction.SelectCategory(league))
    awaitCondition { http.urls.any { it.contains("/mixListV1/2_1/") } }

    assertTrue("got $lastRoomsUrl", lastRoomsUrl.contains("/mixListV1/2_1/"))
    assertEquals(league.key, viewModel.state.value.selectedCategory?.key)
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
    // The landing state itself: the aggregate is selected and the row must already be empty,
    // without the user having to open and close a partition first.
    assertTrue(
      "the aggregate has no third level, got ${viewModel.state.value.subCategories.map { it.name }}",
      viewModel.state.value.subCategories.isEmpty(),
    )
    assertNull(viewModel.state.value.selectedSubCategory)
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
