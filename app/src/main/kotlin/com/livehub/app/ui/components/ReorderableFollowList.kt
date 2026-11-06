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
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Circle
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
import com.livehub.core.common.ViewerCountFormatter
import com.livehub.core.model.Streamer

/**
 * A reorderable follow list.
 *
 * Long-press a row and drag vertically to reorder. The drag offset is tracked per item
 * so the row follows the finger, and the target index is computed from the accumulated
 * offset divided by the measured row height — no external library is needed for a
 * single-list reorder.
 *
 * [onReorder] receives an index into **this** list, which is a filtered view. Resolving that
 * index against the stored order is the caller's job — see
 * [com.livehub.app.di.FollowOrdering.moveWithin].
 */
@Composable
fun ReorderableFollowList(
  streamers: List<Streamer>,
  onClick: (Streamer) -> Unit,
  onReorder: (Streamer, Int) -> Unit,
  modifier: Modifier = Modifier,
  onRemove: ((Streamer) -> Unit)? = null,
  onFile: ((Streamer) -> Unit)? = null,
  groupNamesOf: ((Streamer) -> List<String>) = { emptyList() },
  /**
   * When non-null the list is in selection mode: tapping a row toggles it instead of opening it,
   * and dragging is disabled so a long press cannot be mistaken for a scroll-selection gesture.
   */
  selectedKeys: Set<String>? = null,
  onToggleSelected: ((Streamer) -> Unit)? = null,
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
      val selecting = selectedKeys != null
      FollowRow(
        streamer = streamer,
        isDragging = isDragging,
        selected = selectedKeys?.contains(streamer.key) == true,
        selecting = selecting,
        onClick = {
          if (selecting && onToggleSelected != null) onToggleSelected(streamer) else onClick(streamer)
        },
        // The star and the ⋮ both change meaning in selection mode — "unfollow" on a multi-select
        // is a destructive action nobody asked for — so they are hidden rather than reinterpreted.
        onRemove = if (selecting) null else onRemove?.let { remove -> { remove(streamer) } },
        onFile = if (selecting) null else onFile?.let { file -> { file(streamer) } },
        groupNames = groupNamesOf(streamer),
        modifier = Modifier.pointerInput(streamer.key, streamers.size, selecting) {
          // Dragging is off in selection mode: a long press that both selects and starts a reorder
          // is ambiguous, and reordering is a per-list action rather than a bulk one.
          if (selecting) return@pointerInput
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
  modifier: Modifier,
  selected: Boolean = false,
  selecting: Boolean = false,
  onRemove: (() -> Unit)? = null,
  onFile: (() -> Unit)? = null,
  groupNames: List<String> = emptyList(),
) {
  // A live room gets the primary-colour border the reference uses, so a long list can be
  // scanned for "who is on right now" without reading the badges. In selection mode the border
  // means "selected" instead — one border, one meaning at a time.
  val border = when {
    selecting && selected -> androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    selecting -> null
    streamer.isLive -> androidx.compose.foundation.BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
    else -> null
  }
  Surface(
    color = when {
      selecting && selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)
      isDragging -> MaterialTheme.colorScheme.surfaceVariant
      else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
    },
    shape = RoundedCornerShape(12.dp),
    border = border,
    tonalElevation = if (isDragging) 6.dp else 0.dp,
    modifier = modifier
      .fillMaxWidth()
      .then(if (isDragging) Modifier.zIndex(1f) else Modifier)
      .clip(RoundedCornerShape(12.dp))
      // The whole row opens the room; the star used to be the only tap target, which
      // made the follow list feel dead.
      .clickable(onClick = onClick),
  ) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(10.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      if (selecting) {
        Icon(
          imageVector = if (selected) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
          contentDescription = if (selected) "已选中 ${streamer.streamerName}" else "未选中 ${streamer.streamerName}",
          tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(22.dp),
        )
      }
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
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
          Text(
            text = streamer.streamerName.ifBlank { "未知主播" },
            style = MaterialTheme.typography.titleSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // `fill = false` so a short name does not push the audience figure to the edge of
            // the column: the figure sits right after the name, the way the reference's
            // 关注列表 puts it on the card.
            modifier = Modifier.weight(1f, fill = false),
          )
          // The one place in the app that showed a followed room without its audience figure.
          // The store has carried `viewerCount` all along (`LibraryStore`/`LibraryCodec`
          // write it, `FollowRefreshWorker` refreshes it), and the reference's 关注列表 prints
          // it on every card, so the only thing missing was this Text. 0 means "unknown", not
          // "empty room".
          if (streamer.viewerCount > 0) {
            Text(
              text = ViewerCountFormatter.format(streamer.viewerCount),
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.primary,
              maxLines = 1,
            )
          }
        }
        Text(
          text = streamer.title.ifBlank { "暂无标题" },
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        // Which groups this room is filed into. Rendered only when there is at least one: an
        // empty line on every unfiled row would be pure noise, and 未分类 is the default.
        if (groupNames.isNotEmpty()) {
          Text(
            text = groupNames.joinToString(" · "),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.primary.copy(alpha = 0.85f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }

      if (onRemove != null) {
        // Unfollowing used to be impossible from this screen: the only remove affordance
        // was the star on the grid card, which this list does not use.
        IconButton(onClick = onRemove, modifier = Modifier.size(36.dp)) {
          Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = "取消关注 ${streamer.streamerName}",
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(18.dp),
          )
        }
      }

      if (onFile != null) {
        // Filing into groups. A menu icon rather than a second icon row: at 320dp the row
        // already carries a cover, two text lines, the star and the drag handle.
        IconButton(onClick = onFile, modifier = Modifier.size(36.dp)) {
          Icon(
            imageVector = Icons.Filled.MoreVert,
            contentDescription = "把 ${streamer.streamerName} 放进分组",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(18.dp),
          )
        }
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
