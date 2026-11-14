package com.livehub.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.livehub.core.designsystem.LiveBadgeColor
import com.livehub.core.model.Streamer

/**
 * The 特别关注 row: circular avatars above the grid, long-press to drag.
 *
 * This is the only reorderable surface on the follow screen now that the body is a two-column
 * grid. Dragging inside a grid means resolving a two-dimensional offset into a cell, and that
 * gesture fights the scroll on every diagonal movement; a one-row strip has neither problem, so
 * the arrangement the user makes here is the one that survives.
 *
 * The trailing ＋ opens the picker rather than creating anything: a room becomes 特别关注 by being
 * followed and starred, and a second way to add would be a second thing to keep in sync.
 */
@Composable
fun SpecialFollowRow(
  streamers: List<Streamer>,
  onClick: (Streamer) -> Unit,
  onReorder: (Streamer, Int) -> Unit,
  onAdd: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  var draggingKey by remember { mutableStateOf<String?>(null) }
  var dragOffset by remember { mutableFloatStateOf(0f) }
  val density = androidx.compose.ui.platform.LocalDensity.current.density

  LazyRow(
    state = listState,
    modifier = modifier.fillMaxWidth(),
    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(AVATAR_GAP_DP.dp),
    verticalAlignment = Alignment.Top,
  ) {
    items(streamers, key = { it.key }) { streamer ->
      SpecialAvatar(
        streamer = streamer,
        dragging = draggingKey == streamer.key,
        reorder = reorderActionsFor(streamer, streamers, onReorder),
        // Two official gesture detectors, composed rather than hand-rolled.
        //
        // The old handler was a single `awaitEachGesture` whose only two outcomes were "tap" and
        // "long press". `awaitLongPressOrCancellation` returns null both when the finger lifts
        // early *and* when a parent consumes the movement — and the parent here is the row's own
        // horizontal scroll — so every attempt to swipe the strip fell into the tap branch and
        // opened a room instead of scrolling it. That was the reported "特别关注 不能左右滑动".
        //
        // `detectDragGesturesAfterLongPress` cancels itself when the scroll wins the gesture, and
        // `clickable` cancels its tap when the drag consumes the up. Between them the four
        // outcomes are all covered: tap opens, long-press-then-move reorders, long-press-and-release
        // does nothing (the drag detector consumes the up), and a horizontal swipe scrolls.
        modifier = Modifier
          .clickable(
            onClickLabel = "打开直播间",
            role = Role.Button,
            onClick = { onClick(streamer) },
          )
          .pointerInput(streamer.key, streamers.size) {
            detectDragGesturesAfterLongPress(
              onDragStart = {
                draggingKey = streamer.key
                dragOffset = 0f
              },
              onDragEnd = {
                draggingKey = null
                dragOffset = 0f
              },
              onDragCancel = {
                draggingKey = null
                dragOffset = 0f
              },
              onDrag = { _, dragAmount ->
                // The strip scrolls horizontally, so the delta that addresses a slot is x. One
                // slot is the avatar plus the gap, read from the same constants the row uses.
                dragOffset += dragAmount.x
                val slotPx = (AVATAR_SIZE_DP + AVATAR_GAP_DP) * density
                val steps = (dragOffset / slotPx).toInt()
                if (steps != 0) {
                  val current = streamers.indexOfFirst { it.key == streamer.key }
                  if (current >= 0) {
                    val target = (current + steps).coerceIn(0, streamers.lastIndex)
                    if (target != current) {
                      onReorder(streamer, target)
                      dragOffset = 0f
                    }
                  }
                }
              },
            )
          },
      )
    }
    item(key = "special-add") {
      AddSlot(onClick = onAdd)
    }
  }
}

