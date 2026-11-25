package com.livehub.app.ui.screens

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rule behind「已经到底了」.
 *
 * The grid's own condition used to be `!hasNextPage && rooms.size < 21`, and the comment beside it
 * named two single-page platforms as the reason the line exists — Bilibili's 热门 tab (110 rooms,
 * `has_more = 0`) and Douyin's feed (six). The size gate excluded the first of those exactly: the
 * platform the note named was the only one that never showed the line, and an empty-looking end of a
 * 110-room grid reads as a failed load.
 *
 * Pure, so the rule can be pinned without composing a grid.
 */
class EndOfListHintTest {

  @Test
  fun `a single page of 110 rooms still ends the list`() {
    assertTrue(
      "B站热门 gives 110 rooms with has_more = 0, which used to fall outside the size gate",
      showsEndOfList(hasNextPage = false, roomCount = 110),
    )
  }

  @Test
  fun `a six room feed ends the list`() {
    assertTrue(showsEndOfList(hasNextPage = false, roomCount = 6))
  }

  @Test
  fun `a paged list does not claim to be finished`() {
    assertFalse(showsEndOfList(hasNextPage = true, roomCount = 40))
  }

  @Test
  fun `an empty result says nothing at all`() {
    assertFalse(
      "the empty state already explains itself; a second line under it would be noise",
      showsEndOfList(hasNextPage = false, roomCount = 0),
    )
  }
}
