package com.livehub.app.ui.screens

import android.content.Context
import android.os.Looper
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import com.livehub.app.di.AppContainer
import com.livehub.app.di.UserSettings
import com.livehub.app.sync.shouldRefresh
import com.livehub.app.ui.MainViewModel
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.designsystem.LiveHubTheme
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * The 后台刷新 switch, the Wi-Fi restriction and the worker gate they drive.
 *
 * Three separate defects are pinned here, because the feature is only honest if all three hold:
 *
 *  * `shouldRefresh` is the whole decision, so its truth table is asserted directly — including
 *    the "cannot tell which network" answer, which must **allow** the refresh. Reading that as
 *    "not Wi-Fi" would silently disable the feature for anyone behind a VPN or an OEM network
 *    stack that reports nothing, and nobody would ever see an error.
 *  * the settings page has to *show* the two switches, send the intent when one is tapped, and
 *    keep the Wi-Fi row on screen but disabled while the feature is off. Hidden and disabled look
 *    the same in a screenshot-less test only if the row is asserted to still exist.
 *  * the preference has to reach disk. A switch whose value dies with the process is the exact
 *    defect this feature was opened for: the worker kept running with no way to stop it.
 *
 * The page is rendered inside [LiveHubTheme], as every screen test in this package has been since
 * round 85. The two switch rows are `bodyLarge` titles with `labelSmall` subtitles, which the app's
 * scale draws identically to Material's — but `the background refresh card sits above the storage
 * card` compares two y positions, and a position only means something in the scale that produced
 * it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class BackgroundRefreshTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val context: Context get() = ApplicationProvider.getApplicationContext()

  private lateinit var container: AppContainer

  @Before
  fun setUp() {
    container = AppContainer(context)
    // DataStore is one process-wide file, so a value another test persisted would decide what this
    // one starts from. Every test here begins at the shipped defaults.
    container.preferences.update { UserSettings() }
    container.preferences.awaitWrites()
  }

  @After
  fun tearDown() {
    // Guarded: if `setUp` itself failed there is nothing to reset, and an `UninitializedProperty`
    // thrown here would hide the real failure behind a second one.
    if (!::container.isInitialized) return
    container.preferences.update { UserSettings() }
    container.preferences.awaitWrites()
  }

  private fun idle() {
    shadowOf(Looper.getMainLooper()).idle()
  }

  // -------------------------------------------------------------- shouldRefresh ---

  @Test
  fun `with the switch on and no restriction every network is refreshed`() {
    assertTrue(
      "开关开着且不限制网络时，移动数据也要刷新",
      shouldRefresh(backgroundRefresh = true, wifiOnly = false, onWifi = false),
    )
    assertTrue(
      "开关开着且不限制网络时，Wi-Fi 要刷新",
      shouldRefresh(backgroundRefresh = true, wifiOnly = false, onWifi = true),
    )
  }

  @Test
  fun `with the switch on and Wi-Fi only the refresh follows the transport`() {
    assertTrue(
      "仅 Wi-Fi 且当前是 Wi-Fi 时必须刷新",
      shouldRefresh(backgroundRefresh = true, wifiOnly = true, onWifi = true),
    )
    assertFalse(
      "仅 Wi-Fi 且当前是移动数据时必须跳过",
      shouldRefresh(backgroundRefresh = true, wifiOnly = true, onWifi = false),
    )
  }

  /**
   * An unclassifiable network counts as allowed.
   *
   * This is the assertion that keeps the restriction from becoming a silent outage: `null` means
   * the platform would not say which transport is up (no active network yet, a VPN tunnel, a
   * restricted profile, a thrown exception), and the refresh ran on every network before this
   * limit existed. Failing closed here would have switched the feature off for those users with
   * nothing in the UI to explain it.
   */
  @Test
  fun `an unknown network is allowed rather than treated as mobile data`() {
    assertTrue(
      "判断不出网络时要放行（失败要开不要闭）",
      shouldRefresh(backgroundRefresh = true, wifiOnly = true, onWifi = null),
    )
  }

  /** The switch wins over every other input: off is off, whatever the network says. */
  @Test
  fun `the switch off stops the refresh on every network`() {
    listOf(null, true, false).forEach { onWifi ->
      listOf(true, false).forEach { wifiOnly ->
        assertFalse(
          "开关关掉后 wifiOnly=${wifiOnly} onWifi=${onWifi} 都不能刷新",
          shouldRefresh(backgroundRefresh = false, wifiOnly = wifiOnly, onWifi = onWifi),
        )
      }
    }
  }

  // ----------------------------------------------------------------- preferences ---

  /**
   * A settings record written before these two fields existed still reads — with the feature on.
   *
   * The stored JSON is what an older build wrote; the decode is the same lenient
   * (`ignoreUnknownKeys`) decode `UserPreferences` performs, and Kotlin's `@Serializable` fills a
   * missing field from its default. That default is the whole reason the upgrade does not turn a
   * working feature off for existing installations.
   */
  @Test
  fun `a settings record from before the switches existed decodes with refresh on`() {
    val stored = """{"themeMode":"dark","danmakuEnabled":false,"blockedKeywords":["广告"]}"""

    val decoded = Json { ignoreUnknownKeys = true }
      .decodeFromString(UserSettings.serializer(), stored)

    assertTrue("旧记录没有该字段时必须落到默认的开启", decoded.backgroundRefresh)
    assertFalse("旧记录必须落到默认的不限制网络", decoded.refreshOnWifiOnly)
    assertEquals("旧记录的其他字段不能被顺带重置", "dark", decoded.themeMode)
  }

  // ---------------------------------------------------------------- settings page ---

  private fun renderSettings(
    backgroundRefresh: Boolean = true,
    wifiOnly: Boolean = false,
    actions: MutableList<UiAction> = mutableListOf(),
  ) {
    composeRule.setContent {
      LiveHubTheme {
        SettingsScreen(
          state = UiState(
            screen = Screen.Settings,
            backgroundRefreshEnabled = backgroundRefresh,
            refreshOnWifiOnly = wifiOnly,
          ),
          onAction = { actions += it },
        )
      }
    }
    composeRule.waitForIdle()
  }

  /** The page is a `LazyColumn`, so a card below the fold is only composed once scrolled to. */
  private fun scrollTo(text: String) {
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
  }

  @Test
  fun `both background refresh switches are on the settings page`() {
    renderSettings()

    scrollTo("后台刷新开播状态")
    composeRule.onNodeWithText("后台刷新开播状态").assertIsDisplayed()
    // The switch shares its row with the title, so it is inside the viewport as soon as the title
    // is. The subtitle does not: `performScrollToNode` stops the moment its target becomes
    // visible, which can leave the line under it below the fold — asserting it without scrolling
    // to it first is how this test failed on its first CI run.
    composeRule.onNodeWithTag(BACKGROUND_REFRESH_SWITCH_TAG).assertIsDisplayed()
    scrollTo("每 30 分钟检查一次关注列表里谁开播了")
    composeRule.onNodeWithText("每 30 分钟检查一次关注列表里谁开播了").assertIsDisplayed()

    // Scrolled to separately so the second row is provably inside the viewport, not merely
    // composed: `assertIsDisplayed` fails on a node that is clipped by the fold.
    scrollTo("仅 Wi-Fi 下刷新")
    composeRule.onNodeWithText("仅 Wi-Fi 下刷新").assertIsDisplayed()
    composeRule.onNodeWithTag(WIFI_ONLY_SWITCH_TAG).assertIsDisplayed()
  }

  @Test
  fun `tapping the first switch asks for the feature to be turned off`() {
    val actions = mutableListOf<UiAction>()
    renderSettings(actions = actions)

    scrollTo("后台刷新开播状态")
    composeRule.onNodeWithTag(BACKGROUND_REFRESH_SWITCH_TAG).assertIsEnabled().performClick()
    composeRule.waitForIdle()

    assertEquals(
      "第一行开关打开状态下点一下必须发出关闭指令",
      listOf<UiAction>(UiAction.SetBackgroundRefresh(false)),
      actions,
    )
  }

  @Test
  fun `tapping the Wi-Fi switch sends its own action`() {
    val actions = mutableListOf<UiAction>()
    renderSettings(actions = actions)

    scrollTo("仅 Wi-Fi 下刷新")
    composeRule.onNodeWithTag(WIFI_ONLY_SWITCH_TAG).assertIsEnabled().performClick()
    composeRule.waitForIdle()

    assertEquals(
      "第二行开关必须发出仅 Wi-Fi 的指令",
      listOf<UiAction>(UiAction.SetRefreshOnWifiOnly(true)),
      actions,
    )
  }

  /** Off means disabled, not hidden: the choice stays visible and still applies when re-enabled. */
  @Test
  fun `the Wi-Fi switch is disabled but still shown while the feature is off`() {
    renderSettings(backgroundRefresh = false)

    scrollTo("仅 Wi-Fi 下刷新")
    composeRule.onNodeWithText("仅 Wi-Fi 下刷新").assertIsDisplayed()
    composeRule.onNodeWithTag(WIFI_ONLY_SWITCH_TAG).assertIsNotEnabled()
    composeRule.onNodeWithTag(BACKGROUND_REFRESH_SWITCH_TAG).assertIsEnabled()
  }

  @Test
  fun `the Wi-Fi switch is enabled while the feature is on`() {
    renderSettings(backgroundRefresh = true, wifiOnly = true)

    scrollTo("仅 Wi-Fi 下刷新")
    composeRule.onNodeWithTag(WIFI_ONLY_SWITCH_TAG).assertIsEnabled()
  }

  /**
   * The card sits above 存储.
   *
   * A tall viewport is used so both titles are composed at once and their vertical positions can
   * be compared; scrolling would only show one of them.
   */
  @Test
  @Config(sdk = [34], qualifiers = "w320dp-h2000dp")
  fun `the background refresh card sits above the storage card`() {
    renderSettings()

    val refreshY = composeRule.onNodeWithText("后台刷新开播状态")
      .fetchSemanticsNode().positionInRoot.y
    val storageY = composeRule.onNodeWithText("存储").fetchSemanticsNode().positionInRoot.y

    assertTrue("后台刷新卡（y=${refreshY}）必须在存储卡（y=${storageY}）之上", refreshY < storageY)
  }

  // ------------------------------------------------------------------ view model ---

  @Test
  fun `turning the feature off reaches the state and the disk`() {
    val viewModel = MainViewModel(container)
    idle()
    assertTrue("新装默认开启", viewModel.state.value.backgroundRefreshEnabled)

    viewModel.onAction(UiAction.SetBackgroundRefresh(false))
    idle()

    assertFalse("关掉之后状态必须立即为 false", viewModel.state.value.backgroundRefreshEnabled)

    container.preferences.awaitWrites()
    val restarted = AppContainer(context)
    assertFalse(
      "关闭状态必须落盘，否则重启后后台任务又回来了",
      restarted.preferences.settings.backgroundRefresh,
    )
  }

  @Test
  fun `the Wi-Fi only choice reaches the state and the disk`() {
    val viewModel = MainViewModel(container)
    idle()
    assertFalse("新装默认不限制网络", viewModel.state.value.refreshOnWifiOnly)

    viewModel.onAction(UiAction.SetRefreshOnWifiOnly(true))
    idle()

    assertTrue("打开之后状态必须立即为 true", viewModel.state.value.refreshOnWifiOnly)

    container.preferences.awaitWrites()
    val restarted = AppContainer(context)
    assertTrue(
      "仅 Wi-Fi 的选择必须落盘",
      restarted.preferences.settings.refreshOnWifiOnly,
    )
  }
}
