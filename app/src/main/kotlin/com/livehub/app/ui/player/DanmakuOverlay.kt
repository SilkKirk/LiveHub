package com.livehub.app.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * One danmaku currently flying across the screen.
 *
 * `internal` rather than private so [DanmakuLine] can be driven directly from a test: the JVM
 * test runner has no font backend, so labels there have no measurable width and an
 * overlay-level test cannot tell a long message from a short one. Handing the line a
 * synthetic item with a real width is the only way to pin its timing from a JVM test.
 */
internal data class FlyingDanmaku(
  val id: Long,
  val text: String,
  val lane: Int,
  val color: Color,
  /** Measured width of this label, used for both the exit point and lane scheduling. */
  val textWidthPx: Float,
  /** How long this particular label takes to cross, from [flightDurationMs]. */
  val flightMillis: Long,
)

/**
 * Picks the lane that becomes free soonest, or null when every lane is busy.
 *
 * Mirrors the reference client's `chooseTrack`: prefer a lane that is already clear, then
 * tolerate one that frees within [LANE_TOLERANCE_MILLIS] so a steady stream keeps flowing
 * instead of stuttering once per lane cycle. Returning null is deliberate — under a burst
 * heavier than the lanes can carry, dropping a danmaku is what every live client does;
 * stacking them on top of each other makes the overlay unreadable instead.
 */
internal fun chooseLane(laneAvailableAt: LongArray, now: Long): Int? {
  var bestLane = -1
  var bestReadyAt = Long.MAX_VALUE
  laneAvailableAt.forEachIndexed { lane, readyAt ->
    if (readyAt <= now) return lane
    if (readyAt < bestReadyAt) {
      bestReadyAt = readyAt
      bestLane = lane
    }
  }
  return bestLane.takeIf { bestReadyAt <= now + LANE_TOLERANCE_MILLIS }
}

/**
 * How long a label needs to cross the overlay, in milliseconds.
 *
 * The overlay scrolls at a constant speed, not for a constant duration. A fixed duration
 * gives every label the same time on screen no matter how far it has to travel, so the
 * speed becomes a function of how long the message happens to be. With the old 8 s and a
 * screen that fits twenty-two CJK characters, a three-character danmaku moved at
 * 0.14 px/ms and a thirty-character one at 0.30 px/ms — the same word read at half or
 * double the pace depending on how much company it kept inside the message, and in
 * characters-per-second past a fixed point, 3.1 against 6.5. Constant px/s is what removes
 * that spread; it is also what every mainstream client does.
 *
 * The reference client arrives at the same place from the other side: `ScrollingDanmakuItem`
 * hardcodes 9000 ms, but it also travels a fixed distance (`startX = widthPx`,
 * `endX = -widthPx`), so its speed is constant and only its duration is written down. That
 * is not support for a fixed *duration* — this overlay's travel is `screen + label`, which
 * varies, so the duration has to be derived instead of fixed.
 *
 * [MILLIS_PER_SCREEN_WIDTH] is calibrated so a typical ten-character danmaku — about 0.45
 * screen widths at the default text size — keeps the 8 s the old fixed duration gave it.
 * Shorter labels therefore cross faster than before and longer ones slower, which is the
 * whole point of the change.
 *
 * The result is clamped to [MAX_FLIGHT_MILLIS]: a label that would need half a minute would
 * hold its lane that long, and under a burst the overlay would starve instead of dropping
 * danmaku. Only messages past roughly forty characters are affected, and they scroll a
 * little faster than the baseline rather than being cut off.
 */
internal fun flightDurationMs(textWidthPx: Float, screenWidthPx: Float): Long {
  // Coerced rather than guarded: the caller passes a measured width that can legitimately be
  // zero on the first frame, and a division by a zero screen would be a crash for no reason.
  val screen = screenWidthPx.coerceAtLeast(1f)
  val travel = screen + textWidthPx.coerceAtLeast(1f)
  return ((travel / screen) * MILLIS_PER_SCREEN_WIDTH).toLong().coerceAtMost(MAX_FLIGHT_MILLIS)
}

/**
 * How long until the tail of a label has cleared the right edge.
 *
 * The label enters at the right edge and leaves fully past the left, so it travels
 * `screenWidth + textWidth`. It has cleared the entry point once it has moved its own
 * width, which is that fraction of the whole flight.
 */
