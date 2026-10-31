package com.livehub.app

import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.livehub.app.ui.LiveHubRoot
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.app.ui.components.BiliLoginUi
import com.livehub.core.model.Category
import com.livehub.core.model.DanmakuMessage
import com.livehub.core.model.Platform
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import com.livehub.core.sync.SyncLogEntry
import com.livehub.core.sync.TrustedPeer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Renders every screen with representative data and writes a PNG for each.
 *
 * The APK cannot be launched interactively in CI, so these renders are the visual
 * regression harness: each run produces a folder of screenshots that a reviewer opens
 * and compares. They also fail the build if a screen throws while composing, which
 * catches layout crashes a plain unit test cannot see.
 *
 * One test per screen is required because a Compose test rule may only call
 * `setContent` once.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  private val outputDir: File by lazy {
    File(context.getExternalFilesDir(null), "screenshots").apply { mkdirs() }
  }

  // ---------------------------------------------------------------- fake data ---

  private fun streamer(
    platform: Platform,
    id: String,
    name: String,
    title: String,
    viewers: Long,
    live: Boolean = true,
  ) = Streamer(
    platform = platform,
    roomId = id,
    streamerName = name,
    title = title,
    coverUrl = null,
    avatarUrl = null,
    viewerCount = viewers,
    isLive = live,
    updatedAt = 1_700_000_000_000,
  )

  private val douyuRooms = listOf(
    streamer(Platform.DOUYU, "1", "一条小团团", "今天继续冲分！冲击王者一百星", 128_500),
    streamer(Platform.DOUYU, "2", "旭旭宝宝", "DNF 深渊日常，出货就送", 96_300),
    streamer(Platform.DOUYU, "3", "Doinb", "峡谷之巅排位，冲击前一百", 74_100),
    streamer(Platform.DOUYU, "4", "PDD", "好久没播了，今天聊聊近况", 210_000),
    streamer(Platform.DOUYU, "5", "大司马", "课上完了，来把排位", 58_000),
    streamer(Platform.DOUYU, "6", "呆妹儿小霸王", "深夜聊天，随机连麦", 32_400),
  )

  private val huyaRooms = listOf(
    streamer(Platform.HUYA, "10", "Uzi", "复出首播，感谢大家支持", 320_000),
    streamer(Platform.HUYA, "11", "骚男", "快乐游戏，快乐生活", 88_000),
  )

  private val douyuCategories = listOf(
    Category(Platform.DOUYU, "86", "英雄联盟", "1", "网游", sortOrder = 0),
    Category(Platform.DOUYU, "1", "王者荣耀", "2", "手游", sortOrder = 1),
    Category(Platform.DOUYU, "2", "绝地求生", "1", "网游", sortOrder = 2),
    Category(Platform.DOUYU, "3", "DNF", "1", "网游", sortOrder = 3),
    Category(Platform.DOUYU, "4", "户外", "8", "娱乐", sortOrder = 4),
  )

  private fun baseState(): UiState = UiState(
    screen = Screen.Home,
    platform = Platform.DOUYU,
    categories = douyuCategories,
    selectedCategory = douyuCategories.first(),
    rooms = douyuRooms,
    deviceName = "Pixel 7",
    localAddress = "192.168.1.23:47653",
  )

  // ------------------------------------------------------------------- renders ---

  @Test
  fun home() = capture("01-home", baseState())

  @Test
  fun homeFollowed() = capture("02-home-followed", baseState().copy(followed = douyuRooms.take(3)))

  @Test
  fun homePinnedCategories() =
    capture("03-home-pinned", baseState().copy(subscribedCategories = douyuCategories.take(3)))

  @Test
  fun homeLoading() = capture("04-home-loading", baseState().copy(rooms = emptyList(), loading = true))

  @Test
  fun homeEmpty() = capture("05-home-empty", baseState().copy(rooms = emptyList(), loading = false))

  @Test
  fun homeError() = capture(
    "06-home-error",
    baseState().copy(rooms = emptyList(), loading = false, error = "无法连接到服务器，请检查网络后重试"),
  )

  @Test
  fun followList() = capture(
    "07-follow-list",
    baseState().copy(
      screen = Screen.Follow,
      followed = listOf(
        douyuRooms[0], douyuRooms[1], douyuRooms[2],
        streamer(Platform.HUYA, "10", "Uzi", "复出首播", 320_000),
        streamer(Platform.BILIBILI, "20", "某幻君", "今晚八点档", 45_000, live = false),
      ),
    ),
  )

  @Test
  fun followEmpty() = capture("08-follow-empty", baseState().copy(screen = Screen.Follow))

  @Test
  fun searchIdle() = capture("09-search-idle", baseState().copy(screen = Screen.Search, searchQuery = ""))

  @Test
  fun searchHistory() = capture(
    "10-search-history",
    baseState().copy(
      screen = Screen.Search,
      searchHistory = listOf("英雄联盟", "户外", "DNF", "王者荣耀", "深夜电台", "美食探店"),
    ),
  )

  @Test
  fun searchResults() = capture(
    "11-search-results",
    baseState().copy(
      screen = Screen.Search,
      searchQuery = "英雄联盟",
      searchResults = douyuRooms.take(4),
      searchHistory = listOf("英雄联盟", "户外"),
    ),
  )

  @Test
  fun searchNoResult() = capture(
    "12-search-no-result",
    baseState().copy(screen = Screen.Search, searchQuery = "不存在的直播间"),
  )

  @Test
  fun syncPaired() = capture(
    "13-sync",
    baseState().copy(
      screen = Screen.Sync,
      pairingCode = "482913",
      peers = listOf(
        TrustedPeer(2, "小米平板 6", "secret", 1_700_000_000_000, lastSeenAt = 1_700_000_100_000),
      ),
      syncLog = listOf(
        SyncLogEntry(1_700_000_100_000, SyncLogEntry.Direction.PULL, "小米平板 6", "关注列表", 3, true, "更新 3 条"),
        SyncLogEntry(1_700_000_090_000, SyncLogEntry.Direction.PUSH, "小米平板 6", "分区订阅", 2, true, null),
        SyncLogEntry(1_700_000_080_000, SyncLogEntry.Direction.PUSH, "小米平板 6", "播放设置", 0, false, "对方拒绝连接"),
      ),
    ),
  )

  @Test
  fun syncEmpty() = capture("14-sync-empty", baseState().copy(screen = Screen.Sync))

  @Test
  fun settings() = capture(
    "15-settings",
    baseState().copy(
      screen = Screen.Settings,
      blockedKeywords = listOf("广告", "刷屏", "666"),
      bilibiliLogin = BiliLoginUi(loggedIn = false),
    ),
  )

  @Test
  fun settingsLoggedIn() = capture(
    "16-settings-logged-in",
    baseState().copy(screen = Screen.Settings, bilibiliLogin = BiliLoginUi(loggedIn = true)),
  )

  @Test
  fun player() = capture(
    "17-player",
    baseState().copy(
      screen = Screen.Player,
      room = douyuRooms[0],
      variants = listOf(
        StreamVariant("原画", "https://example.com/a.flv", "ws-h5", qualityRank = 0, qualityCode = 0),
        StreamVariant("高清", "https://example.com/b.flv", "ws-h5", qualityRank = 2, qualityCode = 2),
        StreamVariant("标清", "https://example.com/c.flv", "ali", qualityRank = 1, qualityCode = 1),
      ),
      selectedVariant = StreamVariant("原画", "https://example.com/a.flv", "ws-h5", qualityRank = 0),
      danmaku = listOf(
        DanmakuMessage("douyu:1", "观众A", "这波操作太秀了"),
        DanmakuMessage("douyu:1", "观众B", "666666"),
        DanmakuMessage("douyu:1", "观众C", "主播声音好听"),
      ),
    ),
  )

  @Test
  fun playerLoading() = capture("18-player-loading", baseState().copy(screen = Screen.Player, loading = true))

  @Test
  fun playerError() =
    capture("19-player-error", baseState().copy(screen = Screen.Player, error = "主播未开播"))

  @Test
  fun huyaHome() = capture(
    "20-huya-home",
    baseState().copy(
      platform = Platform.HUYA,
      rooms = huyaRooms,
      categories = listOf(Category(Platform.HUYA, "1", "网游", "热门", "热门", sortOrder = 0)),
    ),
  )

  private fun capture(name: String, state: UiState) {
    composeRule.setContent {
      LiveHubRoot(state = state, onAction = { })
    }
    composeRule.waitForIdle()

    val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
    FileOutputStream(File(outputDir, "$name.png")).use { out ->
      bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
    }
    bitmap.recycle()
  }
}
