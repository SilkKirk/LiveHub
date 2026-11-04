package com.livehub.app.di

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ApplicationProvider
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.app.ui.screens.SyncScreen
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
 * The follow list is the only thing that synchronises.
 *
 * Make no mistake about what this replaced: the sync page used to show one switch per
 * `SyncScope` entry, three of them. One had no `SyncTarget` at all (`history`), one had a
 * target whose record set nothing ever wrote to (`preferences`), and both were removed by
 * decision — the follow list is the only data whose value is destroyed by owning two devices.
 *
 * Three assertions, one per layer that could bring a second scope back: the registry, the
 * engine, and the screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FollowsOnlySyncTest {

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
  fun `the follow list is the only scope the app exchanges`() {
    assertEquals(listOf(SyncScope.FOLLOWS), container.syncTargets.available().map { it.scope })
    assertEquals(setOf(SyncScope.FOLLOWS), container.syncEngine.enabledScopes())
    // The enum is the contract, not just this registry: a value here without a target is
    // exactly how 搜索历史 spent several rounds as a switch that did nothing.
    assertEquals(listOf("follows"), SyncScope.entries.map { it.wireName })
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

    assertNotNull("the only scope must still produce a payload", payload)
    val records = SyncCodec.decodeRecords(payload!!.entries)
    assertTrue(records.any { it.key == streamer.key })
  }

  /**
   * The page says what it syncs instead of offering a switch.
   *
   * `pairingCodeSecondsLeft = 0` keeps the countdown effect from suspending for four minutes
   * of virtual time while `waitForIdle` settles — the same trick `LongListReachabilityTest`
   * uses.
   */
  @Test
  fun `the sync screen names the follow list and offers no scope switch`() {
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

    // Existence, not geometry: the point is which scopes the page names, and the smallest
    // phone this project renders (320x470dp by default here) puts the card at the fold.
    composeRule.onNodeWithText("关注列表").assertExists()
    composeRule.onNodeWithText("播放与弹幕设置").assertDoesNotExist()
    composeRule.onNodeWithText("搜索历史").assertDoesNotExist()
  }
}
