package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.designsystem.LiveHubTheme
import com.livehub.core.sync.DiscoveredPeer
import com.livehub.core.sync.SyncLogEntry
import com.livehub.core.sync.TrustedPeer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The hand-rolled rows of the sync page after they became official `ListItem`s (round 103).
 *
 * Swapping a `Row { Column { title; subtitle } ; control }` for a component is not a refactor the
 * compiler can be trusted to get right, because the things that can break are exactly the things
 * that are no longer written down in the layout:
 *
 *  * the 自动同步 `Switch` keeps its `testTag` *and* stays the enabled control a tap reaches —
 *    `SyncAutoCardTest` found it by that tag before this change and has to keep finding it;
 *  * a paired peer still names itself and still offers 解除关联, which still reports the peer's id;
 *  * a log entry's direction — a tinted `↑`/`↓` character until now — is in the semantics tree, and
 *    so is the fact that a transfer failed, which nothing in the row's own text has to say;
 *  * a discovered device is still the two lines it was: the name and where it was found.
 *
 * One thing about the new rows is load-bearing for the assertions below: `ListItem` merges its own
 * descendants, so a row's headline and its supporting line live on **one** node — `onNodeWithText`
 * returns the row, not a bare line — while the switch and the 解除关联 button merge separately and
 * stay addressable on their own. That asymmetry is why the switch is checked to be inside its row's
 * bounds instead of being asserted as part of the row's text.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class SyncRowsTest {

  @get:Rule
  val composeRule = createComposeRule()

  /**
   * The 自动同步 explanation, used as the scroll target.
   *
   * The deepest part of the row on purpose: scrolling until the headline is visible would leave the
   * switch clipped by the bottom edge, and an `assertIsDisplayed` on a clipped node is a coin toss.
   */
  private val autoSyncSubtitle = "应用打开时与已配对设备同步一次；服务端只在前台运行。"

  /**
   * A page with one paired peer, one discovered-but-unpaired device and a log of three rows: one
   * successful send, one receive, and one send that failed.
   *
   * `pairingCodeSecondsLeft = 0` is load-bearing for the reason `SyncAutoCardTest` gives: `SyncScreen`
   * derives the countdown in a `LaunchedEffect` that loops while there is time left, so a non-zero
   * value keeps the composition busy and `waitForIdle` never returns.
   *
   * The log holds exactly one *successful* send and one receive on purpose: the direction assertions
   * count nodes by content description, and a second successful send would make 「发送」 mean "at
   * least one" rather than "the send". The third row is the failed one — its description is
   * 「发送，失败」, so the two sends stay distinguishable, which is what makes the failure assertion
   * mean something: a fixture of successes alone cannot tell "a failure is announced" apart from the
   * old behaviour, where the red tint was the only sign.
   */
  private fun state(autoSync: Boolean = true) = UiState(
    screen = Screen.Sync,
    deviceName = "Pixel 7",
    localAddress = "192.168.1.23:47653",
    pairingCode = "482913",
    pairingCodeSecondsLeft = 0,
    autoSyncEnabled = autoSync,
    peers = listOf(
      TrustedPeer(PEER_ID, PEER_NAME, "secret", 1_700_000_000_000, lastSeenAt = 1_700_000_100_000),
    ),
    discoveredPeers = listOf(DiscoveredPeer(9L, DISCOVERED_NAME, "192.168.1.40", 47653)),
    syncLog = listOf(
      SyncLogEntry(1_700_000_080_000, SyncLogEntry.Direction.PUSH, DISCOVERED_NAME, "关注列表", 3, true),
      SyncLogEntry(1_700_000_090_000, SyncLogEntry.Direction.PULL, PEER_NAME, "关注列表", 2, true),
      SyncLogEntry(
        1_700_000_095_000, SyncLogEntry.Direction.PUSH, FAILED_PEER, "关注列表", 0, false,
        "对方拒绝连接",
      ),
    ),
  )

  /**
   * Renders the page in a 320×640 box — the smallest phone the app targets — and tags that box, so
   * "on screen" below means inside what the screen was given rather than inside whatever window the
   * test happens to run in.
   *
   * Composed inside [LiveHubTheme] because these rows are laid out by Material's type scale and
   * 56/72dp minimum heights, which the theme is what supplies.
   */
  private fun render(state: UiState, onAction: (UiAction) -> Unit = {}) {
    composeRule.setContent {
      LiveHubTheme {
        Box(modifier = Modifier.size(320.dp, 640.dp).testTag(VIEWPORT_TAG)) {
          SyncScreen(state = state, onAction = onAction)
        }
      }
    }
    composeRule.waitForIdle()
  }

  /** The page is a `LazyColumn`: a row below the fold is not composed until it is scrolled to. */
  private fun scrollTo(text: String) {
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    composeRule.waitForIdle()
  }

  /**
   * The switch is still the control of the 自动同步 row, and it still reports a tap.
   *
   * `SyncAutoCardTest` pins the same tag and reports; this adds the part that the component swap
   * could break silently — the switch was moved into `trailingContent`, so it must still be found by
   * its tag, still be on the row's bounds, and still be off when the setting is off.
   */
  @Test
  fun `the auto sync switch still sits on its row and still reports a tap`() {
    val actions = mutableListOf<UiAction>()
    render(state(autoSync = false), onAction = { actions += it })
    scrollTo(autoSyncSubtitle)

    composeRule.onNodeWithText("自动同步").assertIsDisplayed()
    composeRule.onNodeWithTag(AUTO_SYNC_SWITCH_TAG).assertIsOff().assertIsDisplayed().performClick()
    composeRule.waitForIdle()

    val page = composeRule.onNodeWithTag(VIEWPORT_TAG).fetchSemanticsNode().boundsInRoot
    val row = composeRule.onNodeWithText("自动同步").fetchSemanticsNode().boundsInRoot
    val switch = composeRule.onNodeWithTag(AUTO_SYNC_SWITCH_TAG).fetchSemanticsNode().boundsInRoot
    assertTrue("the row is above the top edge: $row vs $page", row.top >= page.top)
    assertTrue("the row is below the bottom edge: $row vs $page", row.bottom <= page.bottom)
    assertTrue(
      "the switch is not on its row: $switch vs $row",
      switch.left >= row.left && switch.right <= row.right &&
        switch.top >= row.top && switch.bottom <= row.bottom,
    )

    assertEquals(
      "点自动同步开关必须发出取反的 SetAutoSync",
      listOf<UiAction>(UiAction.SetAutoSync(true)),
      actions,
    )
  }

  /** A peer row still says who it is and still carries its 解除关联 button. */
  @Test
  fun `a paired peer keeps its name and its unpair button`() {
    val actions = mutableListOf<UiAction>()
    render(state(), onAction = { actions += it })
    scrollTo(PEER_NAME)

    composeRule.onNodeWithText(PEER_NAME).assertIsDisplayed()
    composeRule.onNodeWithContentDescription("解除关联").assertIsDisplayed().performClick()
    composeRule.waitForIdle()

    assertEquals(
      "点解除关联必须把该设备的 id 交给 UnpairPeer",
      listOf<UiAction>(UiAction.UnpairPeer(PEER_ID)),
      actions,
    )
  }

  /**
   * A log entry's direction and its failure are readable, not only visible.
   *
   * The row used to draw `↑` or `↓` in the primary-or-error colour with no description at all, so
   * both facts it carries — which way the records went, and whether it worked — existed for sighted
   * users only. The failure half is the one the row's own text cannot cover: the direction is
   * already spelled out in the supporting line ("发送 3 条"), while a failed transfer is a red tint
   * and, at best, a note that may or may not say why.
   *
   * The counts are what make these assertions exact rather than "something is described": one
   * successful send, one receive and one failed send means each description names exactly one row.
   */
  @Test
  fun `the log direction and the failure are both in the semantics tree`() {
    render(state())

    scrollTo(PUSH_ROW)
    composeRule.onNodeWithContentDescription("发送").assertExists()
    assertEquals(
      "一条 PUSH 只该有一个「发送」节点",
      1,
      composeRule.onAllNodesWithContentDescription("发送").fetchSemanticsNodes().size,
    )

    scrollTo(PULL_ROW)
    composeRule.onNodeWithContentDescription("接收").assertExists()
    assertEquals(
      "一条 PULL 只该有一个「接收」节点",
      1,
      composeRule.onAllNodesWithContentDescription("接收").fetchSemanticsNodes().size,
    )

    // The failed send. Before this its description was the bare 「发送」, exactly like the successful
    // row's, so no node carrying 「发送，失败」 existed and this assertion could not have passed.
    scrollTo(FAILED_ROW)
    composeRule.onNodeWithContentDescription("发送，失败").assertExists()
    assertEquals(
      "失败的那一行必须自己说明失败，且只该有一个",
      1,
      composeRule.onAllNodesWithContentDescription("发送，失败").fetchSemanticsNodes().size,
    )
  }

  /** A discovered device is still a two-line row: the name, and where discovery found it. */
  @Test
  fun `a discovered device is a row with its address`() {
    render(state())
    scrollTo(DISCOVERED_NAME)

    composeRule.onNodeWithText(DISCOVERED_NAME).assertIsDisplayed()
    composeRule.onNodeWithText(DISCOVERED_ADDRESS).assertIsDisplayed()
  }

  private companion object {
    /** The page's own box, so "inside the window" means inside what the screen was given. */
    const val VIEWPORT_TAG = "syncRowsViewport"

    /** Ids are `Long` on the models; the literals are written as such to keep the calls typed. */
    const val PEER_ID = 2L

    const val PEER_NAME = "小米平板 6"

    const val DISCOVERED_NAME = "客厅电视"

    const val DISCOVERED_ADDRESS = "192.168.1.40:47653"

    /** The peer of the failed transfer, kept apart from the other two so each row's text is unique. */
    const val FAILED_PEER = "书房电脑"

    /** `SyncLogRow` renders a row as `"${entry.scope} · ${entry.peerName}"`. */
    const val PUSH_ROW = "关注列表 · 客厅电视"

    const val PULL_ROW = "关注列表 · 小米平板 6"

    const val FAILED_ROW = "关注列表 · 书房电脑"
  }
}
