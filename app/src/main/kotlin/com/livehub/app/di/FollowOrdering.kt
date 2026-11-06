package com.livehub.app.di

import com.livehub.core.model.Streamer

/**
 * Helpers for reordering the follow list while keeping sync metadata intact.
 *
 * The list on screen and the list in the store are **not** the same list: the screen shows a
 * filtered, live-first view of it. Handing a view index straight to the store is what made
 * dragging row 1 to row 2 under a platform filter move somebody else entirely — the index
 * addressed the unfiltered list. Everything here therefore works in *keys* and resolves the
 * view position back to a position in the full list by anchoring on a neighbour that is
 * present in both.
 */
object FollowOrdering {

  /**
   * Reorders [ordered] so that [moved] ends up at index [toIndex].
   *
   * [toIndex] is an index into the **final** list, which is what a drag gesture produces
   * ("drop this row where row N currently is"). Removing the moved item first shifts everything
   * after it down by one, so a naive `without.add(toIndex)` puts the row one slot too far when
   * dragging downwards — the bug this function had.
   *
   * Returns the new order as streamer keys; the caller re-stamps each record so the new order
   * wins over a concurrent reorder from another device.
   */
  fun move(ordered: List<Streamer>, moved: Streamer, toIndex: Int): List<String> {
    if (ordered.isEmpty()) return emptyList()
    val without = ordered.filterNot { it.key == moved.key }
    val target = toIndex.coerceIn(0, without.size)
    val result = without.toMutableList()
    result.add(target, moved)
    return result.map { it.key }
  }

  /**
   * Moves [moved] inside [visible], then returns the resulting order of the **full** list.
   *
   * [visible] is what the user is dragging (filtered by group, platform and live state);
   * [full] is the stored order. The two are reconciled by anchor rather than by index:
   *
   * 1. Remove [moved] from [full], giving the list it has to be re-inserted into.
   * 2. Compute where it belongs *relative to its visible neighbours* — the item that will end
   *    up after it in [visible], or, when it is dropped at the end, the item before it.
   * 3. Insert it next to that anchor in the full list.
   *
   * That keeps two properties the index-based version could not:
   *
   *  * a drag under a filter never touches a row the filter hid;
   *  * the stored order stays purely manual. It used to be written back as the *live-first*
   *    order, so a streamer who went offline jumped to the bottom of the list permanently.
   */
  fun moveWithin(
    full: List<Streamer>,
    visible: List<Streamer>,
    moved: Streamer,
    toIndex: Int,
  ): List<String> {
    if (full.isEmpty()) return emptyList()
    val without = full.filterNot { it.key == moved.key }
    val visibleWithout = visible.filterNot { it.key == moved.key }
    if (visibleWithout.isEmpty()) return full.map { it.key }

    val target = toIndex.coerceIn(0, visibleWithout.size)

    // The anchor is the visible neighbour the moved row is being placed next to, translated into
    // the full list. Falling back to "after the last visible item" keeps a drop at the end of a
    // filtered view from landing at the very bottom of everything.
    val anchorKey = visibleWithout.getOrNull(target)?.key
      ?: visibleWithout.lastOrNull()?.key
    val anchorInFull = anchorKey?.let { key -> without.indexOfFirst { it.key == key } }

    val result = without.toMutableList()
    val insertAt = when {
      anchorInFull == null || anchorInFull < 0 -> result.size
      // Dropping *before* the anchor when moving up, *after* it when moving down: the anchor is
      // the row that will follow the moved one, so "before" is right in both cases except when
      // the moved row is being sent to the end of the visible list.
      target >= visibleWithout.size -> anchorInFull + 1
      else -> anchorInFull
    }
    result.add(insertAt.coerceIn(0, result.size), moved)
    return result.map { it.key }
  }
}
