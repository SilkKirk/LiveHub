package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiState
import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Two things the reference screenshots show that our cards did not.
 *
 * Both come from the visual pass over `_ref/dtv_mobile/screenshots/`:
 *
 *  * `douyu.jpeg` / `b.jpeg` never print the platform on a room tile — the platform *is* the
 *    page there, and it is already named by the switcher above the grid. Ours repeated it on
 *    every card, which cost the streamer name horizontal room for no information.
 *  * `home.jpeg` (the reference's 关注列表) prints the audience figure on every followed room.
 *    Ours was the one list in the app that dropped it, although the follow store has always
 *    carried `viewerCount` and the home grid and search rows both render it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class CardInformationDensityTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val categories = listOf(
    Category(Platform.DOUYU, "86", "英雄联盟", "86", "网游", sortOrder = 0),
    Category(Platform.DOUYU, "2", "绝地求生", "2", "网游", sortOrder = 1),
  )

  private fun streamer(
    name: String,
    viewers: Long,
    live: Boolean = true,
    platform: Platform = Platform.DOUYU,
  ) = Streamer(
    platform = platform,
    roomId = name,
    streamerName = name,
    title = "直播标题 $name",
    viewerCount = viewers,
    isLive = live,
  )

  private fun composeInSmallWindow(content: @Composable () -> Unit) {
    composeRule.setContent {
      MaterialTheme {
        Box(modifier = Modifier.size(320.dp, 640.dp)) { content() }
      }
    }
    composeRule.waitForIdle()
  }

  @Test
  fun `the home grid does not repeat the platform on every card`() {
    val state = UiState(
      screen = Screen.Home,
      platform = Platform.DOUYU,
      categories = categories,
      selectedCategory = categories.first(),
      rooms = listOf(streamer("主播甲", 129_000), streamer("主播乙", 96_000)),
    )

    composeInSmallWindow { HomeScreen(state = state, onAction = {}) }

    // One 斗鱼: the switcher. With the chip on each tile this was 1 + the number of cards,
    // which is also why a long streamer name had to ellipsize two characters earlier.
    assertEquals(1, composeRule.onAllNodesWithText("斗鱼").fetchSemanticsNodes().size)
  }

  @Test
  fun `a followed room shows its audience figure`() {
    val state = UiState(
      screen = Screen.Follow,
      followed = listOf(
        streamer("一条小团团", 129_000),
        streamer("旭旭宝宝", 320_000, live = false),
      ),
    )

    composeInSmallWindow { FollowScreen(state = state, onAction = {}) }

    // The formatter's own output, not a hand-written string: 12.9万 and 32万.
    composeRule.onNodeWithText("12.9万").assertExists()
    composeRule.onNodeWithText("32万").assertExists()
  }
}
