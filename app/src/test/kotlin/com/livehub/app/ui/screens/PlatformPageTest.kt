package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiState
import com.livehub.core.designsystem.LiveHubTheme
import com.livehub.core.model.Category
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a pager page draws when it is not the current platform.
 *
 * The pager keeps `beyondViewportPageCount = 0`, so a page is composed exactly while it is being
 * dragged in — and at that moment `state.rooms` still belongs to the platform being dragged away from.
 * The page drew that grid anyway, so the incoming half of a swipe was a copy of the outgoing half while
 * the tab indicator had already moved to the new platform. The comment above the branch has claimed
 * "a page mid-swipe shows its skeleton" since round 44; this is the test that makes the code say it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class PlatformPageTest {

  @get:Rule
  val composeRule = createComposeRule()

  private val category = Category(Platform.DOUYU, "1", "英雄联盟", "1", "网游")

  private val rooms = (1..4).map { index ->
    Streamer(
      platform = Platform.DOUYU,
      roomId = "$index",
      streamerName = "主播$index",
      title = "标题$index",
      isLive = true,
    )
  }

  private fun render(isCurrent: Boolean) {
    val state = UiState(
      platform = Platform.DOUYU,
      categories = listOf(category),
      selectedCategory = category,
      rooms = rooms,
    )
    composeRule.setContent {
      LiveHubTheme {
        Box(modifier = Modifier.size(320.dp, 640.dp)) {
          PlatformPage(state = state, isCurrent = isCurrent, onAction = {})
        }
      }
    }
    composeRule.waitForIdle()
  }

  @Test
  fun `the page the user is on draws its rooms`() {
    render(isCurrent = true)

    composeRule.onNodeWithText("标题1").assertExists()
  }

  @Test
  fun `a page being dragged in does not draw the outgoing platform's rooms`() {
    render(isCurrent = false)

    composeRule.onNodeWithText("标题1").assertDoesNotExist()
  }
}
