package com.livehub.app.ui.screens

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import com.livehub.app.di.MAX_BLOCKED_KEYWORDS
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.core.designsystem.LiveHubTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The blocked-keyword limit is visible, and reaching it is visible.
 *
 * The limit existed only inside `MainViewModel`: `.take(50)` on the way to the store. So the
 * fifty-first word cleared the input, added nothing and said nothing — a control that silently
 * discards input is indistinguishable from a broken one, and the card never mentioned a limit at all.
 *
 * Both halves are pinned here: the field states the limit while there is room for more, and it
 * becomes the explanation at the moment 添加 goes grey. A grey button with no reason next to it is
 * what round 141 fixed on the sync page.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class BlockedKeywordLimitTest {

  @get:Rule
  val composeRule = createComposeRule()

  private fun render(keywords: List<String>) {
    composeRule.setContent {
      LiveHubTheme {
        SettingsScreen(
          state = UiState(screen = Screen.Settings, blockedKeywords = keywords),
          onAction = {},
        )
      }
    }
    composeRule.waitForIdle()
  }

  private fun scrollTo(text: String) {
    composeRule.onNode(hasScrollAction()).performScrollToNode(hasText(text))
    composeRule.waitForIdle()
  }

  @Test
  fun `below the limit the field states it and how many are used`() {
    render(listOf("广告", "刷屏", "666"))

    scrollTo("最多 $MAX_BLOCKED_KEYWORDS 个，当前 3 个。")
    composeRule.onNodeWithText("最多 $MAX_BLOCKED_KEYWORDS 个，当前 3 个。").assertIsDisplayed()
    scrollTo("添加")
    // A word has to be in the field for 添加 to be live — see `the add button is off while the field is
    // empty` below. What this test is about is that the cap is not what is holding it back.
    typeDraft("剧组")
    composeRule.onNodeWithText("添加").assertIsEnabled()
  }

  @Test
  fun `at the limit adding is disabled and the field says why`() {
    render(List(MAX_BLOCKED_KEYWORDS) { "词$it" })

    val why = "已达上限 $MAX_BLOCKED_KEYWORDS 个，先移除一个才能再加。"
    scrollTo(why)

    composeRule.onNodeWithText(why).assertIsDisplayed()
    scrollTo("添加")
    // A word in the field, so the cap is the only reason the button is off.
    typeDraft("剧组")
    composeRule.onNodeWithText("添加").assertIsNotEnabled()
  }

  /** One below the limit is still "room for one more": the counter is what shows the edge. */
  @Test
  fun `one below the limit still allows adding`() {
    render(List(MAX_BLOCKED_KEYWORDS - 1) { "词$it" })

    scrollTo("添加")
    typeDraft("剧组")

    composeRule.onNodeWithText("添加").assertIsEnabled()
  }

  /**
   * With an empty field there is nothing to add, and the button says so.
   *
   * `enabled = !atCap` alone left 添加 looking live and doing nothing: the only thing stopping a tap was
   * the `isNotBlank` guard inside the body. Round 141 removed the same shape from the sync page — a
   * control that answers by not answering.
   */
  @Test
  fun `the add button is off while the field is empty`() {
    render(listOf("广告"))

    scrollTo("添加屏蔽词")
    composeRule.onNodeWithText("添加").assertIsNotEnabled()
  }

  /** Types into the one text field on this page and lets the recomposition land. */
  private fun typeDraft(word: String) {
    composeRule.onNode(hasSetTextAction()).performTextInput(word)
    composeRule.waitForIdle()
  }
}
