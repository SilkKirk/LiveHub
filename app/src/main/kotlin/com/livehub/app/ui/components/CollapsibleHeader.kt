package com.livehub.app.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.Modifier

/**
 * How much of a drag counts as "the user is scrolling", in pixels.
 *
 * Not zero: a tap and a scroll share the same first frame, and a header that folds on a stray pixel
 * flickers on every attempt to tap a card. 6px is below the touch slop of any device this ships to,
 * so a real drag crosses it on its first event.
 */
internal const val HeaderFoldThresholdPx = 6f

/**
 * Whether a collapsible header stays open, given one drag delta from the list under it.
 *
 * Dragging up (a negative `deltaY`: content moving up, the list scrolling down) folds it away;
 * dragging down brings it back; a delta too small to be a scroll changes nothing. `scrolledAway`
 * gates the fold, because at the top of a list there is nothing behind the header to reveal.
 *
 * **The input is a drag delta, never a scroll position.** The first version of this rule read
 * `firstVisibleItemIndex`/`ScrollOffset` out of a `snapshotFlow`, which is the same quantity the fold
 * changes back: hiding the header changes the list's height, that changes the state the decision was
 * derived from, and the two fed each other. It never failed an assertion — it made the instrumented
 * suite go from 4 minutes to 18 (round 98). A delta is an input, not a consequence.
 */
internal fun headerVisibleAfterDrag(
  visible: Boolean,
  deltaY: Float,
  scrolledAway: Boolean,
): Boolean = when {
  deltaY < -HeaderFoldThresholdPx && scrolledAway -> false
  deltaY > HeaderFoldThresholdPx -> true
  else -> visible
}

/**
 * The state behind 下滑收起、上滑显示: one boolean, one nested-scroll connection.
 *
 * Held by the screen so the header and the list can be composed separately — the list is usually
 * inside a `RefreshableBox` and a paging effect, and threading a boolean through both would be worse
 * than passing this object down.
 */
@Stable
class CollapsibleHeaderState internal constructor() {
  /** Whether the header is currently shown. Starts open: that is what a screenshot shows. */
  var visible by mutableStateOf(true)
    internal set

  /**
   * Whether the list is far enough down that folding reveals something.
   *
   * Set by [rememberCollapsibleHeaderState]; `true` for a header over a list that cannot report its
   * position, because folding is then the only thing the gesture can do.
   */
  internal var hasScrolled: () -> Boolean = { true }

  /** Attach to the column that holds the header **and** the list. */
  val connection: NestedScrollConnection = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
      visible = headerVisibleAfterDrag(visible, available.y, hasScrolled())
      // Never consumed: this is an observer of the gesture, and taking any of it would slow the
      // list down.
      return Offset.Zero
    }
  }
}

/**
 * Creates the state, and keeps [hasScrolled] current.
 *
 * `remember` without keys is deliberate: the state object must survive the recompositions that its
 * own `visible` changes cause, or the connection would be rebuilt mid-gesture.
 */
@Composable
fun rememberCollapsibleHeaderState(hasScrolled: () -> Boolean = { true }): CollapsibleHeaderState {
  val state = remember { CollapsibleHeaderState() }
  state.hasScrolled = hasScrolled
  return state
}

/**
 * The header itself, folded and unfolded with the state.
 *
 * A size change rather than a floating bar, because the list has to move up with the header instead
 * of being covered by it. 160ms is the short end of Material's expressive range: long enough to read
 * as a fold, short enough that the list does not wait for it.
 */
@Composable
fun CollapsibleHeader(
  state: CollapsibleHeaderState,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  AnimatedVisibility(
    visible = state.visible,
    modifier = modifier,
    enter = expandVertically(animationSpec = tween(160)) + fadeIn(tween(160)),
    exit = shrinkVertically(animationSpec = tween(160)) + fadeOut(tween(120)),
    content = { content() },
  )
}

/** `Modifier.nestedScroll` for [state], so callers do not have to reach into it. */
fun Modifier.collapsibleHeaderScroll(state: CollapsibleHeaderState): Modifier =
  nestedScroll(state.connection)
