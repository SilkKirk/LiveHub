package com.livehub.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind 下滑收起分区、上滑显示, without a gesture.
 *
 * The tree costs the most pixels on the home screen (two chip rows and a third level; they were 41%
 * of a 320×640 window before round 22), and while the grid is being scrolled they answer a question
 * the user has already answered. Folding them away on the way down and back on the way up is one
 * line of state, and the failures it can have are all in this table: folding at the top, unfolding
 * on an arrival, or folding while the list is standing still.
 */
class HomeHeaderCollapseTest {

  @Test
  fun `scrolling down folds the tree away`() {
    assertFalse(
      "a grid that moved down one item must fold the tree",
      categoriesVisibleAfterScroll(
        visible = true,
        previousIndex = 0,
        previousOffset = 0,
        index = 1,
        offset = 0,
      ),
    )
  }

  @Test
  fun `scrolling up brings it back`() {
    assertTrue(
      "any upward movement unfolds it, including a partial row",
      categoriesVisibleAfterScroll(
        visible = false,
        previousIndex = 2,
        previousOffset = 120,
        index = 2,
        offset = 100,
      ),
    )
  }

  /**
   * A grid that did not move keeps whatever it had.
   *
   * This is the case that separates "the user scrolled" from "the list changed": a new page arriving
   * or a refresh replacing the rooms also emits a new `(index, offset)` pair, and folding on that
   * would slide the header shut under a finger that is not moving.
   */
  @Test
  fun `standing still changes nothing`() {
    assertTrue(
      "visible stays visible",
      categoriesVisibleAfterScroll(true, previousIndex = 3, previousOffset = 40, index = 3, offset = 40),
    )
    assertFalse(
      "folded stays folded",
      categoriesVisibleAfterScroll(false, previousIndex = 3, previousOffset = 40, index = 3, offset = 40),
    )
  }

  /** The top of the list is where the tree belongs, whatever the previous state was. */
  @Test
  fun `the top of the list always shows the tree`() {
    assertTrue(
      "back at the first item, the tree is the answer to 'which partition am I in'",
      categoriesVisibleAfterScroll(false, previousIndex = 1, previousOffset = 0, index = 0, offset = 0),
    )
  }

  /**
   * A first-frame emission at the top must not fold anything.
   *
   * `snapshotFlow` emits the current value as soon as it is collected, so on a cold start the very
   * first pair is `(0, 0)` — and `previousIndex`/`previousOffset` are also `(0, 0)`, which reads as
   * "did not move" and would otherwise be judged by the `visible` value alone.
   */
  @Test
  fun `the first emission of a fresh grid keeps the tree`() {
    assertTrue(categoriesVisibleAfterScroll(true, 0, 0, 0, 0))
  }
}
