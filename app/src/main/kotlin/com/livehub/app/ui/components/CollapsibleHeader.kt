package com.livehub.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity

/**
 * Where one drag leaves the fold, in pixels: `0` open, [maxPx] fully folded.
 *
 * **The input is a drag delta, never a scroll position.** The first version of this rule read
 * `firstVisibleItemIndex`/`ScrollOffset` out of a `snapshotFlow`, which is the same quantity the fold
 * changes back: hiding the header changes the list's height, that changes the state the decision was
 * derived from, and the two fed each other. It never failed an assertion — it made the instrumented
 * suite go from 4 minutes to 18 (round 98). A delta is an input, not a consequence.
 *
 * Folding needs `scrolledAway`, because a list that cannot scroll has nothing behind the header to
 * reveal; unfolding does not, or a folded header could never come back once the list is at its top.
 */
internal fun collapsedAfterDrag(
  collapsedPx: Float,
  deltaY: Float,
  maxPx: Float,
  scrolledAway: Boolean,
): Float {
  if (maxPx <= 0f) return 0f
  if (deltaY < 0f && !scrolledAway) return collapsedPx
  return (collapsedPx - deltaY).coerceIn(0f, maxPx)
}

/**
 * The state behind 下滑收起、上滑显示: how far the header is scrolled away, and the connection that
 * moves it.
 *
 * **Bounded by the drag, not by a clock.** It used to be a boolean plus a 160ms animation: any drag
 * past 6px flipped the header away, and the animation then removed its entire height in 160ms. On a
 * 320dp-wide screen the 关注 header is about 200dp, so for those 160ms the content underneath moved
 * at a further ~1200dp/s on top of the finger's own speed. That is what "滑动很快" was: the scroll
 * was fine, the fold was faster than the hand.
 *
 * **The drag pays for the fold.** [connection] consumes exactly the delta it uses, so one pixel of
 * gesture either folds the header or scrolls the list, never both at once. From the finger's point
 * of view the header is simply the first thing the list scrolls — which is what it looks like.
 *
 * Held by the screen so the header and the list can be composed separately — the list is usually
 * inside a `RefreshableBox` and a paging effect, and threading the collapse through both would be
 * worse than passing this object down.
 */
@Stable
class CollapsibleHeaderState internal constructor() {
  /** How much of the header is scrolled away, in pixels. */
  internal var collapsedPx by mutableFloatStateOf(0f)

  /**
   * The header's full height, published by [CollapsibleHeader] once it has been measured.
   *
   * Read by [connection] only — never by composition — so publishing it cannot invalidate the frame
   * that measured it.
   */
  internal var maxPx by mutableFloatStateOf(0f)

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
      val before = collapsedPx
      val after = collapsedAfterDrag(
        collapsedPx = before,
        deltaY = available.y,
        maxPx = maxPx,
        scrolledAway = hasScrolled(),
      )
      if (after == before) return Offset.Zero
      collapsedPx = after
      // What the fold used, so the list only sees the remainder. Consuming nothing (what this did
      // while the fold was a timer) is the half of the old behaviour that made the content move at
      // finger speed *plus* the fold's own speed.
      return Offset(0f, before - after)
    }
  }
}

/**
 * Creates the state, and keeps [hasScrolled] current.
 *
 * `remember` without keys is deliberate: the state object must survive the recompositions that its
 * own `collapsedPx` changes cause, or the connection would be rebuilt mid-gesture.
 */
@Composable
fun rememberCollapsibleHeaderState(hasScrolled: () -> Boolean = { true }): CollapsibleHeaderState {
  val state = remember { CollapsibleHeaderState() }
  state.hasScrolled = hasScrolled
  return state
}

/**
 * The header itself: its height follows [CollapsibleHeaderState.collapsedPx], and its content is
 * translated up by the same amount and clipped, so the fold reads as the header sliding under the
 * top edge rather than squashing.
 *
 * The full height is measured here rather than asked of the caller: "fully folded" has to have a
 * number, and the header's content is not a fixed size (the follow page's platform and tag rows
 * come and go). Measurement is published through `onSizeChanged`, and the value it publishes is
 * read only by the scroll connection, so the write cannot re-invalidate the layout that produced it.
 */
@Composable
fun CollapsibleHeader(
  state: CollapsibleHeaderState,
  modifier: Modifier = Modifier,
  content: @Composable () -> Unit,
) {
  val density = LocalDensity.current
  var fullHeight by remember { mutableStateOf(0) }
  SideEffect { state.maxPx = fullHeight.toFloat() }

  val collapsed = if (fullHeight > 0) state.collapsedPx.coerceIn(0f, fullHeight.toFloat()) else 0f
  val remaining = (fullHeight - collapsed).coerceAtLeast(0f)

  Box(
    modifier
      .fillMaxWidth()
      .clipToBounds()
      // Only fixed while folding: at rest the box wraps its content, so a header that grows (an
      // extra chip row) is measured again instead of being clipped to yesterday's height.
      .then(
        if (collapsed > 0f) Modifier.height(with(density) { remaining.toDp() }) else Modifier,
      ),
  ) {
    // Nothing is composed once the fold has used the whole header: a fully folded header must leave
    // the semantics tree (the screen tests assert its text is gone) and must not keep the subtree
    // alive behind an empty box.
    if (fullHeight == 0 || collapsed < fullHeight) {
      Box(
        Modifier
          .fillMaxWidth()
          .then(
            if (collapsed > 0f) Modifier.height(with(density) { fullHeight.toFloat().toDp() }) else Modifier,
          )
          .graphicsLayer { translationY = -collapsed }
          .onSizeChanged { fullHeight = it.height },
      ) {
        content()
      }
    }
  }
}

/** `Modifier.nestedScroll` for [state], so callers do not have to reach into it. */
fun Modifier.collapsibleHeaderScroll(state: CollapsibleHeaderState): Modifier =
  nestedScroll(state.connection)
