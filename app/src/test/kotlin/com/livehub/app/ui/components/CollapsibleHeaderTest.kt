package com.livehub.app.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The rule behind 下滑收起、上滑显示, without a gesture.
 *
 * Two screens fold their header with it (首页 keeps the platform tabs, 关注 folds the lot), so the
 * table lives with the component rather than with a screen. The cases below are the ones that were
 * actually wrong at some point:
 *
 *  * the first version derived the direction from `firstVisibleItemIndex`/`ScrollOffset`, i.e. from
 *    the layout the fold itself changes. It never failed an assertion; it made the instrumented
 *    suite five times slower (round 98), which is why the input here is a drag delta.
 *  * the second version flipped a boolean on any drag past 6px and left the height to a 160ms
 *    animation, which moved the content faster than the finger (round 131). The fold is now the
 *    drag's own distance, so the table is about pixels rather than about a direction.
 *  * folding at the top of a list that cannot scroll leaves an empty band where the header was.
 */
class CollapsibleHeaderTest {

  /** The 关注 header on a 320dp-wide screen is roughly this tall; the exact number does not matter. */
  private val headerHeight = 200f

  private fun fold(collapsedPx: Float, deltaY: Float, scrolledAway: Boolean = true): Float =
    collapsedAfterDrag(
      collapsedPx = collapsedPx,
      deltaY = deltaY,
      maxPx = headerHeight,
      scrolledAway = scrolledAway,
    )

  @Test
  fun `dragging up folds by exactly the distance dragged`() {
    assertEquals(
      "40px of drag is 40px of fold, not the whole header",
      40f,
      fold(collapsedPx = 0f, deltaY = -40f),
      0.001f,
    )
  }

  @Test
  fun `the fold stops at the header's own height`() {
    assertEquals(
      "a long drag ends fully folded, not past it",
      headerHeight,
      fold(collapsedPx = 180f, deltaY = -80f),
      0.001f,
    )
  }

  @Test
  fun `dragging down unfolds by exactly the distance dragged`() {
    assertEquals(
      "coming back is the same arithmetic in the other direction",
      125f,
      fold(collapsedPx = 165f, deltaY = 40f),
      0.001f,
    )
  }

  @Test
  fun `the fold never goes above open`() {
    assertEquals(0f, fold(collapsedPx = 10f, deltaY = 40f), 0.001f)
  }

  /**
   * Nothing is folded while the list is at its top.
   *
   * The list has nothing to scroll, so folding would only replace the header with empty space — and
   * it would have to come straight back on the next frame.
   */
  @Test
  fun `a list still at its top does not fold`() {
    assertEquals(
      "there is nothing behind the header to reveal",
      0f,
      collapsedAfterDrag(
        collapsedPx = 0f,
        deltaY = -40f,
        maxPx = headerHeight,
        scrolledAway = false,
      ),
      0.001f,
    )
  }

  /** The way back is not gated: otherwise a folded header could never return at the top of the list. */
  @Test
  fun `a folded header comes back even when the list is at its top`() {
    assertEquals(
      80f,
      collapsedAfterDrag(
        collapsedPx = 120f,
        deltaY = 40f,
        maxPx = headerHeight,
        scrolledAway = false,
      ),
      0.001f,
    )
  }

  /** Zero is the commonest event of all: `onPreScroll` is called with nothing to consume. */
  @Test
  fun `a zero delta changes nothing`() {
    assertEquals(0f, fold(collapsedPx = 0f, deltaY = 0f), 0.001f)
    assertEquals(120f, fold(collapsedPx = 120f, deltaY = 0f), 0.001f)
  }

  /** A header that has not been measured yet has no "fully folded" to reach. */
  @Test
  fun `an unmeasured header cannot fold`() {
    assertEquals(
      0f,
      collapsedAfterDrag(collapsedPx = 0f, deltaY = -40f, maxPx = 0f, scrolledAway = true),
      0.001f,
    )
  }
}
