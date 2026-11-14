package com.livehub.app.di

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.app.ui.screens.SyncScreen
import com.livehub.core.model.LibraryKey
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import com.livehub.core.sync.SyncCodec
import com.livehub.core.sync.SyncScope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What the app exchanges between two installs.
 *
 * Round 32 cut the scope list to the follow list alone, because two of the three scopes it
 * had never worked. Round 44 adds the login state back — deliberately, and for a different
 * reason: it is the second thing a user should not have to repeat on a second device, and the
 * user asked for it. The scopes it removed (search history, danmaku settings) stay removed.
 *
 * Three layers are pinned here: the registry, the engine, and the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncScopesTest {

  @get:Rule
  val composeRule = createComposeRule()

  private lateinit var container: AppContainer

  @Before
  fun setUp() {
    container = AppContainer(ApplicationProvider.getApplicationContext())
    // DataStore is a process-wide file, so a previous test's state would leak in.
    container.preferences.update { UserSettings() }
    container.followStore.replaceAll(emptyList())
  }

  @Test
  fun `the app exchanges the follow list and the login state`() {
    assertEquals(
      listOf(SyncScope.FOLLOWS, SyncScope.CREDENTIALS),
      container.syncTargets.available().map { it.scope },
    )
    assertEquals(
      setOf(SyncScope.FOLLOWS, SyncScope.CREDENTIALS),
      container.syncEngine.enabledScopes(),
    )
    // The enum is the contract, not just this registry: a value here without a target is
    // exactly how 搜索历史 spent several rounds as a switch that did nothing.
    assertEquals(listOf("follows", "credentials"), SyncScope.entries.map { it.wireName })
  }

  /** Dropping the other scopes must not have touched the one that works. */
  @Test
  fun `a followed room still reaches the peer payload`() {
    val streamer = Streamer(
      platform = Platform.DOUYU,
      roomId = "999999",
      streamerName = "测试主播",
      title = "测试直播间",
      isLive = true,
    )
    container.followStore.toggle(
      streamer = streamer,
      hlc = container.syncEngine.nextHlc(),
      deviceId = container.preferences.deviceId,
      now = 1_700_000_000_000L,
    )

    val payload = container.syncEngine.buildPayload(SyncScope.FOLLOWS)

    assertNotNull("the follow scope must still produce a payload", payload)
    val records = SyncCodec.decodeRecords(payload!!.entries)
    assertTrue(records.any { it.key == LibraryKey.Room(Platform.DOUYU, "999999").encode() })
  }

  /**
   * A follow list longer than one payload is split, and every record still arrives.
   *
   * Chunking predates this scope growing, but nothing exercised it for the follow list — the one
   * scope that can realistically hold hundreds of records.
   */
  @Test
  fun `a follow list larger than one payload is split and every record still arrives`() {
    val deviceId = container.preferences.deviceId
    val hlc = container.syncEngine.nextHlc()
    val rooms = (0 until 600).map { index ->
      Streamer(platform = Platform.DOUYU, roomId = "$index", streamerName = "主播$index", isLive = true)
    }
    rooms.forEach { container.followStore.toggle(it, hlc, deviceId, 1_700_000_000_000L) }

    val payloads = container.syncEngine.buildPayloads(SyncScope.FOLLOWS)

    assertTrue("must be split into more than one payload", payloads.size > 1)
    val delivered = payloads.flatMap { SyncCodec.decodeRecords(it.entries) }.map { it.key }.toSet()
    assertEquals(600, delivered.size)
    assertTrue(delivered.contains(LibraryKey.Room(Platform.DOUYU, "599").encode()))
  }

  /** A login is a payload too, so the peer has something to merge. */
  @Test
  fun `a stored login produces a credentials payload`() {
    container.credentialStore.set(
      platform = Platform.BILIBILI,
      header = "SESSDATA=abc; bili_jct=def",
      hlc = container.syncEngine.nextHlc(),
      deviceId = container.preferences.deviceId,
      now = 1_700_000_000_000L,
    )

    val payload = container.syncEngine.buildPayload(SyncScope.CREDENTIALS)

    assertNotNull("the login scope must produce a payload", payload)
    val records = SyncCodec.decodeRecords(payload!!.entries)
    assertEquals(1, records.size)
    assertEquals("login:bilibili", records.single().key)
  }

  /**
   * The page names what it syncs instead of offering a switch.
   *
   * `pairingCodeSecondsLeft = 0` keeps the countdown effect from suspending for four minutes
   * of virtual time while `waitForIdle` settles — the same trick `LongListReachabilityTest`
   * uses.
   */
  @Test
  fun `the sync screen names both scopes and offers no scope switch`() {
    composeRule.setContent {
      MaterialTheme {
        SyncScreen(
          state = UiState(
            screen = Screen.Sync,
            pairingCode = "482913",
            pairingCodeSecondsLeft = 0,
          ),
          onAction = {},
        )
      }
    }
    composeRule.waitForIdle()

    // The page gained a masthead in round 104, so the 同步内容 card is now a scroll away on the
    // default Robolectric window instead of sitting just above the fold.
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasText("关注列表 · 登录状态"))
    composeRule.onNodeWithText("关注列表 · 登录状态").assertExists()
    composeRule.onNodeWithText("播放与弹幕设置").assertDoesNotExist()
    composeRule.onNodeWithText("搜索历史").assertDoesNotExist()
  }
}
