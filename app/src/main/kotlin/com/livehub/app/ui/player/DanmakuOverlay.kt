package com.livehub.app.ui.player

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.random.Random

/** One danmaku currently flying across the screen. */
private data class FlyingDanmaku(
  val id: Long,
  val text: String,
  val lane: Int,
  val color: Color,
  val durationMillis: Int,
)

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
 * Each message flies right-to-left in one of [laneCount] lanes. Lanes are chosen
 * round-robin per second so bursts stack instead of overlapping, and blocked keywords
 * are filtered before a message ever enters the list.
 *
 * The composable renders nothing when [enabled] is false, which also stops the
 * collector so a disabled overlay costs no work.
 */
@Composable
fun DanmakuOverlay(
  messages: List<com.livehub.core.model.DanmakuMessage>,
  enabled: Boolean,
  blockedKeywords: List<String>,
  opacity: Float = 1f,
  fontScale: Float = 1f,
  areaFraction: Float = 0.5f,
  modifier: Modifier = Modifier,
) {
  if (!enabled) return

  val blocked = remember(blockedKeywords) { blockedKeywords.map { it.lowercase() }.filter { it.isNotBlank() } }
  val flying = remember { mutableStateListOf<FlyingDanmaku>() }
  var nextId by remember { mutableStateOf(0L) }
  var laneCursor by remember { mutableStateOf(0) }

  val laneCount = (MAX_LANES * areaFraction).toInt().coerceIn(1, MAX_LANES)

  LaunchedEffect(messages) {
    if (messages.isEmpty()) return@LaunchedEffect
    messages.forEach { message ->
      if (blocked.any { keyword -> message.text.lowercase().contains(keyword) }) {
        return@forEach
      }
      val id = nextId++
      flying.add(
        FlyingDanmaku(
          id = id,
          text = message.text,
          lane = laneCursor % laneCount,
          color = message.color?.let { Color(it) } ?: DEFAULT_COLOR,
          durationMillis = FLIGHT_MILLIS,
        ),
      )
      laneCursor += 1
    }
    // Keep the overlay bounded: a busy room can push hundreds of messages a second.
    while (flying.size > MAX_VISIBLE) flying.removeAt(0)
  }

  BoxWithConstraints(modifier = modifier.fillMaxSize()) {
    val density = LocalDensity.current
    val widthPx = with(density) { maxWidth.toPx() }
    val laneHeight = with(density) { (24.dp * fontScale) .toPx() }

    flying.forEach { item ->
      androidx.compose.runtime.key(item.id) {
        DanmakuLine(
          item = item,
          widthPx = widthPx,
          laneHeight = laneHeight,
          opacity = opacity,
          fontScale = fontScale,
          onFinished = { flying.removeAll { it.id == item.id } },
        )
      }
    }
  }
}

@Composable
private fun DanmakuLine(
  item: FlyingDanmaku,
  widthPx: Float,
  laneHeight: Float,
  opacity: Float,
  fontScale: Float,
  onFinished: () -> Unit,
) {
  val offsetX = remember { Animatable(widthPx) }

  LaunchedEffect(item.id) {
    offsetX.animateTo(
      targetValue = -widthPx * 0.6f,
      animationSpec = tween(durationMillis = item.durationMillis, easing = LinearEasing),
    )
    onFinished()
  }

  Text(
    text = item.text,
    color = item.color.copy(alpha = opacity.coerceIn(0.1f, 1f)),
    style = TextStyle(
      fontSize = (16 * fontScale).sp,
      fontWeight = FontWeight.Medium,
    ),
    maxLines = 1,
    overflow = TextOverflow.Visible,
    softWrap = false,
    modifier = Modifier
      .offset(x = with(LocalDensity.current) { offsetX.value.toDp() }, y = with(LocalDensity.current) { (laneHeight * item.lane).toDp() })
      .fillMaxSize(),
  )
}

private const val MAX_LANES = 12
private const val MAX_VISIBLE = 60
private const val FLIGHT_MILLIS = 8_000
private val DEFAULT_COLOR = Color.White
