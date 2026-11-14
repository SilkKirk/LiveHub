package com.livehub.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.Screen
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.app.ui.screens.FollowScreen
import com.livehub.core.designsystem.LiveHubTheme
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * What a screen reader or a switch can actually do on the follow screen's two 特别关注 surfaces.
 *
 * Both were gesture- or colour-only, which reads as "works" on a phone and as "nothing happens" with
 * TalkBack on. The avatar strip opens a room with a tap and reorders with a press-and-hold followed
 * by a drag: a screen reader cannot produce either, so the strip could be walked through, announced
 * correctly ("特别关注 团团") and acted on in no way at all. The picker's rows carried their state in
 * an 18dp tick with `contentDescription = null`, so every row sounded the same and nothing said which
 * of them were pinned.
 *
 * The assertions below are about the semantics, because the semantics *are* the fix: `Role.Button`
 * plus an `OnClick` action on the avatar, `Role.Checkbox` plus a toggle state on the picker row. That
 * the pointer gesture still behaves is `TouchTargetTest`'s business, and the visual result is the
 * instrumented screenshots'.
 *
 * The pointer handlers are deliberately untouched, including the one that makes a long press and a
 * tap mutually exclusive — see the comments in `SpecialFollowRow`.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w320dp-h640dp")
class SpecialFollowSemanticsTest {

  @get:Rule
  val composeRule = createComposeRule()

  /** Live, and the room the picker's 直播中 dot and its first row both belong to. */
  private val live = Streamer(
    platform = Platform.DOUYU,
    roomId = "1",
    streamerName = "团团",
    title = "标题 1",
    isLive = true,
  )

  /**
   * Offline, so the picker has both dot states on screen at once.
   *
   * Also the second slot of the strip, which is what gives the reorder actions a middle and an end
   * to be offered from.
   */
  private val offline = Streamer(
    platform = Platform.DOUYU,
    roomId = "2",
    streamerName = "宝宝",
    title = "标题 2",
    isLive = false,
  )

  // ------------------------------------------------------------- the avatar strip ---

  /** The strip on its own: it is public, so its semantics can be read without the whole screen. */
  private fun renderStrip(calls: MutableList<String>) {
    composeRule.setContent {
      LiveHubTheme {
        Box(modifier = Modifier.size(width = 320.dp, height = 160.dp)) {
          SpecialFollowRow(
            streamers = listOf(live, offline),
            onClick = { streamer -> calls += "open ${streamer.streamerName}" },
            onReorder = { streamer, index -> calls += "move ${streamer.streamerName} to $index" },
            onAdd = { calls += "add" },
          )
        }
      }
    }
    composeRule.waitForIdle()
  }

  /**
   * The avatar is a button, and its action is the tap the pointer handler would need a finger for.
   *
   * `performSemanticsAction` rather than `performClick`: this is the path a screen reader and a
   * switch access user take, and the only one available to them here.
   */
  @Test
  fun `the avatar is a button whose action opens the room`() {
    val calls = mutableListOf<String>()
    renderStrip(calls)

    val avatar = composeRule.onNode(
      hasContentDescription("特别关注 团团") and
        SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button),
    )
    avatar.assertIsDisplayed()

    avatar.performSemanticsAction(SemanticsActions.OnClick)
    composeRule.waitForIdle()

