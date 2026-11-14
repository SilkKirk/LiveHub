package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.designsystem.LiveHubTheme
import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The player's chrome is built from the official Material 3 components, and says so to accessibility.
 *
 * Round 103 replaced four hand-rolled controls with the components that already were them: the pills
 * (`Surface` inside a `Box` with `Modifier.clickable`) became `Button`, the round side buttons and
 * the floating arrow became `IconButton`, and the full-screen star became `IconToggleButton`. What
 * was written by hand for them is what these assertions pin: the 48dp target (drawn as a ring around
 * a ~34dp pill), the `Role.Button` a `Modifier.clickable` does not report at all, and the fact that
 * the node carrying the content description is the node a finger hits.
 *
 * The star carried a second, worse defect: it described itself as 关注 whether the room was followed
 * or not, so TalkBack offered to follow a room the user had already followed. As a toggle it now has
 * a state (`assertIsOff` / `assertIsOn`) as well as an action, and the two can be checked apart.
 *
 * The danmaku switch is the same story one step further: two chips labelled 开 and 关 held a setting
 * that the 设置 page already exposed as a `Switch`, so one stored value had two different controls
 * depending on the screen — and a chip cannot report "on" the way a switch does.
 *
 * Sizes are read off `boundsInRoot` and converted through `composeRule.density`, so a failure says
 * how many dp the target actually got instead of only that it was too small. Every render goes
 * through [LiveHubTheme]: a pill's height comes from `labelLarge`, so the type scale is part of what
 * decides whether 48dp is reached.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class PlayerControlsTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val room = Streamer(
    platform = Platform.DOUYU,
    roomId = "1",
    streamerName = "一条小团团",
    title = "今天继续冲分",
    viewerCount = 128_500,
    isLive = true,
  )

  private val variant = StreamVariant(
    qualityLabel = "原画",
    url = "https://example.com/a.flv",
    lineName = "ws-h5",
    qualityRank = 0,
    qualityCode = 0,
  )

  /** A playing room; [followed] and [danmakuEnabled] are the two settings the controls render. */
  private fun uiState(followed: Boolean = false, danmakuEnabled: Boolean = true) = UiState(
    screen = Screen.Player,
    platform = Platform.DOUYU,
    categories = listOf(Category(Platform.DOUYU, "86", "英雄联盟", "1", "网游")),
    room = room,
    variants = listOf(variant),
    selectedVariant = variant,
    lines = listOf("ws-h5", "ali"),
    selectedLine = "ws-h5",
    followed = if (followed) listOf(room) else emptyList(),
    danmakuEnabled = danmakuEnabled,
  )

  /**
   * Renders the player body with a stand-in video surface.
   *
   * The same substitution `PlayerChromeTest` makes: the real surface builds a Media3 decoder, which
   * no JVM test can do. The clock is frozen because the chrome hides itself four seconds into a
   * playing stream, and the controls under test are exactly the ones that would have gone with it.
   */
  private fun renderPlayer(
    state: UiState = uiState(),
    fullscreen: Boolean = false,
    onAction: (UiAction) -> Unit = {},
  ) {
    composeRule.mainClock.autoAdvance = false
    composeRule.setContent {
      LiveHubTheme {
        PlayerContent(
          state = state,
          onAction = onAction,
          fullscreen = fullscreen,
          onFullscreenChange = {},
          onOpenSettings = {},
          videoSurface = { surfaceModifier -> Box(surfaceModifier.fillMaxSize()) },
        )
      }
    }
    composeRule.mainClock.advanceTimeByFrame()
    composeRule.waitForIdle()
  }

  /**
   * Renders the settings sheet on its own, which is where the danmaku switch lives.
   *
   * `PlayerSettings` is `internal` so a test can compose the panel without a rotation; reaching it
   * through the whole player would also mean opening a `ModalBottomSheet`.
   */
  private fun renderSettings(state: UiState, onAction: (UiAction) -> Unit) {
    composeRule.setContent {
      LiveHubTheme {
        PlayerSettings(state = state, variant = state.selectedVariant, onAction = onAction)
      }
    }
    composeRule.waitForIdle()
  }

  /**
   * Asserts one control is a Material button *and* that what a finger hits is at least 48dp.
   *
   * The role is half the assertion, not a formality: a `Box` with a `Modifier.clickable` is a
   * control the user can press and not a button to accessibility services, which is what these all
   * used to be. The size is the other half — 48dp was a ring the boxes drew around themselves, and
   * `Button`/`IconButton` enforce it on their own.
   */
  private fun assertOfficialButton(label: String, control: SemanticsMatcher) {
    composeRule.onNode(
      control and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
    ).assertExists()

    val bounds = composeRule.onNode(control).fetchSemanticsNode().boundsInRoot
    with(composeRule.density) {
      val width = bounds.width.toDp().value
      val height = bounds.height.toDp().value
      assertTrue(
        "「$label」is ${width}x${height}dp; a Material button's target is at least 48dp",
        width >= 48f && height >= 48f,
      )
    }
  }

  @Test
  fun `the portrait chrome is official buttons with 48dp targets`() {
    renderPlayer()

    // The way out of the player, the three round controls down the right edge, and the pill in the
    // action row underneath the picture.
    assertOfficialButton("返回", hasContentDescription("返回"))
    assertOfficialButton("播放设置", hasContentDescription("播放设置"))
    assertOfficialButton("刷新", hasContentDescription("刷新"))
    assertOfficialButton("全屏", hasContentDescription("全屏"))
    assertOfficialButton("关注", hasText("关注"))
  }

  @Test
  fun `the full screen bar is official buttons with 48dp targets`() {
    renderPlayer(fullscreen = true)

    // 退出全屏 is both the bar's back arrow and the side column's exit button in this window. They
    // were two separate hand-rolled boxes, so both are asserted rather than one of them.
    composeRule.onAllNodes(
      hasContentDescription("退出全屏") and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
    ).assertCountEquals(2)

    val exits = composeRule.onAllNodesWithContentDescription("退出全屏").fetchSemanticsNodes()
    assertEquals("expected the bar arrow and the side button, found ${exits.size}", 2, exits.size)
    exits.forEach { node ->
      val bounds = with(composeRule.density) {
        node.boundsInRoot.width.toDp().value to node.boundsInRoot.height.toDp().value
      }
      assertTrue(
        "an exit control is ${bounds.first}x${bounds.second}dp; the target is at least 48dp",
        bounds.first >= 48f && bounds.second >= 48f,
      )
    }
  }

  @Test
  fun `the follow star is a toggle that offers 关注 on an unfollowed room`() {
    val actions = mutableListOf<UiAction>()
    renderPlayer(fullscreen = true, onAction = { actions += it })

    composeRule.onNodeWithContentDescription("关注").assertIsOff()
    composeRule.onNodeWithContentDescription("关注").performClick()
    composeRule.waitForIdle()

    assertEquals(
      "tapping 关注 must ask for the follow, got $actions",
      listOf<UiAction>(UiAction.ToggleFollow(room)),
      actions,
    )
  }

  /**
   * The accessibility lie this replaces: the bar described itself as 关注 in both states.
   *
   * A followed room therefore offered to follow itself, and the only hint that it was already
   * followed was the filled glyph — which TalkBack cannot read.
   */
  @Test
  fun `the follow star says 取消关注 once the room is followed`() {
    val actions = mutableListOf<UiAction>()
    renderPlayer(fullscreen = true, state = uiState(followed = true), onAction = { actions += it })

    assertEquals(
      "a followed room must not be described as something to follow",
      0,
      composeRule.onAllNodesWithContentDescription("关注").fetchSemanticsNodes().size,
    )
    composeRule.onNodeWithContentDescription("取消关注").assertIsOn()
    composeRule.onNodeWithContentDescription("取消关注").performClick()
    composeRule.waitForIdle()

    assertEquals(
      "unfollowing is the same toggle, got $actions",
      listOf<UiAction>(UiAction.ToggleFollow(room)),
      actions,
    )
  }

  @Test
  fun `the danmaku setting is one official switch, not two chips`() {
    val actions = mutableListOf<UiAction>()
    renderSettings(state = uiState(), onAction = { actions += it })

    // Exactly one node in the panel reports the Switch role: the official control, not a chip row
    // that looks like a setting.
    composeRule.onAllNodes(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Switch))
      .assertCountEquals(1)

    val switch = composeRule.onNodeWithTag(DANMAKU_SWITCH_TAG).performScrollTo()
    switch.assertIsOn()

    // The two chips this replaced: 开 and 关, one of them always selected, neither of them a switch
    // to a screen reader.
    assertEquals(
      "the 开/关 chips must be gone, found ${composeRule.onAllNodesWithText("开").fetchSemanticsNodes().size}",
      0,
      composeRule.onAllNodesWithText("开").fetchSemanticsNodes().size,
    )
    assertEquals(
      "the 开/关 chips must be gone, found ${composeRule.onAllNodesWithText("关").fetchSemanticsNodes().size}",
      0,
      composeRule.onAllNodesWithText("关").fetchSemanticsNodes().size,
    )

    switch.performClick()
    composeRule.waitForIdle()

    assertEquals(
      "tapping a switch that is on must ask for it to be off, got $actions",
      listOf<UiAction>(UiAction.ToggleDanmaku(false)),
      actions,
    )
  }

  /** The other direction, which is the state a user who turned danmaku off comes back to. */
  @Test
  fun `the danmaku switch is off when the setting is off`() {
    val actions = mutableListOf<UiAction>()
    renderSettings(state = uiState(danmakuEnabled = false), onAction = { actions += it })

    composeRule.onNodeWithTag(DANMAKU_SWITCH_TAG).performScrollTo().assertIsOff().performClick()
    composeRule.waitForIdle()

    assertEquals(
      "tapping a switch that is off must ask for it to be on, got $actions",
      listOf<UiAction>(UiAction.ToggleDanmaku(true)),
      actions,
    )
  }
}
