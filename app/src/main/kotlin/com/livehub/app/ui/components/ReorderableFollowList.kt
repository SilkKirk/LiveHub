package com.livehub.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import coil3.compose.AsyncImage
import com.livehub.core.model.Streamer

/**
 * A reorderable follow list.
 *
 * Long-press a row and drag vertically to reorder. The drag offset is tracked per item
 * so the row follows the finger, and the target index is computed from the accumulated
 * offset divided by the measured row height — no external library is needed for a
 * single-list reorder.
 */
@Composable
fun ReorderableFollowList(
  streamers: List<Streamer>,
  onClick: (Streamer) -> Unit,
  onReorder: (Streamer, Int) -> Unit,
  modifier: Modifier = Modifier,
) {
  val listState = rememberLazyListState()
  var draggingKey by remember { mutableStateOf<String?>(null) }
  var dragOffset by remember { mutableFloatStateOf(0f) }
  val density = androidx.compose.ui.platform.LocalDensity.current.density

  LazyColumn(
    state = listState,
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(8.dp),
    contentPadding = androidx.compose.foundation.layout.PaddingValues(12.dp),
  ) {
    items(streamers, key = { it.key }) { streamer ->
      val isDragging = draggingKey == streamer.key
      FollowRow(
        streamer = streamer,
        isDragging = isDragging,
        onClick = { onClick(streamer) },
        dragModifier = Modifier.pointerInput(streamer.key, streamers.size) {
          detectDragGesturesAfterLongPress(
            onDragStart = {
              draggingKey = streamer.key
              dragOffset = 0f
            },
            onDrag = { change, amount ->
              change.consume()
              dragOffset += amount.y
              // ~72dp per row; convert the accumulated drag into a target index.
              val rowHeightPx = ROW_HEIGHT_DP * density
              val steps = (dragOffset / rowHeightPx).toInt()
              if (steps != 0) {
                val current = streamers.indexOfFirst { it.key == streamer.key }
                val target = (current + steps).coerceIn(0, streamers.lastIndex)
                if (target != current) {
                  onReorder(streamer, target)
                  dragOffset = 0f
                }
              }
            },
            onDragEnd = {
              draggingKey = null
              dragOffset = 0f
            },
            onDragCancel = {
              draggingKey = null
              dragOffset = 0f
            },
          )
        },
      )
    }
  }
}

@Composable
private fun FollowRow(
  streamer: Streamer,
  isDragging: Boolean,
  onClick: () -> Unit,
  dragModifier: Modifier,
) {
  Surface(
    color = if (isDragging) {
      MaterialTheme.colorScheme.surfaceVariant
    } else {
      MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    },
    shape = RoundedCornerShape(12.dp),
    tonalElevation = if (isDragging) 6.dp else 0.dp,
    modifier = Modifier
      .fillMaxWidth()
      .then(if (isDragging) Modifier.zIndex(1f) else Modifier)
      .clip(RoundedCornerShape(12.dp))
      // The whole row opens the room; the star used to be the only tap target, which
      // made the follow list feel dead.
      .clickable(onClick = onClick)
      .then(dragModifier),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Box {
        // Reuse the same gradient placeholder the grid uses; without it a room whose
        // cover failed to load shows an empty grey square.
        if (streamer.coverUrl.isNullOrBlank() && streamer.avatarUrl.isNullOrBlank()) {
          Box(
            modifier = Modifier
              .size(56.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(
                androidx.compose.ui.graphics.Brush.linearGradient(
                  listOf(
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.55f),
                    MaterialTheme.colorScheme.secondary.copy(alpha = 0.55f),
                  ),
                ),
              ),
            contentAlignment = Alignment.Center,
          ) {
            Text(
              text = streamer.streamerName.firstOrNull()?.toString() ?: "?",
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onPrimary,
            )
          }
        } else {
          AsyncImage(
            model = streamer.coverUrl ?: streamer.avatarUrl,
            contentDescription = streamer.streamerName,
            contentScale = ContentScale.Crop,
            modifier = Modifier
              .size(56.dp)
              .clip(RoundedCornerShape(8.dp))
              .background(MaterialTheme.colorScheme.surfaceVariant),
          )
        }
        if (streamer.isLive) {
          Surface(
            color = com.livehub.core.designsystem.LiveBadgeColor,
            shape = CircleShape,
            modifier = Modifier.align(Alignment.TopStart).padding(2.dp),
          ) {
            Text(
              text = "LIVE",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.padding(horizontal = 4.dp),
            )
          }
        }
      }

      Column(modifier = Modifier.weight(1f)) {
        Text(
          text = streamer.streamerName.ifBlank { "未知主播" },
          style = MaterialTheme.typography.titleSmall,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = streamer.title.ifBlank { "暂无标题" },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }

      Icon(
        imageVector = Icons.Filled.DragHandle,
        contentDescription = "长按拖动排序",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private const val ROW_HEIGHT_DP = 76
