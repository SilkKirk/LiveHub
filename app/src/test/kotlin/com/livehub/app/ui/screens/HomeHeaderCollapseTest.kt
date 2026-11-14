package com.livehub.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind 下滑收起分区、上滑显示, without a gesture.
 *
 * The tree is the most expensive thing on the home screen (two chip rows and a third level; they
 * were 41% of a 320×640 window before round 22), and while the grid is being scrolled it answers a
 * question the user has already answered. The whole rule is this table — and the cases below are the
 * ones that were actually wrong at some point:
 *
 *  * the first version derived the direction from `firstVisibleItemIndex`/`ScrollOffset`, i.e. from
 *    the layout the fold itself changes. It never failed an assertion; it made the instrumented
 *    suite five times slower, which is why the input here is a drag delta and not a position.
 *  * folding at the top of a list that cannot scroll leaves an empty band where the tree was.
 *  * folding on a sub-threshold delta makes the header twitch when a card is tapped.
 */
class HomeHeaderCollapseTest {

  @Test
  fun `dragging up folds the tree away`() {
    assertFalse(
      "a drag with content moving up is a scroll down the list",
      categoriesVisibleAfterDrag(visible = true, deltaY = -40f, scrolledAway = true),
    )
  }

  @Test
  fun `dragging down brings it back`() {
    assertTrue(
      "a drag the other way is the user asking for the top of the list",
      categoriesVisibleAfterDrag(visible = false, deltaY = 40f, scrolledAway = true),
    )
  }

  /**
   * Nothing is hidden while the list is at its top.
   *
   * The grid has nothing to scroll, so folding would only replace the tree with empty space — and it
   * would have to come straight back on the next frame.
   */
  @Test
  fun `a list still at its top keeps the tree`() {
    assertTrue(
      "there is nothing behind the tree to reveal",
      categoriesVisibleAfterDrag(visible = true, deltaY = -40f, scrolledAway = false),
    )
  }

  /** A tap is not a scroll: sub-threshold deltas change nothing, in either direction. */
  @Test
  fun `a tiny delta is not a scroll`() {
    assertTrue(
      "a nudge must not fold an open tree",
      categoriesVisibleAfterDrag(visible = true, deltaY = -2f, scrolledAway = true),
    )
    assertFalse(
      "and it must not unfold a folded one either",
      categoriesVisibleAfterDrag(visible = false, deltaY = 2f, scrolledAway = true),
    )
  }

  /** Zero is the commonest event of all: `onPreScroll` is called with nothing to consume. */
  @Test
  fun `a zero delta changes nothing`() {
    assertTrue(categoriesVisibleAfterDrag(true, deltaY = 0f, scrolledAway = true))
    assertFalse(categoriesVisibleAfterDrag(false, deltaY = 0f, scrolledAway = true))
  }
}
