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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Which category id the second and later pages of a room list are requested with.
 *
 * Picking a third level category ("三级分类") narrows the list but keeps the second level
 * selected, so `UiState.selectedCategory` still names the *parent* afterwards. Both
 * [MainViewModel]'s `loadMore` and its `refresh` read that field directly, which meant
 * scrolling inside 三级分类 appended rooms from the whole 二级分类 and a pull-to-refresh
 * threw the third level away — the header still said 峡谷之巅 while the grid filled with
 * rooms from all of 英雄联盟.
 *
 * The assertion is on the outgoing URL rather than on the state, because the state looked
 * correct in both cases: only the request reveals which id was used. That also pins the
 * `mixListV1/3_<id>` endpoint [DouyuAdapter] picks for a `c3:` id, so a future change to the
 * level rule fails here instead of quietly serving the wrong list.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class CategoryPaginationTest {

  private lateinit var http: RecordingDouyuHttp
  private lateinit var viewModel: MainViewModel

  /** The second level category the user opens first: 英雄联盟. */
  private val cate2 = Category(
    platform = Platform.DOUYU,
    id = "1",
    name = "英雄联盟",
    parentId = "1",
    parentName = "网游",
  )

  @Before
  fun setUp() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    http = RecordingDouyuHttp()
    val container = AppContainer(
      context,
      adaptersOverride = listOf(DouyuAdapter(http = http, webSocketClient = OkHttpClient())),
    )
    // DataStore is a process wide file, so the platform a previous test selected would
    // decide which adapter this view model asks for. Reset it the way MainViewModelTest does.
    container.preferences.update { UserSettings() }

    viewModel = MainViewModel(container)
    idle()

    viewModel.onAction(UiAction.SelectCategory(cate2))
    // The third level arrives from its own coroutine after the room list, so the chip
    // `SelectSubCategory` needs does not exist on the first idle.
    awaitCondition {
      val state = viewModel.state.value
      state.subCategories.isNotEmpty() && !state.loading
    }
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

  /** Selects the only third level category and waits for its first page to come back. */
  private fun selectSubCategoryAndSettle() {
    viewModel.onAction(UiAction.SelectSubCategory(viewModel.state.value.subCategories.first()))
    awaitCondition {
      val state = viewModel.state.value
      state.selectedSubCategory != null && !state.loading && state.rooms.isNotEmpty()
    }
  }

  /**
   * The last room-list URL, ignoring the sub-category lookup.
   *
   * The two requests are issued by separate coroutines, so whichever finished last is not
   * necessarily the one under test.
   */
  private val lastRoomsUrl: String get() = http.urls.last { it.contains("mixListV1") }

  private fun roomsRequestCount(): Int = http.urls.count { it.contains("mixListV1") }

  /** Runs [action] and waits until it produced a further room-list request. */
  private fun awaitNewRoomsRequest(action: () -> Unit) {
    val before = roomsRequestCount()
    action()
    awaitCondition { roomsRequestCount() > before }
  }

  @Test
  fun `the third level stays selected once the list is opened`() {
    // The premise of the two tests below: the parent chip keeps its selection so the user
    // can see where they are, and the first page already narrows to the third level.
    selectSubCategoryAndSettle()

    assertTrue(
      "the parent must stay selected, got ${viewModel.state.value.selectedCategory?.name}",
      viewModel.state.value.selectedCategory?.key == cate2.key,
    )
    assertTrue(
      "the first page must already use the third level endpoint, got $lastRoomsUrl",
      lastRoomsUrl.contains("mixListV1/3_3683/1"),
    )
  }

  @Test
  fun `scrolling further stays inside the third level category`() {
    selectSubCategoryAndSettle()

    awaitNewRoomsRequest { viewModel.onAction(UiAction.LoadMore) }

    assertTrue(
      "page two must stay inside 峡谷之巅, got $lastRoomsUrl",
      lastRoomsUrl.contains("mixListV1/3_3683/2"),
    )
  }

  @Test
  fun `refreshing stays inside the third level category`() {
    selectSubCategoryAndSettle()

    awaitNewRoomsRequest { viewModel.onAction(UiAction.Refresh) }

    assertTrue(
      "a refresh must not fall back to the parent category, got $lastRoomsUrl",
      lastRoomsUrl.contains("mixListV1/3_3683/1"),
    )
  }

  /**
   * The 全部 chip that leads the third row.
   *
   * The reference client's `Cate3Chips` starts with a 全部 pill whose `onSelect` passes null,
   * so the level can be cleared in one tap. Our row had no such chip, and the parent chip was
   * rendered unselected while a third level was open, so "show the whole category again" had
   * no visible control at all.
   */
  @Test
  fun `clearing the third level goes back to the parent category`() {
    selectSubCategoryAndSettle()

    awaitNewRoomsRequest { viewModel.onAction(UiAction.SelectSubCategory(null)) }

    assertTrue(
      "clearing the third level must request the parent category, got $lastRoomsUrl",
      lastRoomsUrl.contains("mixListV1/2_1/1"),
    )
    assertNull(
      "the third level chip must be deselected",
      viewModel.state.value.selectedSubCategory,
    )
    assertTrue(
      "the parent must stay selected so the row still says where the list is",
      viewModel.state.value.selectedCategory?.key == cate2.key,
    )
  }

  /**
   * Answers Douyu's three endpoints with canned JSON and records every URL it was asked for.
   *
   * The adapter under test is the real [DouyuAdapter]: the defect was in which category id
   * the view model chose to send, and that id is only observable in the outgoing URL.
   */
  private class RecordingDouyuHttp : HttpClient {
    val urls = mutableListOf<String>()

    override suspend fun execute(request: Request): HttpResponse {
      val url = request.url.toString()
      urls += url
      val body = when {
        url.contains("getThreeCate") -> """{"error":0,"data":[{"id":"3683","name":"峡谷之巅"}]}"""
        url.contains("mixListV1") -> pageJson()
        // An empty catalogue keeps `init` from selecting a category of its own, so every
        // test starts from exactly one `SelectCategory`.
        url.contains("cate/list") -> """{"data":{"cate1Info":[],"cate2Info":[]}}"""
        else -> "{}"
      }
      return HttpResponse(code = 200, body = body, headers = emptyMap(), url = url)
    }

    /**
     * A full page of rooms.
     *
     * `DouyuApi.rooms` reports another page only when it parsed as many rooms as it asked
     * for, so a short page would end the sequence and `LoadMore` would return without ever
     * making a request.
     */
    private fun pageJson(): String {
      val rooms = (1..DouyuAdapter.PAGE_SIZE).joinToString(",") { index ->
        """{"rid":"r$index","nn":"主播$index","rn":"标题$index","ol":"$index"}"""
      }
      return """{"code":0,"data":{"rl":[$rooms]}}"""
    }
  }
}
