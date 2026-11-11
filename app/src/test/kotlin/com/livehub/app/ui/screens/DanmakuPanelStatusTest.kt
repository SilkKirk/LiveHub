package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.DanmakuStatus
import com.livehub.app.ui.UiState
import com.livehub.core.model.DanmakuMessage
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * An empty danmaku panel has to say *why* it is empty.
 *
 * The ViewModel used to match `DanmakuEvent.Message` and send every other event to `else -> Unit`,
 * so a socket that never connected rendered exactly like a quiet room: "正在连接弹幕…" with a
 * spinner, forever. All four adapters emit `Failed` and `Disconnected` with a real reason; nothing
 * read them.
 *
 * These pin the four states the panel can now be in, because "the text changed" is not something
 * a screenshot can distinguish from "the text never changes".
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DanmakuPanelStatusTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun state(status: DanmakuStatus, messages: List<DanmakuMessage> = emptyList()) = UiState(
    platform = Platform.DOUYU,
    room = Streamer(
      platform = Platform.DOUYU,
      roomId = "1",
      streamerName = "一条小团团",
      title = "今天继续冲分",
      isLive = true,
    ),
    danmaku = messages,
    danmakuStatus = status,
  )

  private fun compose(state: UiState) {
    composeRule.setContent {
      Box(modifier = Modifier.size(320.dp, 320.dp)) {
        DanmakuPanel(state = state, modifier = Modifier.size(320.dp, 320.dp))
      }
    }
    composeRule.waitForIdle()
  }

  /** A failure shows the platform's own reason, not a generic spinner. */
  @Test
  fun `a failed connection shows the reason`() {
    compose(state(DanmakuStatus.Failed("握手被拒绝")))

    composeRule.onNodeWithText("弹幕连接失败：握手被拒绝").assertIsDisplayed()
  }

  /** The old behaviour: the failure text must *replace* the spinner, not sit beside it. */
  @Test
  fun `a failed connection no longer claims it is still connecting`() {
    compose(state(DanmakuStatus.Failed("403")))

    composeRule.onNodeWithText("正在连接弹幕…").assertDoesNotExist()
    composeRule.onNodeWithText("弹幕连接失败：403").assertIsDisplayed()
  }

  /** While the adapter retries by itself the panel stays quiet about it. */
  @Test
  fun `a reconnecting socket says so without calling it a failure`() {
    compose(state(DanmakuStatus.Reconnecting("连接中断")))

    composeRule.onNodeWithText("弹幕正在重连…").assertIsDisplayed()
    composeRule.onNodeWithText("弹幕连接失败：连接中断").assertDoesNotExist()
  }

  /**
   * A healthy but silent room is a distinct state.
   *
   * "正在连接弹幕…" on a connection that is up would be a lie the user cannot disprove; saying
   * nobody has spoken yet is the truth.
   */
  @Test
  fun `a connected but silent room says nobody has spoken yet`() {
    compose(state(DanmakuStatus.Connected))

    composeRule.onNodeWithText("还没有人说话").assertIsDisplayed()
    composeRule.onNodeWithText("正在连接弹幕…").assertDoesNotExist()
  }

  /** The genuine "still opening" state keeps its spinner. */
  @Test
  fun `a socket that is still opening keeps the spinner`() {
    compose(state(DanmakuStatus.Connecting))

    composeRule.onNodeWithText("正在连接弹幕…").assertIsDisplayed()
  }

  /**
   * Messages win over the status.
   *
   * A drop *after* chat arrived must not wipe the list the user is reading: the status is only
   * consulted while the list is empty.
   */
  @Test
  fun `an existing conversation survives a later disconnect`() {
    compose(
      state(
        status = DanmakuStatus.Reconnecting("连接中断"),
        messages = listOf(
          DanmakuMessage(roomKey = "douyu:1", userName = "观众A", text = "这波操作太秀了"),
        ),
      ),
    )

    composeRule.onNodeWithText("这波操作太秀了").assertIsDisplayed()
    composeRule.onNodeWithText("弹幕正在重连…").assertDoesNotExist()
  }
}
