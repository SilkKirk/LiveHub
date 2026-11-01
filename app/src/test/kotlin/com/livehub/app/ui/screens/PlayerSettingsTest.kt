package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiState
import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.StreamVariant
import com.livehub.core.model.Streamer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The player settings panel has to stay usable in landscape.
 *
 * A `ModalBottomSheet` in landscape is capped at a fraction of an already short window, and
 * the panel is taller than that: 清晰度, 线路, 弹幕 on/off, 显示区域, 字号, 透明度 and the
 * landscape size row do not fit. There was no scrolling either, so everything below the
 * first couple of rows was unreachable — the settings existed but could not be changed.
 *
 * `qualifiers = "land"` is what makes `LocalConfiguration.orientation` report landscape, so
 * the panel also composes its landscape-only row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w640dp-h360dp-land")
class PlayerSettingsTest {

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

  private fun state() = UiState(
    platform = Platform.DOUYU,
    categories = listOf(Category(Platform.DOUYU, "86", "英雄联盟", "1", "网游")),
    room = room,
    variants = listOf(
      StreamVariant("原画", "https://e.com/a.flv", "ws-h5", qualityRank = 0, qualityCode = 0),
      StreamVariant("高清", "https://e.com/b.flv", "ws-h5", qualityRank = 2, qualityCode = 2),
    ),
    selectedVariant = StreamVariant("原画", "https://e.com/a.flv", "ws-h5", qualityRank = 0),
    lines = listOf("ws-h5", "ali"),
    selectedLine = "ws-h5",
  )

  /**
   * Constrains the panel to a landscape phone's height, then scrolls to the last option.
   *
   * `performScrollTo` fails if no scrollable ancestor exists, so this asserts the panel is
   * scrollable rather than merely that the node is in the tree (it is always in the tree —
   * that was the bug: composed, measured, drawn outside the visible window).
   */
  private fun composeInLandscapeHeight() {
    composeRule.setContent {
      // 360dp of height: what a 640x360 landscape phone leaves a sheet after system bars.
      Box(modifier = Modifier.width(640.dp).height(360.dp)) {
        PlayerSettings(state = state(), variant = state().selectedVariant, onAction = {})
      }
    }
    composeRule.waitForIdle()
  }

  @Test
  fun `the danmaku opacity row is reachable in landscape`() {
    composeInLandscapeHeight()

    composeRule.onNodeWithText("弹幕透明度").performScrollTo().assertExists()
  }

  @Test
  fun `the landscape font size row is reachable in landscape`() {
    composeInLandscapeHeight()

    composeRule.onNodeWithText("横屏弹幕字号").performScrollTo().assertExists()
  }

  @Test
  fun `quality and line rows are present`() {
    composeInLandscapeHeight()

    composeRule.onNodeWithText("清晰度").assertExists()
    composeRule.onNodeWithText("线路").assertExists()
  }
}
