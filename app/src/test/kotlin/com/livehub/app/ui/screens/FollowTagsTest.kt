package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Labels on the follow list: the chip row that filters by them, and the long-press editor.
 *
 * The chips sit in the same row as the live and platform filters, and a tag filters the grid the
 * same way those do. The editor is behind a long press because the card already carries a tap and a
 * star; a third visible control on a 140dp tile would cost more than the feature is worth.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class FollowTagsTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val tagged = Streamer(
    platform = Platform.DOUYU,
    roomId = "1",
    streamerName = "团团",
    title = "标题 1",
    isLive = true,
  )

  private val plain = Streamer(
    platform = Platform.DOUYU,
    roomId = "2",
    streamerName = "宝宝",
    title = "标题 2",
    isLive = true,
  )

  private fun state(tagFilter: String? = null) = UiState(
    screen = Screen.Follow,
    followed = listOf(tagged, plain),
    followTags = mapOf(tagged.key to listOf("常看")),
    followTagFilter = tagFilter,
  )

  /**
   * A screen whose state actually changes when a chip is tapped.
   *
   * The filter lives in `UiState`, so a frozen state would leave the grid unfiltered and the
   * assertion would be hunting a bug that the test itself created.
   */
  private fun render(initial: UiState): MutableState<UiState> {
    val state = mutableStateOf(initial)
    composeRule.setContent {
      Box(modifier = Modifier.size(320.dp, 640.dp)) {
        FollowScreen(
          state = state.value,
          onAction = { action ->
            state.value = when (action) {
              is UiAction.SetFollowTagFilter -> state.value.copy(followTagFilter = action.tag)
              is UiAction.SetFollowQuery -> state.value.copy(followQuery = action.query)
              else -> state.value
            }
          },
        )
      }
    }
    composeRule.waitForIdle()
    return state
  }

  @Test
  fun `a tagged room gets a chip, and the chip narrows the grid`() {
    render(state())

    composeRule.onNodeWithText("#常看").assertIsDisplayed()
    composeRule.onNodeWithText("#常看").performClick()
    composeRule.waitForIdle()

    composeRule.onNodeWithText("标题 1").assertIsDisplayed()
    composeRule.onNodeWithText("标题 2").assertDoesNotExist()
  }

  /** No tags in use: no chips, and nothing else in the row changes because of them. */
  @Test
  fun `a list without tags draws no tag chips`() {
    render(state().copy(followTags = emptyMap()))

    composeRule.onNodeWithText("#常看").assertDoesNotExist()
    composeRule.onNodeWithText("标题 1").assertIsDisplayed()
  }

  @Test
  fun `long pressing a card opens the tag editor`() {
    render(state())

    composeRule.onNodeWithText("标题 1").performTouchInput { longClick() }
    composeRule.waitForIdle()

    composeRule.onNodeWithText("完成").assertIsDisplayed()
    composeRule.onNodeWithText("标签只用于关注页筛选，会随关注列表一起同步到已配对的设备。")
      .assertIsDisplayed()
  }

  @Test
  fun `an empty tag filter names the tag`() {
    render(state(tagFilter = "不存在"))

    composeRule.onNodeWithText("没有带「不存在」的主播").assertIsDisplayed()
    composeRule.onNodeWithText("清空筛选").assertIsDisplayed()
  }
}
