package com.livehub.app.ui.components

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind 下滑收起、上滑显示, without a gesture.
 *
 * Two screens fold their header with it now (首页 keeps the platform tabs, 关注 folds the lot), so
 * the table lives with the component rather than with a screen. The cases below are the ones that
 * were actually wrong at some point:
 *
 *  * the first version derived the direction from `firstVisibleItemIndex`/`ScrollOffset`, i.e. from
 *    the layout the fold itself changes. It never failed an assertion; it made the instrumented
 *    suite five times slower (round 98), which is why the input here is a drag delta.
 *  * folding at the top of a list that cannot scroll leaves an empty band where the header was.
 *  * folding on a sub-threshold delta makes the header twitch when a card is tapped.
 */
class CollapsibleHeaderTest {

  @Test
  fun `dragging up folds the header away`() {
    assertFalse(
      "a drag with content moving up is a scroll down the list",
      headerVisibleAfterDrag(visible = true, deltaY = -40f, scrolledAway = true),
    )
  }

  @Test
  fun `dragging down brings it back`() {
    assertTrue(
      "a drag the other way is the user asking for the top of the list",
      headerVisibleAfterDrag(visible = false, deltaY = 40f, scrolledAway = true),
    )
  }

  /**
   * Nothing is folded while the list is at its top.
   *
   * The list has nothing to scroll, so folding would only replace the header with empty space — and
   * it would have to come straight back on the next frame.
   */
  @Test
  fun `a list still at its top keeps the header`() {
    assertTrue(
      "there is nothing behind the header to reveal",
      headerVisibleAfterDrag(visible = true, deltaY = -40f, scrolledAway = false),
    )
  }

  /** A tap is not a scroll: sub-threshold deltas change nothing, in either direction. */
  @Test
  fun `a tiny delta is not a scroll`() {
    assertTrue(
      "a nudge must not fold an open header",
      headerVisibleAfterDrag(visible = true, deltaY = -2f, scrolledAway = true),
    )
    assertFalse(
      "and it must not unfold a folded one either",
      headerVisibleAfterDrag(visible = false, deltaY = 2f, scrolledAway = true),
    )
  }

  /** Zero is the commonest event of all: `onPreScroll` is called with nothing to consume. */
  @Test
  fun `a zero delta changes nothing`() {
    assertTrue(headerVisibleAfterDrag(true, deltaY = 0f, scrolledAway = true))
    assertFalse(headerVisibleAfterDrag(false, deltaY = 0f, scrolledAway = true))
  }
}
