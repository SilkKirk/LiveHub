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

  private fun streamer(id: String, platform: Platform = Platform.DOUYU) = Streamer(
    platform = platform,
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

  // ------------------------------------------------------- filtered views ---

  /**
   * The defect these cover: the follow screen hands the drag index to the *visible* list, and the
   * store used to apply it to the *full* list. Under a platform filter, dragging row 1 to row 2
   * therefore moved whoever happened to sit at index 2 in the unfiltered list.
   */
  @Test
  fun `moving inside a filtered view never touches a hidden row`() {
    val douyu1 = streamer("d1")
    val huya1 = streamer("h1", Platform.HUYA)
    val douyu2 = streamer("d2")
    val huya2 = streamer("h2", Platform.HUYA)
    val full = listOf(douyu1, huya1, douyu2, huya2)
    val visible = listOf(douyu1, douyu2)

    // Drag 主播d1 down one slot inside the 斗鱼 filter.
    val result = FollowOrdering.moveWithin(full, visible, douyu1, 1)

    assertEquals(listOf(huya1.key, douyu2.key, douyu1.key, huya2.key), result)
  }

  @Test
  fun `a drop at the top of a filtered view puts the row before its first visible neighbour`() {
    val a = streamer("a")
    val hidden = streamer("x", Platform.HUYA)
    val b = streamer("b")
    val full = listOf(a, hidden, b)

    val result = FollowOrdering.moveWithin(full, listOf(a, b), b, 0)

    assertEquals(listOf(b.key, a.key, hidden.key), result)
  }

  @Test
  fun `the full list keeps every key exactly once`() {
    val full = (1..5).map { streamer("$it") }
    val visible = listOf(full[0], full[2], full[4])
    visible.forEach { moved ->
      for (target in 0..2) {
        val result = FollowOrdering.moveWithin(full, visible, moved, target)
        assertEquals(full.size, result.size)
        assertEquals(full.map { it.key }.toSet(), result.toSet())
      }
    }
  }

  @Test
  fun `a single visible row is a no-op`() {
    val a = streamer("a")
    val b = streamer("b")
    val result = FollowOrdering.moveWithin(listOf(a, b), listOf(b), b, 0)
    assertEquals(listOf(a.key, b.key), result)
  }

  @Test
  fun `an empty full list returns nothing`() {
    assertTrue(FollowOrdering.moveWithin(emptyList(), emptyList(), streamer("1"), 0).isEmpty())
  }

  /**
   * The stored order is the user's arrangement; the live-first sort is a view rule only. Writing
   * the *view* order back is what sent whoever went offline to the bottom of the list for good.
   */
  @Test
  fun `a live-first view does not reorder offline rooms`() {
    val live = streamer("1")
    val offline = Streamer(
      platform = Platform.DOUYU,
      roomId = "2",
      streamerName = "主播2",
      isLive = false,
    )
    val full = listOf(live, offline)
    // What the screen shows: the live one first, whatever the stored order says.
    val visible = listOf(live, offline)

    val result = FollowOrdering.moveWithin(full, visible, live, 0)

    assertEquals("dragging the live row onto itself must change nothing", listOf(live.key, offline.key), result)
  }
}