    assertEquals("the strip's button has to open the room: $calls", listOf("open 团团"), calls)
  }

  /**
   * Reordering, which had no accessible form at all.
   *
   * A drag is a delta and an action menu can only say a direction, so the menu gets the neighbour a
   * step would reach — the same `onReorder` the handler calls, with the same index arithmetic. Only
   * the directions that can still move are offered: on the first slot an 上移 would be an entry that
   * does nothing, which reads as a broken control rather than as the start of the row.
   */
  @Test
  fun `reorder is offered as actions, and only in the directions that can move`() {
    val calls = mutableListOf<String>()
    renderStrip(calls)

    assertEquals(
      "the first slot can only move right: ${labelsOf("团团")}",
      listOf("下移"),
      labelsOf("团团"),
    )
    assertEquals(
      "the last slot can only move left: ${labelsOf("宝宝")}",
      listOf("上移"),
      labelsOf("宝宝"),
    )

    // Invoking the action is what the screen reader does once the entry is chosen, and it has to
    // reach the callback the gesture uses — not a second implementation of "move one slot left".
    val up = actionsOf("宝宝").single()
    assertTrue("the action must report that it handled the move: $up", up.action())
    composeRule.waitForIdle()
    assertEquals(
      "the action has to reuse the drag's own callback: $calls",
      listOf("move 宝宝 to 0"),
      calls,
    )
  }

  /**
   * The custom actions on one avatar, in the order the row declared them.
   *
   * `SemanticsActions.CustomActions`, not `SemanticsProperties.CustomActions`: the key lives in the
   * action object (it is an `AccessibilityKey` whose merge policy concatenates parent and child
   * lists, so a row's own actions survive being merged into a list row).
   */
  private fun actionsOf(name: String): List<CustomAccessibilityAction> =
    composeRule.onNodeWithContentDescription("特别关注 $name").fetchSemanticsNode()
      .config.getOrNull(SemanticsActions.CustomActions).orEmpty()

  private fun labelsOf(name: String): List<String> = actionsOf(name).map { it.label }

  // ------------------------------------------------------------------- the picker ---

  private val actions = mutableListOf<UiAction>()

  /**
   * The follow screen with two follows, applying a 特别关注 toggle the way the view model does.
   *
   * A frozen state would leave a tapped row drawn as unselected, and the assertion would then be
   * hunting a bug the fixture created — `FollowTagsTest` renders the same way for the same reason.
   */
  private fun renderFollowScreen() {
    val state = mutableStateOf(
      UiState(screen = Screen.Follow, followed = listOf(live, offline)),
    )
    composeRule.setContent {
      LiveHubTheme {
        Box(modifier = Modifier.size(320.dp, 640.dp)) {
          FollowScreen(
            state = state.value,
            onAction = { action ->
              actions += action
              if (action is UiAction.ToggleSpecialFollow) {
                val key = action.streamer.key
                val keys = state.value.specialFollowKeys
                state.value = state.value.copy(
                  specialFollowKeys = if (key in keys) keys - key else keys + key,
                )
              }
            },
          )
        }
      }
    }
    composeRule.waitForIdle()
  }

  /**
   * Opens the picker the way a user does: the ＋ at the end of the avatar strip.
   *
   * `SpecialPickerSheet` is private, so this is the only route in — and it is the honest one, since
   * what changed is what happens to a row inside the panel that ＋ opens.
   */
  private fun openPicker() {
    composeRule.onNodeWithContentDescription("添加特别关注").performClick()
    composeRule.waitForIdle()
  }

  /**
   * One row of the picker, not merely any node carrying the same words.
   *
   * The name is also drawn under the avatar in the strip and beside the card in the grid, and the
   * panel leaves both on screen, so a bare `onNodeWithText` would be ambiguous. `Role.Checkbox` plus
   * a toggle state is what makes this the row — written with the matchers already proven in this
   * repository rather than with `isToggleable()`, which has no precedent here.
   */
  private fun pickerRow(name: String) = composeRule.onNode(
    hasText(name) and
      SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Checkbox) and
      SemanticsMatcher.keyIsDefined(SemanticsProperties.ToggleableState),
  )

  /**
   * The row owns the toggle and the checkbox only shows it, which is the whole point of the change.
   *
   * The tap goes to the middle of the line, not to the 24dp box: before this, the tick was the only
   * thing that carried the state and it was the smallest target on the row.
   */
  @Test
  fun `a picker row is a checkbox-role toggle that flips through the row`() {
    renderFollowScreen()
    openPicker()

    pickerRow("团团").assertIsDisplayed().assertIsOff()

    pickerRow("团团").performClick()
    composeRule.waitForIdle()

    pickerRow("团团").assertIsOn()
    // The neighbour is what makes the assertion about this row rather than about "something became
    // selected": a row that flipped the first item it found would pass without this.
    pickerRow("宝宝").assertIsOff()

    val toggles = actions.filterIsInstance<UiAction.ToggleSpecialFollow>()
    assertEquals("exactly one toggle, got $toggles", 1, toggles.size)
    assertEquals(
      "the toggle must name the row that was tapped",
      live.key,
      toggles.single().streamer.key,
    )
  }

  /**
   * The live dot is a colour, so it needs a description to mean anything to a screen reader.
   *
   * Both halves are matched together because the description has to sit on the row it describes:
   * 未开播 is also drawn on the offline card in the grid behind the panel, and 直播中 is a filter chip
   * label, so either half alone would match a node that says nothing about this list.
   */
  @Test
  fun `the live dot is described, not only coloured`() {
    renderFollowScreen()
    openPicker()

    composeRule.onNode(hasText("团团") and hasContentDescription("直播中")).assertIsDisplayed()
    composeRule.onNode(hasText("宝宝") and hasContentDescription("未开播")).assertIsDisplayed()
  }
}