internal fun laneFreeAfterMs(textWidthPx: Float, screenWidthPx: Float, flightMillis: Long): Long {
  if (screenWidthPx <= 0f || textWidthPx <= 0f) return flightMillis
  val travel = screenWidthPx + textWidthPx
  return ((textWidthPx / travel) * flightMillis).toLong() + LANE_GAP_MILLIS
}

/**
 * Filters blocked keywords out of a danmaku list.
 *
 * Both surfaces need this: the overlay used to filter inline while the portrait list
 * rendered `state.danmaku` raw, so a blocked word disappeared in full screen and came
 * back as soon as the user left it. Keeping one function means there is no second place
 * for the rule to drift.
 *
 * Matching is case-insensitive and substring-based, which is what users expect from a
 * "blocked words" list.
 */
fun List<com.livehub.core.model.DanmakuMessage>.withoutBlockedKeywords(
  blockedKeywords: List<String>,
): List<com.livehub.core.model.DanmakuMessage> {
  val blocked = blockedKeywords.map { it.trim().lowercase() }.filter { it.isNotEmpty() }
  if (blocked.isEmpty()) return this
  return filter { message ->
    val text = message.text.lowercase()
    blocked.none { keyword -> text.contains(keyword) }
  }
}

/**
 * Danmaku overlay.
 *
 * Each message flies right-to-left in one of [laneCount] lanes. A lane is not handed out
 * until its previous occupant's tail has cleared the right edge, so a long message cannot
 * be overtaken by a short one entering behind it — the failure mode of plain round-robin
 * assignment. Blocked keywords are filtered before a message ever enters the list.
 *
 * The composable renders nothing when [enabled] is false, which also stops the collector
 * so a disabled overlay costs no work.
 */
@Composable
fun DanmakuOverlay(
  messages: List<com.livehub.core.model.DanmakuMessage>,
  enabled: Boolean,
  blockedKeywords: List<String>,
  modifier: Modifier = Modifier,
  opacity: Float = 1f,
  fontScale: Float = 1f,
  areaFraction: Float = 0.5f,
) {
  if (!enabled) return

  val blocked = remember(blockedKeywords) {
    blockedKeywords.map { it.lowercase() }.filter { it.isNotBlank() }
  }
  val flying = remember { mutableStateListOf<FlyingDanmaku>() }
  var nextId by remember { mutableStateOf(0L) }
  /**
   * How far into [messages] the overlay has already animated.
   *
   * `LaunchedEffect(messages)` re-runs on every emission, and `messages` is the *whole*
   * rolling buffer (up to 200 entries) rather than the new message. Iterating it each time
   * re-added every older danmaku, so one new message per second put a couple of hundred
   * extra bubbles on screen and the lane cursor wrapped constantly. The offset makes each
   * message enter exactly once.
   */
  var consumedCount by remember { mutableStateOf(0) }

  val measurer = rememberTextMeasurer()
  val density = LocalDensity.current
  val textStyle = remember(fontScale) {
    TextStyle(fontSize = (16 * fontScale).sp, fontWeight = FontWeight.Medium)
  }

  val laneCount = (MAX_LANES * areaFraction).toInt().coerceIn(1, MAX_LANES)

  BoxWithConstraints(modifier = modifier.fillMaxSize().clipToBounds()) {
    val screenWidthPx = with(density) { maxWidth.toPx() }.coerceAtLeast(1f)
    val laneHeightDp = 24.dp * fontScale
    val laneHeightPx = with(density) { laneHeightDp.toPx() }

    /**
     * When each lane is next free.
     *
     * The values are in the frame clock's own timebase, which is what the animation
     * actually advances on — so a lane's hand-off lines up with where the previous label
     * really is. `withFrameMillis` inside the effect gives us that clock; wall time would
     * drift out of step with a frozen or slowed test clock.
     */
    val laneAvailableAt = remember(laneCount) { LongArray(laneCount) { 0L } }

    LaunchedEffect(messages, laneCount, screenWidthPx) {
      // The buffer is trimmed at the front once it exceeds the cap, which shifts every index.
      // A shorter list than the consumed count means that happened, so start over rather than
      // skip the new tail.
      if (messages.size < consumedCount) consumedCount = 0
      val pending = messages.drop(consumedCount)
      consumedCount = messages.size
      if (pending.isEmpty()) return@LaunchedEffect

      withFrameMillis { frameTime ->
        pending.forEach { message ->
          if (blocked.any { keyword -> message.text.lowercase().contains(keyword) }) {
            return@forEach
          }
          val text = message.text.trim()
          if (text.isEmpty()) return@forEach

          // Measured, not estimated: the exit point and the lane hand-off both depend on the
          // real width, and a character-count guess drifts badly between a row of "|||" and
          // a row of wide CJK glyphs.
          val textWidthPx = measurer
            .measure(text, textStyle, maxLines = 1, softWrap = false)
            .size
            .width
            .toFloat()
            .coerceAtLeast(1f)

          val lane = chooseLane(laneAvailableAt, frameTime) ?: return@forEach
          val flightMillis = flightDurationMs(textWidthPx, screenWidthPx)

          flying.add(
            FlyingDanmaku(
              id = nextId++,
              text = text,
              lane = lane,
              color = message.color?.let { Color(it) } ?: DEFAULT_COLOR,
              textWidthPx = textWidthPx,
              flightMillis = flightMillis,
            ),
          )
          // The hand-off has to use this label's own flight time: the lane is free once the
          // tail clears the entry point, which is a fraction of how long *this* label is on
          // screen. Reusing one duration for every message would free a wide label's lane
          // long before it had actually moved out of the way.
          laneAvailableAt[lane] =
            frameTime + laneFreeAfterMs(textWidthPx, screenWidthPx, flightMillis)
        }
      }
      // Keep the overlay bounded: a busy room can push hundreds of messages a second.
      while (flying.size > MAX_VISIBLE) flying.removeAt(0)
    }

    flying.forEach { item ->
      key(item.id) {
        DanmakuLine(
          item = item,
          screenWidthPx = screenWidthPx,
          laneHeightPx = laneHeightPx,
          opacity = opacity,
          style = textStyle,
          onFinished = { flying.removeAll { it.id == item.id } },
        )
      }
    }
  }
}

