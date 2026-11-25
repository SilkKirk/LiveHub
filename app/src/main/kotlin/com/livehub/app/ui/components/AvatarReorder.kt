package com.livehub.app.ui.components

import com.livehub.core.model.Streamer

/**
 * Turns a horizontal drag into "which slot this avatar should move to next".
 *
 * The rule used to live inline in the pointer handler, and a pointer handler is created once per
 * `(streamer.key, streamers.size)` — so it captured the list **as it was when the gesture began**.
 * Reordering changes that list, so the second slot of one drag was computed against the old order: the
 * avatar had already arrived at the target, the computed move was a no-op, and the drag stopped after a
 * single slot. Moving an avatar three places took three separate long-presses.
 *
 * Taking the list as an argument on *every* call is the fix rather than a detail of it: there is no
 * captured list left to go stale. (The handler still cannot be re-created mid-gesture — that would
 * cancel the drag — so the composable reads the current order through `rememberUpdatedState`.)
 *
 * Pure on purpose — no `Density`, no Compose state — because the thing that broke is a *sequence*:
 * one slot at a time, against a list that changes between the steps. Driving a real long-press-drag in
 * Robolectric is not something this project has ever managed to do reliably; feeding deltas is exact.
 */
internal class AvatarReorder(
  /** One slot: the avatar plus the gap between avatars, in pixels. */
  private val slotPx: Float,
) {
  private var accumulatedPx = 0f

  /** Begins a gesture. Pixels left over from the previous one must not count toward this one. */
  fun start() {
    accumulatedPx = 0f
  }

  /**
   * Feeds a horizontal delta and returns the index to move [key] to, or null when nothing should move.
   *
   * The accumulator is cleared only when a move is emitted, so a drag that has run out of room keeps
   * its distance: reversing direction answers on the first pixel back instead of after re-covering the
   * whole slot.
   */
  fun nextTarget(deltaX: Float, streamers: List<Streamer>, key: String): Int? {
    accumulatedPx += deltaX
    val steps = (accumulatedPx / slotPx).toInt()
    if (steps == 0) return null
    val current = streamers.indexOfFirst { it.key == key }
    if (current < 0) return null
    val target = (current + steps).coerceIn(0, streamers.lastIndex)
    if (target == current) return null
    accumulatedPx = 0f
    return target
  }
}
