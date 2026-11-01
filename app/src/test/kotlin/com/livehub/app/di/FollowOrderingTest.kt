package com.livehub.app.di

import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer
import com.livehub.core.sync.Hlc
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Ordering rules for the follow list.
 *
 * The list is user-ordered *and* synchronised, so the order has to live inside each
 * record rather than in a separate array — otherwise a reorder on one device would be
 * lost the next time a record arrives from another.
 */
class FollowOrderingTest {

  private fun streamer(id: String) = Streamer(
    platform = Platform.DOUYU,
    roomId = id,
    streamerName = "主播$id",
    title = "直播$id",
    isLive = true,
  )

  @Test
  fun `moving to the front puts the item first`() {
    val a = streamer("1")
    val b = streamer("2")
    val c = streamer("3")

    val moved = FollowOrdering.move(listOf(a, b, c), c, 0)

    assertEquals(listOf(c.key, a.key, b.key), moved)
  }

  @Test
  fun `moving to the end puts the item last`() {
    val a = streamer("1")
    val b = streamer("2")
    val c = streamer("3")

    val moved = FollowOrdering.move(listOf(a, b, c), a, 2)

    assertEquals(listOf(b.key, c.key, a.key), moved)
  }

  @Test
  fun `moving within the list preserves the other items`() {
    val a = streamer("1")
    val b = streamer("2")
    val c = streamer("3")

    val moved = FollowOrdering.move(listOf(a, b, c), b, 2)

    assertEquals(listOf(a.key, c.key, b.key), moved)
  }

  @Test
  fun `out of range indices are clamped`() {
    val a = streamer("1")
    val b = streamer("2")

    // Negative clamps to the front.
    assertEquals(listOf(b.key, a.key), FollowOrdering.move(listOf(a, b), b, -5))
    // Past the end clamps to the final slot.
    assertEquals(listOf(b.key, a.key), FollowOrdering.move(listOf(a, b), a, 99))
  }

  @Test
  fun `moving in an empty list is a no-op`() {
    assertTrue(FollowOrdering.move(emptyList(), streamer("1"), 0).isEmpty())
  }

  @Test
  fun `a single item list is stable`() {
    val a = streamer("1")
    assertEquals(listOf(a.key), FollowOrdering.move(listOf(a), a, 0))
  }

  @Test
  fun `the result always contains every key exactly once`() {
    val items = (1..5).map { streamer("$it") }
    items.forEach { moved ->
      listOf(0, 2, 4).forEach { target ->
        val result = FollowOrdering.move(items, moved, target)
        assertEquals("moving ${moved.key} to $target", items.size, result.size)
        assertEquals(items.map { it.key }.toSet(), result.toSet())
      }
    }
  }

  @Test
  fun `the moved item really lands on the requested index`() {
    val items = (1..4).map { streamer("$it") }
    items.forEach { moved ->
      for (target in 0..3) {
        val result = FollowOrdering.move(items, moved, target)
        assertEquals(
          "moving ${moved.key} to $target",
          target,
          result.indexOf(moved.key),
        )
      }
    }
  }
}
