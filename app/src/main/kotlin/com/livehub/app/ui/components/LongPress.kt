package com.livehub.app.ui.components

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

/**
 * Fires [onLongPress] when the user holds on this element, without taking over the tap.
 *
 * `Modifier.combinedClickable` cannot be used on a Material chip: `FilterChip` puts its own
 * `clickable` on the same layout node, and a `clickable` added through the caller's modifier sits
 * *before* it in the chain — so the chip's handler consumes the down event first and the long
 * press never sees it.
 *
 * This modifier therefore does not try to win the gesture. It listens with
 * `requireUnconsumed = false` on the pass that runs after the chip has had its say, and only
 * reports a press that was held past the timeout without moving. A tap is still a tap: the chip's
 * own `onClick` fires and this one stays quiet. A drag past touch slop is treated as a scroll, not
 * a long press, so a chip inside a `LazyRow` still scrolls.
 *
 * The same hand-rolled approach as `BackSwipeContainer`, for the same reason: the built-in gesture
 * helpers cannot express "observe without consuming".
 */
fun Modifier.longPressOnly(onLongPress: () -> Unit): Modifier = this.pointerInput(onLongPress) {
  val slop = viewConfiguration.touchSlop
  val holdMillis = android.view.ViewConfiguration.getLongPressTimeout().toLong()

  awaitEachGesture {
    val down = awaitFirstDown(requireUnconsumed = false)
    val start = down.position

    // Returns null only when the timeout elapsed with the finger still down and still: a long
    // press. Anything else — lifted, moved, cancelled — returns false and stays quiet.
    val ended = withTimeoutOrNull(holdMillis) {
      var pending = true
      while (pending) {
        val event = awaitPointerEvent(PointerEventPass.Final)
        val change = event.changes.firstOrNull { it.id == down.id }
        if (change == null || !change.pressed || movedPast(change.position, start, slop)) {
          pending = false
        }
      }
      false
    }

    if (ended == null) onLongPress()
  }
}

private fun movedPast(current: Offset, start: Offset, slop: Float): Boolean =
  abs(current.x - start.x) > slop || abs(current.y - start.y) > slop