@Composable
internal fun DanmakuLine(
  item: FlyingDanmaku,
  screenWidthPx: Float,
  laneHeightPx: Float,
  opacity: Float,
  style: TextStyle,
  onFinished: () -> Unit,
) {
  // Enters fully off the right edge and leaves fully off the left, so the travel distance
  // is the screen plus the label. Without the label's own width the tail would still be
  // visible when the animation declared itself finished.
  val startX = screenWidthPx
  val endX = -item.textWidthPx
  val offsetX = remember(item.id) { Animatable(startX) }

  LaunchedEffect(item.id, startX, endX) {
    offsetX.snapTo(startX)
    offsetX.animateTo(
      targetValue = endX,
      animationSpec = tween(durationMillis = item.flightMillis.toInt(), easing = LinearEasing),
    )
    onFinished()
  }

  val density = LocalDensity.current
  Text(
    text = item.text,
    color = item.color.copy(alpha = opacity.coerceIn(0.1f, 1f)),
    style = style,
    maxLines = 1,
    overflow = TextOverflow.Visible,
    softWrap = false,
    // Deliberately not `fillMaxSize()`: that forced every bubble to the full overlay width,
    // so a three-character danmaku hit the right edge already, and the right-to-left
    // animation read as a bubble sliding rather than as text scrolling. Wrapping the
    // content keeps the label its natural width.
    modifier = Modifier.offset(
      x = with(density) { offsetX.value.toDp() },
      y = with(density) { (laneHeightPx * item.lane).toDp() },
    ),
  )
}

private const val MAX_LANES = 12
private const val MAX_VISIBLE = 60

/**
 * Baseline speed as time-per-screen-width: a label that is exactly as wide as the screen
 * takes this long, and every other label takes proportionally longer. Expressing the speed
 * relative to the width rather than in pixels is what keeps it feeling the same on a phone
 * and on a tablet.
 */
private const val MILLIS_PER_SCREEN_WIDTH = 5_500f

/** Ceiling on a single flight, so one essay cannot hold a lane long enough to starve the rest. */
private const val MAX_FLIGHT_MILLIS = 15_000L

/** How late a lane may be and still be used, so a steady stream does not stutter. */
private const val LANE_TOLERANCE_MILLIS = 120L

/** Breathing room between one label leaving a lane and the next entering it. */
private const val LANE_GAP_MILLIS = 80L

private val DEFAULT_COLOR = Color.White
