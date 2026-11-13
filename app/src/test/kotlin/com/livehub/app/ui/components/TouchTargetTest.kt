package com.livehub.app.ui.components

import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Where a finger can actually land, not where the semantics tree says a control is.
 *
 * The two are not the same thing, and this project learned that the hard way: round 61 "fixed" a
 * 32dp star by writing `size(48.dp).padding(8.dp)`, which reports a 48dp node and still leaves a
 * 32dp clickable area — `IconButton` applies Material's minimum *inside* the caller's modifier, so
 * the padding is what the inner `size(40.dp)` saw. A tap in the corner fell through to the card and
 * opened the room, while every assertion anyone wrote about that star passed, because they all
 * measured the semantics node.
 *
 * The first version of *this* test tapped 4dp in from the corner and failed, which is the other half
 * of the same lesson: a `Material3` `IconButton` is a 48dp *node* around a 40dp *state layer*, and
 * that outer ring is not part of the pointer area. Tapping 6dp in is inside the real target and
 * outside the 32dp one the padding bug produced, so it separates the two without depending on
 * which of 40 or 48 Material happens to use.
 *
 * `TouchTargetSizeCheck` in `app/build.gradle.kts` is the static half of the same guard.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class TouchTargetTest {

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

  /** The card is laid out at its real size; a size of its own would change what "the corner" means. */
  private fun render(actions: MutableList<String>) {
    composeRule.setContent {
      StreamerCard(
        streamer = room,
        followed = false,
        onClick = { actions += "open" },
        onToggleFollow = { actions += "follow" },
        modifier = Modifier.size(width = 160.dp, height = 200.dp),
      )
    }
    composeRule.waitForIdle()
  }

  @Test
  fun `a tap just inside the star follows instead of opening the room`() {
    val actions = mutableListOf<String>()
    render(actions)

    // 6dp in from the left edge, vertically centred: inside a 40dp state layer, outside the 32dp
    // area `size(48.dp).padding(8.dp)` leaves behind.
    composeRule.onNodeWithContentDescription("关注").performTouchInput { click(Offset(6f, 24f)) }
    composeRule.waitForIdle()

    assertEquals("the edge of the star belongs to the star: $actions", listOf("follow"), actions)
  }

  /** The rest of the card still opens the room, so the assertion above is about the star, not a dead card. */
  @Test
  fun `a tap on the title opens the room`() {
    val actions = mutableListOf<String>()
    render(actions)

    composeRule.onNodeWithText("今天继续冲分").performClick()
    composeRule.waitForIdle()

    assertEquals(listOf("open"), actions)
  }
}
