package com.livehub.app.di

import com.livehub.core.model.Streamer

/** Helpers for reordering the follow list while keeping sync metadata intact. */
object FollowOrdering {
  /**
   * Reorders [ordered] so that [moved] ends up at index [toIndex].
   *
   * [toIndex] is an index into the **final** list, which is what a drag gesture
   * produces ("drop this row where row N currently is"). Removing the moved item first
   * shifts everything after it down by one, so a naive `without.add(toIndex)` puts the
   * row one slot too far when dragging downwards — the bug this function had.
   *
   * Returns the new order as streamer keys; the caller re-stamps each record so the new
   * order wins over a concurrent reorder from another device.
   */
  fun move(ordered: List<Streamer>, moved: Streamer, toIndex: Int): List<String> {
    if (ordered.isEmpty()) return emptyList()
    val without = ordered.filterNot { it.key == moved.key }
    val target = toIndex.coerceIn(0, without.size)
    val result = without.toMutableList()
    result.add(target, moved)
    return result.map { it.key }
  }
}
