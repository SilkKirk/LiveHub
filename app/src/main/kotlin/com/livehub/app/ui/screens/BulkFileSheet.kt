package com.livehub.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.livehub.core.model.FollowGroup

/**
 * Picks the groups a bulk selection should be added to.
 *
 * Multi-select with an explicit 确认, unlike the per-room sheet: adding five rooms to the wrong
 * group is five writes and five tombstones to undo, so the batch is worth one confirm tap. The
 * per-room sheet stays immediate because a single membership is one tap to reverse.
 *
 * Drawn in the activity window rather than as a `ModalBottomSheet`, for the same reason as the
 * other panels: the screenshot harness captures `onRoot()`, and a dialog is a separate window it
 * cannot see.
 */
@Composable
internal fun BulkFileSheet(
  groups: List<FollowGroup>,
  count: Int,
  onDismiss: () -> Unit,
  onPick: (Set<String>) -> Unit,
) {
  var chosen by remember { mutableStateOf(emptySet<String>()) }
  BackHandler { onDismiss() }

  Box(modifier = Modifier.fillMaxSize()) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.45f))
        .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    )
    Surface(
      modifier = Modifier.fillMaxWidth().fillMaxHeight(0.55f).align(Alignment.BottomCenter),
      shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
      color = MaterialTheme.colorScheme.surface,
      tonalElevation = 3.dp,
      shadowElevation = 8.dp,
    ) {
      Column(modifier = Modifier.fillMaxSize().padding(top = 16.dp)) {
        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
          verticalAlignment = Alignment.CenterVertically,
        ) {
          Text(
            text = "把 $count 位主播放进分组",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
          )
          TextButton(onClick = onDismiss) { Text("取消") }
          TextButton(onClick = { onPick(chosen) }, enabled = chosen.isNotEmpty()) { Text("放入") }
        }

        if (groups.isEmpty()) {
          Text(
            text = "还没有分组。先用「管理」建一个，再回来批量放入。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp),
          )
        } else {
          LazyColumn(
            contentPadding = PaddingValues(vertical = 8.dp),
            modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
          ) {
            items(groups, key = { it.id }) { group ->
              val selected = group.id in chosen
              Row(
                modifier = Modifier
                  .fillMaxWidth()
                  .clickable {
                    chosen = if (selected) chosen - group.id else chosen + group.id
                  }
                  .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
              ) {
                Text(
                  text = group.name,
                  style = MaterialTheme.typography.bodyLarge,
                  fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                  color = if (selected) {
                    MaterialTheme.colorScheme.primary
                  } else {
                    MaterialTheme.colorScheme.onSurface
                  },
                  modifier = Modifier.weight(1f),
                )
                if (selected) {
                  Icon(
                    imageVector = Icons.Filled.Check,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp),
                  )
                }
              }
            }
          }
        }

        Text(
          text = "只添加，不会把主播从其他分组移出。",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }
    }
  }
}