@Composable
private fun SpecialAvatar(
  streamer: Streamer,
  dragging: Boolean,
  /**
   * The reorder steps the drag offers to a screen reader or a switch.
   *
   * A drag is a delta, which neither can produce; `customActions` re-publishes the two neighbour
   * moves so the strip is not a row of buttons that can be announced and not rearranged. The tap
   * is no longer listed here — `clickable` owns it now and publishes its own click action (with
   * the 打开直播间 label), which is also what gives the slot its ripple and role.
   */
  reorder: List<CustomAccessibilityAction>,
  modifier: Modifier = Modifier,
) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = modifier
      .then(if (dragging) Modifier.zIndex(1f) else Modifier)
      .size(width = AVATAR_SIZE_DP.dp, height = AVATAR_SLOT_HEIGHT_DP.dp)
      // No `clip` here: it would cut the name line into a circle along with the avatar. The
      // semantics label carries the same information for TalkBack, which is what the clip was
      // standing in for.
      .semantics {
        contentDescription = "特别关注 ${streamer.streamerName}"
        // Only the neighbours that exist; see reorderActionsFor.
        if (reorder.isNotEmpty()) customActions = reorder
      },
  ) {
    Box(contentAlignment = Alignment.Center) {
      // The ring is the live indicator: the reference draws a green circle around whoever is
      // streaming, which is the whole reason a separate row earns its space — you see who is on
      // without reading the grid.
      Box(
        modifier = Modifier
          .size(AVATAR_SIZE_DP.dp)
          .clip(CircleShape)
          .background(
            if (streamer.isLive) LiveBadgeColor.copy(alpha = 0.16f) else Color.Transparent,
          )
          .border(
            width = 2.dp,
            color = if (streamer.isLive) LiveBadgeColor else MaterialTheme.colorScheme.outlineVariant,
            shape = CircleShape,
          ),
        contentAlignment = Alignment.Center,
      ) {
        AvatarImage(streamer = streamer, sizeDp = AVATAR_IMAGE_DP)
      }
    }
    Text(
      text = streamer.streamerName.ifBlank { "未知主播" },
      style = MaterialTheme.typography.labelSmall,
      color = if (streamer.isLive) {
        MaterialTheme.colorScheme.onSurface
      } else {
        MaterialTheme.colorScheme.onSurfaceVariant
      },
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 6.dp),
    )
  }
}

/** The circular portrait, with the same deterministic gradient fallback the cards use. */
@Composable
private fun AvatarImage(streamer: Streamer, sizeDp: Int) {
  val url = streamer.avatarUrl?.takeIf { it.isNotBlank() }
    ?: streamer.coverUrl?.takeIf { it.isNotBlank() }
  if (url == null) {
    val palette = listOf(
      Color(0xFF2C3E50) to Color(0xFF4CA1AF),
      Color(0xFF3A1C71) to Color(0xFFD76D77),
      Color(0xFF134E5E) to Color(0xFF71B280),
      Color(0xFF642B73) to Color(0xFFC6426E),
    )
    val seed = streamer.streamerName.ifBlank { streamer.roomId }
    val pair = palette[(seed.hashCode() and Int.MAX_VALUE) % palette.size]
    Box(
      modifier = Modifier
        .size(sizeDp.dp)
        .clip(CircleShape)
        .background(Brush.linearGradient(listOf(pair.first, pair.second))),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = seed.firstOrNull()?.toString() ?: "?",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        color = Color.White,
      )
    }
  } else {
    AsyncImage(
      model = url,
      contentDescription = streamer.streamerName,
      contentScale = ContentScale.Crop,
      modifier = Modifier
        .size(sizeDp.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant),
    )
  }
}

@Composable
private fun AddSlot(onClick: () -> Unit) {
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier.size(width = AVATAR_SIZE_DP.dp, height = AVATAR_SLOT_HEIGHT_DP.dp),
  ) {
    IconButton(onClick = onClick, modifier = Modifier.size(AVATAR_SIZE_DP.dp)) {
      Icon(
        imageVector = Icons.Filled.Add,
        contentDescription = "添加特别关注",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(26.dp),
      )
    }
    Text(
      text = "添加",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      maxLines = 1,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 6.dp),
    )
  }
}

/**
 * The reorder outcomes that exist for one slot, as accessibility actions.
 *
 * The drag handler turns a pixel delta into a target index. A menu cannot express a delta, so a
 * screen reader gets the two steps it can say — and nothing here is new behaviour: both entries call
 * the `onReorder` the gesture calls, with the index next door. A slot at either end offers only the
 * direction it can still move: an 上移 that does nothing reads as a broken control rather than as
 * the start of the row.
 */
private fun reorderActionsFor(
  streamer: Streamer,
  streamers: List<Streamer>,
  onReorder: (Streamer, Int) -> Unit,
): List<CustomAccessibilityAction> {
  val from = streamers.indexOfFirst { it.key == streamer.key }
  val actions = mutableListOf<CustomAccessibilityAction>()
  if (from > 0) {
    actions += CustomAccessibilityAction(label = "上移") {
      onReorder(streamer, from - 1)
      true
    }
  }
  if (from in 0 until streamers.lastIndex) {
    actions += CustomAccessibilityAction(label = "下移") {
      onReorder(streamer, from + 1)
      true
    }
  }
  return actions
}

/** Outer diameter of the ring, which is also the touch target. */
private const val AVATAR_SIZE_DP = 56

/** Diameter of the portrait inside the ring; the difference is the 2dp ring plus breathing room. */
private const val AVATAR_IMAGE_DP = 48

/** Horizontal gap between slots. The drag maths adds it to the avatar to get one slot. */
private const val AVATAR_GAP_DP = 14

/** Ring plus the name line, so every column in the row is the same height. */
private const val AVATAR_SLOT_HEIGHT_DP = 86
