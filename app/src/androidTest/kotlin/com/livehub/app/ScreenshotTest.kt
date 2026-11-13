package com.livehub.app

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.livehub.app.ui.LiveHubRoot
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.app.ui.components.BiliLoginUi
import com.livehub.app.ui.screens.PlayerContent
import com.livehub.app.ui.screens.PlayerSettings
import com.livehub.core.model.Category
import com.livehub.core.model.DanmakuMessage
import com.livehub.core.model.Platform
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import com.livehub.core.sync.SyncLogEntry
import com.livehub.core.sync.TrustedPeer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

/**
 * Renders every screen with representative data and writes a PNG for each.
 *
 * The APK cannot be launched interactively in CI, so these renders are the visual
 * regression harness: each run produces a folder of screenshots that a reviewer opens and
 * compares. They also fail the build if a screen throws while composing, which catches
 * layout crashes a plain unit test cannot see.
 *
 * One test per screen is required because a Compose test rule may only call `setContent`
 * once.
 */
@RunWith(AndroidJUnit4::class)
class ScreenshotTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

  /**
   * Renders are written both to the app's external files dir and to the shared
   * Download folder. The CI runner tears the emulator down as soon as the test task
   * finishes, after which `adb` reports "device offline", so the shared copy is the
   * one the workflow can actually pull while the device is still alive.
   */
  private val outputDirs: List<File> by lazy {
    listOfNotNull(
      File(context.getExternalFilesDir(null), "screenshots"),
      File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS),
        "livehub-shots",
      ),
    ).onEach { runCatching { it.mkdirs() } }
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

  /**
   * 虎牙 rooms, enough of them to stay above `NO_MORE_HINT_THRESHOLD`.
   *
   * The list used to hold two, which is a shape the platform cannot produce: 虎牙 answers 120
   * rooms per page on every gid, including 全部直播. Two rooms tripped the "已经到底了 · 虎牙
   * 当前只提供这些直播" footer and made `20-huya-home` claim the site only had two live rooms.
   */
  private val huyaRooms = listOf(
    streamer(Platform.HUYA, "10", "Uzi", "复出首播，感谢大家支持", 320_000),
    streamer(Platform.HUYA, "11", "骚男", "快乐游戏，快乐生活", 88_000),
    streamer(Platform.HUYA, "12", "青蛙", "深夜上分，不鸽", 12_300),
    streamer(Platform.HUYA, "13", "虎牙赛事", "TES vs iG 夏季赛", 1_204_517),
    streamer(Platform.HUYA, "14", "董小飒", "老主播回归", 54_100),
    streamer(Platform.HUYA, "15", "小霸王", "怀旧游戏专场", 31_700),
    streamer(Platform.HUYA, "16", "楚河", "主机游戏马拉松", 210_400),
    streamer(Platform.HUYA, "17", "神超", "云顶上分教学", 96_800),
    streamer(Platform.HUYA, "18", "文森特", "德莱文专场", 143_200),
    streamer(Platform.HUYA, "19", "冬瓜", "户外直播中", 27_900),
    streamer(Platform.HUYA, "110", "小漠", "深夜杂谈", 18_600),
    streamer(Platform.HUYA, "111", "阿冷", "唱歌聊天", 62_300),
  )

  private val douyuCategories = listOf(
    Category(Platform.DOUYU, "86", "英雄联盟", "1", "网游", sortOrder = 0),
    Category(Platform.DOUYU, "1", "王者荣耀", "2", "手游", sortOrder = 1),
    Category(Platform.DOUYU, "2", "绝地求生", "1", "网游", sortOrder = 2),
    Category(Platform.DOUYU, "3", "DNF", "1", "网游", sortOrder = 3),
    Category(Platform.DOUYU, "4", "户外", "8", "娱乐", sortOrder = 4),
  )

  /**
   * 斗鱼's row as the adapter really builds it: the aggregate entry first, then the tree.
   *
   * It is a separate fixture from [douyuCategories] because most renders are about a selected
   * partition, and the aggregate has its own render ([homeAggregate]) — putting it in every
   * home state would shift every chip and rewrite screenshots that are not about it.
   */
  private val douyuCategoriesWithAggregate =
    listOf(Category.aggregate(Platform.DOUYU, "全部直播")) + douyuCategories

  /**
   * The third level Douyu publishes under 英雄联盟.
   *
   * Ids carry the adapter's `c3:` prefix, which is what makes `DouyuAdapter.rooms` pick
   * `mixListV1/3_<id>` instead of `2_<id>`.
   */
  private val douyuSubCategories = listOf(
    Category(Platform.DOUYU, "c3:3683", "峡谷之巅", parentId = "86", sortOrder = 3683),
    Category(Platform.DOUYU, "c3:3684", "云顶之弈", parentId = "86", sortOrder = 3684),
    Category(Platform.DOUYU, "c3:3685", "排位赛", parentId = "86", sortOrder = 3685),
  )

  /**
   * Two groups from Huya's shipped table.
   *
   * The shape follows `HuyaCategories.parse`: `id` is the numeric `iGid` that
   * `getLiveList` expects and `parentName` is the group title, so the group row has more than
   * one chip to choose between.
   */
  private val huyaCategories = listOf(
    Category(Platform.HUYA, "1", "英雄联盟", "网游", "网游", sortOrder = 0),
    Category(Platform.HUYA, "2", "王者荣耀", "网游", "网游", sortOrder = 1),
    Category(Platform.HUYA, "3", "星秀", "娱乐", "娱乐", sortOrder = 2),
  )

  /** 虎牙's row as the adapter builds it: 全部直播 first, then the tree. */
  private val huyaCategoriesWithAggregate =
    listOf(Category.aggregate(Platform.HUYA, "全部直播")) + huyaCategories

  /**
   * Two groups from Bilibili's shipped table.
   *
   * `BilibiliCategories.parse` encodes the id as `"<parentAreaId>:<areaId>"`, both numeric,
   * because those are the `parent_area_id` / `area_id` that `second/getList` takes.
   */
  private val bilibiliCategories = listOf(
    Category(Platform.BILIBILI, "2:86", "英雄联盟", "2", "网游", sortOrder = 0),
    Category(Platform.BILIBILI, "2:87", "DOTA2", "2", "网游", sortOrder = 1),
    Category(Platform.BILIBILI, "1:21", "王者荣耀", "1", "手游", sortOrder = 2),
  )

  /** 哔哩哔哩's row as the adapter builds it: 热门 first, then the tree. */
  private val bilibiliCategoriesWithAggregate =
    listOf(Category.aggregate(Platform.BILIBILI, "热门")) + bilibiliCategories

  /**
   * The state the app actually opens in.
   *
   * The aggregate entry leads the row **and** is selected on a cold start (see
   * `MainViewModel.loadCategories`), so the baseline has to carry it — a baseline that opened
   * on 英雄联盟 would render a state no user ever sees on launch. Screens that are about a
   * partition override `selectedCategory` themselves ([homeOtherCategory],
   * [homeSubCategory]).
   */
  private fun baseState(): UiState = UiState(
    screen = Screen.Home,
    platform = Platform.DOUYU,
    categories = douyuCategoriesWithAggregate,
    selectedCategory = douyuCategoriesWithAggregate.first(),
    rooms = douyuRooms,
    deviceName = "Pixel 7",
    localAddress = "192.168.1.23:47653",
  )

  /**
   * A player state that carries every field the layout reads.
   *
   * A variant alone leaves the header and the danmaku panel empty, which is what produced
   * an all-black 703-byte render.
   */
  private fun playerState(): UiState = baseState().copy(
    screen = Screen.Player,
    room = douyuRooms[0],
    followed = douyuRooms.take(2),
    variants = listOf(
      StreamVariant("原画", "https://example.com/a.flv", "ws-h5", qualityRank = 0, qualityCode = 0),
      StreamVariant("高清", "https://example.com/b.flv", "ws-h5", qualityRank = 2, qualityCode = 2),
      StreamVariant("标清", "https://example.com/c.flv", "ali", qualityRank = 1, qualityCode = 1),
    ),
    selectedVariant = StreamVariant(
      "原画", "https://example.com/a.flv", "ws-h5", qualityRank = 0, qualityCode = 0,
    ),
    lines = listOf("ws-h5", "ali", "tx"),
    selectedLine = "ws-h5",
    danmaku = listOf(
      DanmakuMessage("douyu:1", "观众A", "这波操作太秀了"),
      DanmakuMessage("douyu:1", "观众B", "666666"),
      DanmakuMessage("douyu:1", "观众C", "主播声音好听"),
      DanmakuMessage("douyu:1", "观众D", "刚来，错过了什么"),
      DanmakuMessage("douyu:1", "观众E", "主播能开个麦吗"),
    ),
  )

  /**
   * A 虎牙 room as `HuyaApi.playback` now returns it: one quality, three CDN lines.
   *
   * The page publishes six `gameStreamInfoList` entries for three hosts, and every entry
   * used to become a variant labelled 原画 — six chips, five of them indistinguishable from
   * another and two thirds pointing at a CDN another chip already offered. The line labels
   * are the page's own `sCdnType` values, which is also what the Douyu selector shows.
   */
  private fun huyaPlayerState(): UiState = baseState().copy(
    screen = Screen.Player,
    platform = Platform.HUYA,
    categories = huyaCategoriesWithAggregate,
    selectedCategory = huyaCategoriesWithAggregate.first(),
    room = huyaRooms[0],
    variants = listOf(
      StreamVariant(
        "原画",
        "http://al-game.flv.huya.com/src/660000.flv?wsSecret=x",
        "AL",
        qualityRank = 0,
        qualityCode = 0,
      ),
    ),
    selectedVariant = StreamVariant(
      "原画",
      "http://al-game.flv.huya.com/src/660000.flv?wsSecret=x",
      "AL",
      qualityRank = 0,
      qualityCode = 0,
    ),
    lines = listOf("AL", "HS", "TX"),
    selectedLine = "AL",
  )

  /**
   * A 哔哩哔哩 room, whose two axes are quality (qn) and CDN host.
   *
   * No render ever showed a B站 playback page: the only player screenshots used a Douyu
   * room, so the platform whose `variants` carry a host name as their line label had no
   * visual coverage at all.
   */
  private fun bilibiliPlayerState(): UiState = baseState().copy(
    screen = Screen.Player,
    platform = Platform.BILIBILI,
    categories = bilibiliCategoriesWithAggregate,
    selectedCategory = bilibiliCategoriesWithAggregate.first(),
    room = streamer(Platform.BILIBILI, "20", "某幻君", "今晚八点档，来聊聊", 45_000),
    variants = listOf(
      StreamVariant("原画", "https://d1--cn-gotcha.bilivideo.com/live/1.m3u8", "d1--cn-gotcha.bilivideo.com", isHls = true, qualityRank = 0, qualityCode = 10_000),
      StreamVariant("蓝光", "https://d1--cn-gotcha.bilivideo.com/live/2.m3u8", "d1--cn-gotcha.bilivideo.com", isHls = true, qualityRank = 2, qualityCode = 4_000),
      StreamVariant("高清", "https://d1--cn-gotcha.bilivideo.com/live/3.m3u8", "d1--cn-gotcha.bilivideo.com", isHls = true, qualityRank = 3, qualityCode = 250),
    ),
    selectedVariant = StreamVariant(
      "原画",
      "https://d1--cn-gotcha.bilivideo.com/live/1.m3u8",
      "d1--cn-gotcha.bilivideo.com",
      isHls = true,
      qualityRank = 0,
      qualityCode = 10_000,
    ),
    lines = listOf("d1--cn-gotcha.bilivideo.com", "cn-hb-bj.bilivideo.com"),
    selectedLine = "d1--cn-gotcha.bilivideo.com",
    danmaku = listOf(
      DanmakuMessage("bilibili:20", "观众A", "前排围观"),
      DanmakuMessage("bilibili:20", "观众B", "这个直播间氛围真好"),
    ),
  )

  /** Search results for 虎牙, which no render had ever produced. */
  private fun huyaSearchState(): UiState = baseState().copy(
    screen = Screen.Search,
    platform = Platform.HUYA,
    categories = huyaCategoriesWithAggregate,
    selectedCategory = huyaCategoriesWithAggregate.first(),
    searchQuery = "英雄联盟",
    lastSearchQuery = "英雄联盟",
    searchResults = listOf(
      streamer(Platform.HUYA, "660000", "Uzi", "复出首播，感谢大家支持", 17_863_363),
      streamer(Platform.HUYA, "660001", "骚男", "快乐游戏，快乐生活", 88_000),
      streamer(Platform.HUYA, "660002", "虎牙赛事", "TES vs iG 夏季赛", 1_204_517),
      streamer(Platform.HUYA, "660003", "青蛙", "深夜上分，不鸽", 12_300, live = false),
    ),
    searchHistory = listOf("英雄联盟"),
  )

  /** Search results for 哔哩哔哩, likewise never rendered. */
  private fun bilibiliSearchState(): UiState = baseState().copy(
    screen = Screen.Search,
    platform = Platform.BILIBILI,
    categories = bilibiliCategoriesWithAggregate,
    selectedCategory = bilibiliCategoriesWithAggregate.first(),
    searchQuery = "英雄联盟",
    lastSearchQuery = "英雄联盟",
    searchResults = listOf(
      streamer(Platform.BILIBILI, "20", "某幻君", "今晚八点档，来聊聊", 45_000),
      streamer(Platform.BILIBILI, "21", "老番茄", "新视频首发", 88_000),
      streamer(Platform.BILIBILI, "22", "嘉然今天吃什么", "看比赛，顺便聊聊天", 320_000),
      streamer(Platform.BILIBILI, "23", "小约翰可汗", "硬核历史，随便聊聊", 6_700, live = false),
    ),
    searchHistory = listOf("英雄联盟", "户外"),
  )

  /** The sync page as a paired device sees it, with three entries in the exchange log. */
  private fun syncState(): UiState = baseState().copy(
    screen = Screen.Sync,
    pairingCode = "482913",
    pairingCodeSecondsLeft = 268,
    peers = listOf(
      TrustedPeer(2, "小米平板 6", "secret", 1_700_000_000_000, lastSeenAt = 1_700_000_100_000),
    ),
    // Every row says 关注列表 because it is the only scope there is (round 32). The fixture
    // used to list 分区订阅 and 播放设置, which the app can no longer produce — a reviewer
    // reading the log in `13b-sync-log` would have been told the app syncs more than it does.
    // The deepest row is on the second paired device so `scrollToText` still has one unique
    // target: with a single peer, all three rows render identical text.
    syncLog = listOf(
      SyncLogEntry(1_700_000_100_000, SyncLogEntry.Direction.PULL, "小米平板 6", "关注列表", 3, true, "更新 3 条"),
      SyncLogEntry(1_700_000_090_000, SyncLogEntry.Direction.PUSH, "小米平板 6", "关注列表", 2, true, null),
      SyncLogEntry(1_700_000_080_000, SyncLogEntry.Direction.PUSH, "客厅电视", "关注列表", 0, false, "对方拒绝连接"),
    ),
  )

  private fun settingsState(): UiState = baseState().copy(
    screen = Screen.Settings,
    blockedKeywords = listOf("广告", "刷屏", "666"),
    bilibiliLogin = BiliLoginUi(loggedIn = false),
  )

  // ------------------------------------------------------------------- renders ---

  @Test
  fun home() = capture("01-home", baseState())

  @Test
  fun homeFollowed() = capture("02-home-followed", baseState().copy(followed = douyuRooms.take(3)))

  /**
   * A category other than the one the app selects by itself.
   *
   * This render was called `03-home-pinned` and its comment implied a pinned category. Nothing
   * is pinnable: `CategoryRow` documents that the subscribed-categories row was removed
   * precisely because it duplicated selecting a category. So the name described a state the
   * app cannot reach, and the file only ever proved that tapping a chip moves the highlight
   * and the grid.
   *
   * The partition is named explicitly rather than indexed out of the fixture: `baseState()`
   * now carries the aggregate as its first entry, so an index would silently point at a
   * different chip.
   */
  @Test
  fun homeOtherCategory() = capture(
    "03-home-other-category",
    baseState().copy(
      selectedCategory = douyuCategories.first { it.name == "绝地求生" },
    ),
  )

  /**
   * The expanded category tree.
   *
   * The collapsed row shows one line of groups and the active group's partitions; the chevron
   * wraps every group into a FlowRow. That second state is the whole point of the control and it
   * cannot be reached without a tap, so it needs its own render — a collapsed baseline proves
   * nothing about the layout that appears after the tap.
   */
  @Test
  fun homeCategoryExpanded() = capture(
    "01b-home-category-expanded",
    baseState(),
    clickDescription = "展开分类",
    expectDescription = "收起分类",
  )

  /**
   * The third chip row, which only Douyu publishes.
   *
   * Nothing rendered this before: `CategoryRow` shows the row only when `subCategories` is
   * non-empty, and no state in this file ever set it. The row that the third-level pagination
   * bug lived in therefore had no visual coverage at all — a broken or invisible row would
   * have passed every screenshot review.
   *
   * The parent partition is set explicitly: the baseline selects the aggregate, which has no
   * third level, so a third row over it would be a state the app cannot reach.
   */
  @Test
  fun homeSubCategory() = capture(
    "03b-home-subcategory",
    baseState().copy(
      selectedCategory = douyuCategories.first { it.name == "英雄联盟" },
      subCategories = douyuSubCategories,
      selectedSubCategory = douyuSubCategories.first(),
      rooms = douyuRooms.take(4),
    ),
  )

  @Test
  fun homeLoading() = capture("04-home-loading", baseState().copy(rooms = emptyList(), loading = true))

  /**
   * Nothing live under the selected category — which, since the baseline selects the aggregate,
   * is the aggregate's empty state.
   *
   * That is the copy this render exists to check: it used to say "这个分区暂时没有直播 … 还没有
   * 人在播" for every category, and the aggregate is not a partition — there is no anchor to
   * name. `HomeScreen` now switches to "现在没有人在直播" when `isAggregate` is set.
   */
  @Test
  fun homeEmpty() = capture("05-home-empty", baseState().copy(rooms = emptyList(), loading = false))

  @Test
  fun homeError() = capture(
    "06-home-error",
    baseState().copy(rooms = emptyList(), loading = false, error = "无法连接到服务器，请检查网络后重试"),
  )

  /**
   * The mini window, over the browse screen.
   *
   * Every other render of the player is a full-window surface; this is the only one of it shrunk
   * into the corner, and it is what proves the picture did not end up as a 16:9 band at the top of
   * the first page instead.
   *
   * The picture itself is the resolve spinner. `capture` renders `LiveHubRoot` for this state, and
   * the video-surface stub the harness passes is only wired into `PlayerContent`'s own renders — a
   * real `SurfaceView` captures as solid black anyway, which would hide the window's shape.
   */
  @Test
  fun homeMiniPlayer() = capture("06b-home-mini-player", baseState().copy(room = douyuRooms[0]))

  private val followRooms = listOf(
    douyuRooms[0], douyuRooms[1], douyuRooms[2],
    streamer(Platform.HUYA, "10", "Uzi", "复出首播", 320_000),
    streamer(Platform.BILIBILI, "20", "某幻君", "今晚八点档", 45_000, live = false),
  )

  /**
   * The follow list: the 特别关注 avatar row over a two-column grid.
   *
   * The layout is a *visual* claim that no JVM assertion can hold — a strip of circular avatars
   * with a live ring, and covers two per row underneath it. The fixture deliberately mixes live
   * and offline rooms so the ring and the "未开播" badge are both on screen.
   */
  @Test
  fun followList() = capture(
    "07-follow-list",
    baseState().copy(
      screen = Screen.Follow,
      followed = followRooms,
      specialFollowKeys = listOf(followRooms[0].key, followRooms[3].key, followRooms[4].key),
    ),
  )

  /**
   * The 特别关注 picker, opened by the ＋ at the end of the avatar row.
   *
   * The panel is where a room is starred, so it needs a render of its own: the checked rows, the
   * live dots and the explanatory line are the whole affordance. `expectText` is the panel title —
   * without it a click that opened nothing would still produce a plausible screenshot of the grid.
   */
  @Test
  fun followSpecialPicker() = capture(
    "07b-follow-special-picker",
    baseState().copy(
      screen = Screen.Follow,
      followed = followRooms,
      specialFollowKeys = listOf(followRooms[0].key, followRooms[2].key),
    ),
    clickDescription = "添加特别关注",
    expectText = "选择特别关注",
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
      lastSearchQuery = "英雄联盟",
      searchResults = douyuRooms.take(4),
      searchHistory = listOf("英雄联盟", "户外"),
    ),
  )

  /**
   * One keyword, three platforms answering and one saying why it could not.
   *
   * This is the render of the aggregator's own premise. What it has to show is the *sections* —
   * without a header the four sources read as one list of unrelated rooms, and the cards carry no
   * platform label on purpose — and the failed platform's line next to them, because "抖音 needs a
   * login" must not look like "抖音 has nothing".
   */
  @Test
  fun searchAllPlatforms() = capture(
    "11e-search-all-platforms",
    baseState().copy(
      screen = Screen.Search,
      searchQuery = "团团",
      lastSearchQuery = "团团",
      searchAllPlatforms = true,
      searchResults = listOf(
        douyuRooms[0],
        streamer(Platform.HUYA, "10", "Uzi", "复出首播", 320_000),
        streamer(Platform.BILIBILI, "20", "某幻君", "今晚八点档", 45_000),
      ),
      searchFailures = mapOf(Platform.DOUYIN to "需要登录后按昵称搜索"),
    ),
  )

  /**
   * The keyword was submitted and the first page has not arrived yet.
   *
   * No render ever showed this: the loading state has its own skeleton, and the search screen's
   * skeleton was a two-column grid until this round, which promised a layout the results never
   * used.
   */
  @Test
  fun searchLoading() = capture(
    "11d-search-loading",
    baseState().copy(
      screen = Screen.Search,
      searchQuery = "英雄联盟",
      lastSearchQuery = "英雄联盟",
      loading = true,
    ),
  )

  @Test
  fun searchNoResult() = capture(
    "12-search-no-result",
    baseState().copy(
      screen = Screen.Search,
      searchQuery = "不存在的直播间",
      lastSearchQuery = "不存在的直播间",
    ),
  )

  /**
   * A failed search, which had no render and could not have had one.
   *
   * The failure state only renders when there is nothing else to show, and a failed *second*
   * search used to leave the first keyword's rooms on screen — so this state was unreachable
   * from the UI until the view model started clearing them.
   */
  @Test
  fun searchFailed() = capture(
    "12b-search-failed",
    baseState().copy(
      screen = Screen.Search,
      searchQuery = "英雄联盟",
      lastSearchQuery = "英雄联盟",
      error = "斗鱼返回了网页而不是数据，请稍后重试",
    ),
  )

  /**
   * Text in the field, but no search submitted yet.
   *
   * The screen used to read "没有找到「英雄联盟」" here — a verdict on a search that had not run.
   * The render is the visual half of `SearchScreenTest`'s first assertion.
   */
  @Test
  fun searchTyping() = capture(
    "12c-search-typing",
    baseState().copy(
      screen = Screen.Search,
      searchQuery = "英雄联盟",
    ),
  )

  @Test
  fun syncPaired() = capture("13-sync", syncState())

  /**
   * The exchange log, which no render had ever reached.
   *
   * `13-sync` is taken at the top of the list, where the log is entirely below the fold — the
   * only trace of it was the 我的设备 heading cut in half by the bottom bar, and a reviewer
   * could not tell the page had a log at all. Scrolling to the last row is also what makes
   * the render prove the section is reachable rather than merely present.
   */
  @Test
  fun syncLog() = capture("13b-sync-log", syncState(), scrollToText = LAST_SYNC_LOG_ROW)

  @Test
  fun syncEmpty() = capture("14-sync-empty", baseState().copy(screen = Screen.Sync))

  @Test
  fun settings() = capture("15-settings", settingsState())

  /**
   * The bottom of the settings page: the account card and the about card.
   *
   * Both sit below the fold at 320x640 and had never been rendered. `15-settings` stops in
   * the middle of the blocked-keyword card, so 哔哩哔哩账号 and 关于 — the only place the app
   * names itself — had no visual coverage.
   */
  @Test
  fun settingsAbout() = capture("15b-settings-about", settingsState(), scrollToText = "LiveHub")

  @Test
  fun settingsLoggedIn() = capture(
    "16-settings-logged-in",
    baseState().copy(screen = Screen.Settings, bilibiliLogin = BiliLoginUi(loggedIn = true)),
  )

  // ------------------------------------------------------------------ dark theme ---
  //
  // Every one of the renders above is the light scheme, because `baseState()` leaves
  // `themeMode = System` and the emulator reports light. Dark is a real choice in 设置, and it
  // had **zero** visual coverage — a colour that only reads badly on a dark background (a
  // `surfaceVariant` chip on a `surface` card, the LIVE badge over a dark cover) could not show
  // up in any artifact.
  //
  // Only the screens that go through `LiveHubRoot` are captured: that is the only place
  // `LiveHubTheme` is applied, and `PlayerContent` — the branch a `Screen.Player` state takes
  // here — is composed *outside* it. A dark player render would therefore be the light scheme
  // with a black video box, which is exactly the kind of screenshot that looks like coverage
  // without being any.
  @Test
  fun homeDark() = capture(
    "23-home-dark",
    baseState().copy(themeMode = com.livehub.core.designsystem.ThemeMode.Dark),
  )

  @Test
  fun settingsDark() = capture(
    "23b-settings-dark",
    settingsState().copy(themeMode = com.livehub.core.designsystem.ThemeMode.Dark),
  )

  @Test
  fun syncDark() = capture(
    "23c-sync-dark",
    baseState().copy(
      screen = Screen.Sync,
      themeMode = com.livehub.core.designsystem.ThemeMode.Dark,
    ),
  )

  /**
   * The platform switcher on the search screen.
   *
   * The row is new this round; without a render the only evidence it exists is the source. It is
   * also the one place four chips have to fit on one 320dp line, which is exactly the kind of
   * thing that renders as vertically-stacked single characters when it does not (see the 同步页
   * chip bug from round 13).
   */
  @Test
  fun searchPlatformRow() = capture(
    "09b-search-platforms",
    baseState().copy(
      screen = Screen.Search,
      searchQuery = "英雄联盟",
      lastSearchQuery = "英雄联盟",
      searchResults = douyuRooms.take(3),
    ),
  )

  @Test
  fun player() = capture("17-player", playerState())

  /**
   * The full-screen layout on its first frame.
   *
   * No danmaku here: a bubble enters from beyond the right edge, so at frame one every one of
   * them is outside the clipped overlay. [playerFullscreenDanmaku] is the render that shows
   * the layer; this one shows the chrome around an empty picture.
   */
  @Test
  fun playerFullScreen() = capture("17b-player-fullscreen", playerState(), fullscreen = true)

  /**
   * The full-screen danmaku layer, caught mid-flight.
   *
   * This layer had no visual coverage at all for eighteen rounds. The only full-screen render
   * is taken on the first frame, and at that instant the bubbles have not moved yet: they sit
   * at the frame's right edge, clipped away. The two renders differ in nothing but the clock
   * advance, which also pins down why the bubbles were missing.
   *
   * 2.5 s is chosen against the player's own timings: far enough that every label has
   * travelled about a third of its way across, still short of the 3 s after which the
   * full-screen chrome hides itself, so the render keeps the bar and the side buttons too.
   */
  @Test
  fun playerFullscreenDanmaku() = capture(
    "17c-player-fullscreen-danmaku",
    playerState(),
    fullscreen = true,
    advanceMillis = 2_500L,
    onScreenText = "这波操作太秀了",
  )

  /**
   * The loading, offline and error renders must differ from the playing one.
   *
   * They used to be byte-identical to it: the harness hands `PlayerContent` a stand-in for
   * the video surface, and that stand-in was drawn unconditionally, so the state-specific
   * content (spinner, "主播未开播", error text) never appeared. Passing the stub must not
   * disable the states that exist precisely because there is no video.
   */
  @Test
  fun playerLoading() = capture(
    "18-player-loading",
    baseState().copy(screen = Screen.Player, room = douyuRooms[0], loading = true),
  )

  @Test
  fun playerError() = capture(
    "19-player-error",
    baseState().copy(screen = Screen.Player, room = douyuRooms[0], error = "主播未开播"),
  )

  @Test
  fun playerOffline() = capture(
    "19b-player-offline",
    baseState().copy(
      screen = Screen.Player,
      room = douyuRooms[0].copy(isLive = false),
      danmakuEnabled = false,
    ),
  )

  /**
   * A platform switch, which has to move the category selection with the platform.
   *
   * Both of these used to swap `platform` and `categories` but leave `selectedCategory`
   * pointing at a 斗鱼 category, so the renders showed states the app cannot produce: 虎牙
   * with no chip selected at all, and 哔哩哔哩 with a group highlighted and no area inside it.
   * `selectPlatform` clears the selection and `loadCategories` selects the new platform's
   * **first** entry — which is its aggregate — so the fixture has to do the same or the
   * screenshot lies about what a user sees after switching platform.
   */
  @Test
  fun huyaHome() = capture(
    "20-huya-home",
    baseState().copy(
      platform = Platform.HUYA,
      rooms = huyaRooms,
      categories = huyaCategoriesWithAggregate,
      selectedCategory = huyaCategoriesWithAggregate.first(),
    ),
  )

  @Test
  fun bilibiliHome() = capture(
    "21-bilibili-home",
    baseState().copy(
      platform = Platform.BILIBILI,
      rooms = listOf(
        streamer(Platform.BILIBILI, "20", "某幻君", "今晚八点档，来聊聊", 45_000),
        streamer(Platform.BILIBILI, "21", "老番茄", "新视频首发", 88_000),
        streamer(Platform.BILIBILI, "22", "嘉然今天吃什么", "看比赛，顺便聊聊天", 320_000),
        streamer(Platform.BILIBILI, "23", "小约翰可汗", "硬核历史，随便聊聊", 6_700),
        streamer(Platform.BILIBILI, "24", "老坛胡说", "深夜杂谈", 12_400),
        streamer(Platform.BILIBILI, "25", "绵羊料理", "厨房直播", 33_100),
        streamer(Platform.BILIBILI, "26", "LexBurner", "聊聊新番", 71_500),
        streamer(Platform.BILIBILI, "27", "影视飓风", "拍摄现场", 58_200),
        streamer(Platform.BILIBILI, "28", "何同学", "新项目预告", 120_600),
        streamer(Platform.BILIBILI, "29", "凉风Kaze", "阅片无数", 44_900),
        streamer(Platform.BILIBILI, "210", "面筋哥", "唱歌时间", 9_800),
        streamer(Platform.BILIBILI, "211", "手工耿", "发明时间", 26_300),
      ),
      categories = bilibiliCategoriesWithAggregate,
      selectedCategory = bilibiliCategoriesWithAggregate.first(),
    ),
  )

  /**
   * Search results for 虎牙 and 哔哩哔哩.
   *
   * Every search screenshot up to round 27 used 斗鱼 fixtures, so the two platforms whose
   * search plumbing was rewritten (Huya's popularity field, Bilibili's result bucket) had no
   * visual coverage: the cards were proven only by JVM tests.
   */
  @Test
  fun searchHuya() = capture("11b-search-huya", huyaSearchState())

  @Test
  fun searchBilibili() = capture("11c-search-bilibili", bilibiliSearchState())

  /** A 哔哩哔哩 playback page, which no render had produced either. */
  @Test
  fun playerBilibili() = capture("17d-player-bilibili", bilibiliPlayerState())

  /**
   * The settings panel of a 虎牙 room: one quality chip, three line chips.
   *
   * This is the render that would have caught the defect of round 27: the panel used to list
   * six variants labelled 原画, and nothing in a text assertion could tell them apart. It is
   * composed directly because the sheet only opens on a tap and the screenshot has to freeze
   * the clock, but it is the same composable `PlayerScreen` opens.
   */
  /**
   * A 抖音 room's 清晰度 row: one chip per quality.
   *
   * `enter` lists the same renditions twice, under `hls_pull_url_map` and under
   * `flv_pull_url`, and both maps spell `FULL_HD1` as 原画. Before `parseVariants` collapsed
   * the duplicates this panel showed 原画 twice, with nothing on screen to tell them apart —
   * and the second one is the container the reference resolver warns may be H.265-in-FLV
   * (audio only on many devices). 抖音 has no CDN line axis, so `lines` stays empty.
   */
  @Test
  fun playerSettingsDouyin() {
    val state = douyinPlayerState()
    capture(
      "22b-player-settings-douyin",
      state,
      content = {
        MaterialTheme {
          Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
          ) {
            PlayerSettings(state = state, variant = state.selectedVariant, onAction = {})
          }
        }
      },
    )
  }

  /**
   * A 哔哩哔哩 room's settings panel: three qualities and, for the first time, two CDN lines.
   *
   * The panel renders `state.lines` only when there are at least two, so this row could not
   * appear before `BilibiliApi.playback` started keeping every `url_info` entry: it read only
   * the first host, so every quality had exactly one line and the row was hidden — the 线路
   * selector existed for Douyu and Huya and silently did nothing here.
   */
  @Test
  fun playerSettingsBilibili() {
    val state = bilibiliPlayerState()
    capture(
      "22c-player-settings-bilibili",
      state,
      content = {
        MaterialTheme {
          Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
          ) {
            PlayerSettings(state = state, variant = state.selectedVariant, onAction = {})
          }
        }
      },
    )
  }

  private fun douyinPlayerState(): UiState {
    val original = StreamVariant(
      qualityLabel = "原画",
      url = "https://pull-flv-l1.douyincdn.com/stage/full.m3u8",
      lineName = "抖音",
      isHls = true,
      qualityRank = 0,
      qualityCode = 0,
    )
    return baseState().copy(
      screen = Screen.Player,
      platform = Platform.DOUYIN,
      room = streamer(Platform.DOUYIN, "7380123456789012345", "KPL赛事", "王者荣耀职业联赛", 123_456),
      variants = listOf(
        original,
        StreamVariant(
          qualityLabel = "蓝光",
          url = "https://pull-flv-l1.douyincdn.com/stage/hd.m3u8",
          lineName = "抖音",
          isHls = true,
          qualityRank = 1,
          qualityCode = 1,
        ),
      ),
      selectedVariant = original,
      lines = emptyList(),
      selectedLine = null,
    )
  }

  @Test
  fun playerSettingsHuya() {
    val state = huyaPlayerState()
    capture(
      "22-player-settings-huya",
      state,
      content = {
        MaterialTheme {
          Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
          ) {
            PlayerSettings(state = state, variant = state.selectedVariant, onAction = {})
          }
        }
      },
    )
  }

  /**
   * The decoder-failure state, which had no render at all.
   *
   * `19-player-error` shows a *resolve* failure (`state.error`); this one is the player
   * reporting that the URL it was given cannot be decoded (`state.playerError`). The two are
   * deliberately separate states because only the second one means the URL itself is dead.
   */
  @Test
  fun playerDecoderError() = capture(
    "19c-player-decoder-error",
    playerState().copy(playerError = "无法解码该流（H.265-in-FLV）"),
  )

  // -------------------------------------------------------------------- helpers ---

  /**
   * Renders [state] and writes it as `<name>.png`.
   *
   * The clock is frozen and exactly one frame is rendered, which is what a static screen
   * needs. Anything driven by an animation is therefore still at its start value — a danmaku
   * bubble, for instance, enters from beyond the right edge and is clipped away entirely at
   * frame one. A render that has to show such an element asks for [advanceMillis] instead of
   * relying on the default.
   *
   * @param advanceMillis extra virtual time to run before capturing, for renders that only
   *   become visible mid-animation.
   * @param onScreenText a danmaku label the render must show *inside* the frame. A bubble
   *   parked at its start position is a node the semantics tree still contains, so existence
   *   alone proves nothing; this checks the position as well.
   * @param scrollToText text to scroll into view first, for content that starts below the
   *   fold. Reaching it is part of what the render asserts: the scroll fails outright when the
   *   list cannot scroll, so a page that merely *contains* a section cannot pass as one that
   *   can show it.
   * @param content replaces the screen the state would otherwise pick. Used for panels that a
   *   state cannot express — the player settings sheet is switched on by a tap, and it is
   *   exactly where the quality and line chips live.
   */
  private fun capture(
    name: String,
    state: UiState,
    fullscreen: Boolean = false,
    advanceMillis: Long = 0L,
    onScreenText: String? = null,
    scrollToText: String? = null,
    /** Clicks an icon-only control by its content description. */
    clickDescription: String? = null,
    /** Asserts an icon-only control is present, for the same reason as [clickDescription]. */
    expectDescription: String? = null,
    /**
     * Text the render must show *after* the click. Without it a click that opened nothing would
     * still produce a plausible-looking screenshot of the screen underneath.
     */
    expectText: String? = null,
    content: (@Composable () -> Unit)? = null,
  ) {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      if (content != null) {
        content()
      } else if (state.screen == Screen.Player) {
        // Rendered through PlayerContent so the screenshot does not depend on the sheet
        // state, and so a fullscreen render is reachable without a real gesture.
        //
        // The video surface is replaced: `captureToImage` cannot read a SurfaceView (its
        // pixels are composited by SurfaceFlinger, not drawn into the window), so a real
        // player always captures as solid black and the render would prove nothing about
        // the chrome around it. PlayerContent only substitutes it for a room that is
        // actually playing, so the loading / offline / error states still render their own
        // content.
        PlayerContent(
          state = state,
          onAction = {},
          fullscreen = fullscreen,
          onFullscreenChange = {},
          onOpenSettings = {},
          videoSurface = { surfaceModifier -> VideoSurfaceStub(surfaceModifier) },
        )
      } else {
        LiveHubRoot(state = state, onAction = { })
      }
    }
    // Compose test rules advance the virtual clock automatically; with the player's
    // auto-hide timer that meant the chrome had already faded before the capture, so
    // the screenshot never showed the controls. Freeze the clock and render the first
    // frame instead.
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.waitForIdle()

    if (clickDescription != null) {
      // Icon-only controls have no text to match; the chevron is one of them.
      composeRule.onNodeWithContentDescription(clickDescription).performClick()
      composeRule.mainClock.advanceTimeByFrame()
      composeRule.waitForIdle()
    }

    expectDescription?.let { description ->
      composeRule.onNodeWithContentDescription(description).assertIsDisplayed()
    }

    expectText?.let { text ->
      composeRule.onNodeWithText(text).assertIsDisplayed()
    }

    if (scrollToText != null) {
      // A lazy list applies its scroll on a frame boundary and the clock is frozen for the
      // capture, so the scroll runs with the clock free and is frozen again afterwards. The
      // frozen frames that follow are what make the scrolled position part of the PNG.
      composeRule.mainClock.autoAdvance = true
      composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(scrollToText))
      composeRule.mainClock.autoAdvance = false
      composeRule.waitForIdle()
    }

    if (advanceMillis > 0L) {
      // `waitForIdle` on a frozen clock does not move time forward — it only settles the
      // composition — so this is the only thing that actually starts an animation.
      composeRule.mainClock.advanceTimeBy(advanceMillis)
      composeRule.waitForIdle()
    }

    onScreenText?.let { text ->
      val frame = composeRule.onRoot().fetchSemanticsNode().boundsInRoot
      val bounds = composeRule
        .onNodeWithText(text, useUnmergedTree = true)
        .fetchSemanticsNode()
        .boundsInRoot
      // A bubble that has not started moving sits at x = the frame's right edge, clipped away
      // — and its semantics node exists either way, so existence proves nothing. The position
      // is what shows the capture really contains the layer. 10% of the width is far outside
      // the parked position and far inside what the advance above buys, whatever the label's
      // measured width turns out to be.
      val enteredBy = frame.width * 0.1f
      assertTrue(
        "\"$text\" has not entered the frame: $bounds within $frame",
        bounds.left < frame.right - enteredBy && bounds.right > frame.left,
      )
    }

    val bitmap = composeRule.onRoot().captureToImage().asAndroidBitmap()
    outputDirs.forEach { dir ->
      runCatching {
        FileOutputStream(File(dir, "$name.png")).use { out ->
          bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
        }
      }
    }

    // A render that is a single flat colour means the screen composed nothing visible.
    // `17-player` shipped as an all-black 703-byte PNG for exactly that reason: a variant
    // was present but the room was not, so every text row rendered empty over a black
    // surface.
    assertTrue(
      "$name rendered as a single colour, so the screen composed no visible content",
      hasVisibleContent(bitmap),
    )
    bitmap.recycle()
  }

  /**
   * True when the bitmap contains at least [MIN_DISTINCT_COLORS] different colours.
   *
   * Scans the whole bitmap rather than a sampling grid. A grid misses small elements —
   * the first version sampled 24x24 points and declared the "no results" screen blank
   * because its single centred line of text fell between sample points.
   *
   * The scan stops as soon as the threshold is reached, so a normal screen costs a few
   * hundred pixels rather than a full pass.
   */
  private fun hasVisibleContent(bitmap: android.graphics.Bitmap): Boolean {
    val width = bitmap.width
    val height = bitmap.height
    if (width <= 0 || height <= 0) return false

    val pixels = IntArray(width * height)
    bitmap.getPixels(pixels, 0, width, 0, 0, width, height)

    val seen = HashSet<Int>(MIN_DISTINCT_COLORS * 4)
    for (color in pixels) {
      seen.add(color)
      if (seen.size >= MIN_DISTINCT_COLORS) return true
    }
    return false
  }

  private companion object {
    /**
     * Below this a render is a flat fill.
     *
     * A single line of antialiased text already spans well over a dozen greys, so the
     * bar is far below any real screen and still catches an empty one.
     */
    const val MIN_DISTINCT_COLORS = 12

    /**
     * The deepest row of [syncState]'s exchange log, in the form `SyncLogRow` renders it.
     *
     * Scrolling to the last entry rather than to the 同步记录 heading is what leaves the whole
     * section in frame: the scroll stops as soon as the target is visible, so aiming at the
     * heading would park it at the bottom edge with every row still below it.
     */
    const val LAST_SYNC_LOG_ROW = "关注列表 · 客厅电视"
  }
}

/**
 * Stand-in for the video surface in screenshots.
 *
 * Draws a gradient plus a label so the capture has real content where the picture would
 * be; a real `PlayerView` is a `SurfaceView` and always captures as solid black.
 */
@Composable
private fun VideoSurfaceStub(modifier: Modifier = Modifier) {
  Box(
    modifier = modifier.background(
      Brush.linearGradient(
        listOf(
          Color(0xFF1F2937),
          Color(0xFF4B5563),
          Color(0xFF111827),
        ),
      ),
    ),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = "视频画面（截图占位）",
      color = Color.White.copy(alpha = 0.55f),
      style = MaterialTheme.typography.labelMedium,
    )
  }
}
