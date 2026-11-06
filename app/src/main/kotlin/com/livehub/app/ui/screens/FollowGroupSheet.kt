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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.model.FollowGroup
import com.livehub.core.model.Streamer

/**
 * The group sheet: create, rename, delete and order groups, and file one room into them.
 *
 * Two modes over one panel, because they are the same job seen from two sides — [filing] null is
 * 分组管理, non-null is 把这位主播放进哪些分组.
 *
 * Drawn inside the activity window rather than as a `ModalBottomSheet`, for the same reason
 * [CategoryPicker] is: the screenshot harness captures `onRoot()` of the activity, and a
 * dialog-hosted sheet is a separate window it cannot see — which would leave this screen with no
 * visual evidence at all.
 */
@Composable
internal fun FollowGroupSheet(
  state: UiState,
  filing: Streamer?,
  onDismiss: () -> Unit,
  onAction: (UiAction) -> Unit,
) {
  var draft by remember { mutableStateOf("") }
  var renaming by remember { mutableStateOf<String?>(null) }
  var renameDraft by remember { mutableStateOf("") }
  var confirmingDelete by remember { mutableStateOf<String?>(null) }

  BackHandler { onDismiss() }

  Box(modifier = Modifier.fillMaxSize()) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.45f))
        .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    )
    Surface(
      modifier = Modifier.fillMaxWidth().fillMaxHeight(0.62f).align(Alignment.BottomCenter),
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
            text = if (filing != null) "把「${filing.streamerName}」放进分组" else "分组管理",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
          )
          TextButton(onClick = onDismiss) { Text("完成") }
        }

        if (filing != null) {
          FilingList(
            state = state,
            filing = filing,
            onAction = onAction,
            modifier = Modifier.weight(1f, fill = false),
          )
        } else {
          ManageList(
            state = state,
            renaming = renaming,
            renameDraft = renameDraft,
            confirmingDelete = confirmingDelete,
            onStartRename = { group ->
              renaming = group.id
              renameDraft = group.name
            },
            onRenameDraft = { renameDraft = it },
            onCommitRename = {
              renaming?.let { id -> onAction(UiAction.RenameFollowGroup(id, renameDraft)) }
              renaming = null
            },
            onStartDelete = { confirmingDelete = it },
            onCancelDelete = { confirmingDelete = null },
            onDelete = { id ->
              onAction(UiAction.DeleteFollowGroup(id))
              confirmingDelete = null
            },
            modifier = Modifier.weight(1f, fill = false),
          )
        }

        Row(
          modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          OutlinedTextField(
            value = draft,
            onValueChange = { if (it.length <= 20) draft = it },
            placeholder = { Text("新建分组…") },
            singleLine = true,
            modifier = Modifier.weight(1f),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
              onDone = {
                if (draft.isNotBlank()) {
                  onAction(UiAction.CreateFollowGroup(draft))
                  draft = ""
                }
              },
            ),
          )
          TextButton(
            onClick = {
              if (draft.isNotBlank()) {
                onAction(UiAction.CreateFollowGroup(draft))
                draft = ""
              }
            },
          ) { Text("新建") }
        }
      }
    }
  }
}

/**
 * Multi-select list of groups for one room.
 *
 * Toggles apply immediately rather than on a confirm button: filing a room is a reversible
 * switch, and a confirm step would make "put this room in three groups" a four-tap flow ending in
 * a save. 未分类 is exclusive — picking it clears every membership — because "unfiled" is the
 * absence of memberships, not a group of its own.
 */
@Composable
private fun FilingList(
  state: UiState,
  filing: Streamer,
  onAction: (UiAction) -> Unit,
  modifier: Modifier = Modifier,
) {
  val current = state.roomGroups[filing.key].orEmpty()
  LazyColumn(
    contentPadding = PaddingValues(vertical = 8.dp),
    modifier = modifier.fillMaxWidth(),
  ) {
    item(key = "unfiled") {
      ToggleRow(
        label = FollowGroup.UNFILED_NAME,
        selected = current.isEmpty(),
        onClick = { onAction(UiAction.SetRoomGroups(filing, emptySet())) },
      )
    }
    items(state.followGroups, key = { it.id }) { group ->
      ToggleRow(
        label = group.name,
        selected = group.id in current,
        onClick = {
          val next = if (group.id in current) current - group.id else current + group.id
          onAction(UiAction.SetRoomGroups(filing, next))
        },
      )
    }
    if (state.followGroups.isEmpty()) {
      item(key = "empty-hint") {
        Text(
          text = "还没有分组。在下面输入一个名字就能建。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }
    }
  }
}

@Composable
private fun ManageList(
  state: UiState,
  renaming: String?,
  renameDraft: String,
  confirmingDelete: String?,
  onStartRename: (FollowGroup) -> Unit,
  onRenameDraft: (String) -> Unit,
  onCommitRename: () -> Unit,
  onStartDelete: (String) -> Unit,
  onCancelDelete: () -> Unit,
  onDelete: (String) -> Unit,
  modifier: Modifier = Modifier,
) {
  LazyColumn(
    contentPadding = PaddingValues(vertical = 8.dp),
    modifier = modifier.fillMaxWidth(),
  ) {
    items(state.followGroups, key = { it.id }) { group ->
      val count = state.roomGroups.count { (_, ids) -> group.id in ids }
      Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
          containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        ),
      ) {
        Column(modifier = Modifier.padding(12.dp)) {
          if (renaming == group.id) {
            OutlinedTextField(
              value = renameDraft,
              onValueChange = { if (it.length <= 20) onRenameDraft(it) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
              keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
              keyboardActions = KeyboardActions(onDone = { onCommitRename() }),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
              TextButton(onClick = onCommitRename) { Text("保存") }
            }
          } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = group.name,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
              )
              Text(
                text = "$count 位",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              IconButton(onClick = { onStartRename(group) }, modifier = Modifier.size(36.dp)) {
                Icon(
                  Icons.Filled.Edit,
                  contentDescription = "重命名 ${group.name}",
                  modifier = Modifier.size(18.dp),
                )
              }
              IconButton(onClick = { onStartDelete(group.id) }, modifier = Modifier.size(36.dp)) {
                Icon(
                  Icons.Filled.Delete,
                  contentDescription = "删除 ${group.name}",
                  tint = MaterialTheme.colorScheme.error,
                  modifier = Modifier.size(18.dp),
                )
              }
            }
          }

          if (confirmingDelete == group.id) {
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = "删除后组内主播回到未分类",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.weight(1f),
              )
              TextButton(onClick = onCancelDelete) { Text("取消") }
              TextButton(onClick = { onDelete(group.id) }) { Text("删除") }
            }
          }
        }
      }
    }
    if (state.followGroups.isEmpty()) {
      item(key = "no-groups") {
        Text(
          text = "还没有分组。在下面输入一个名字就能建。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        )
      }
    }
  }
}

@Composable
private fun ToggleRow(label: String, selected: Boolean, onClick: () -> Unit) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(horizontal = 16.dp, vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
      color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
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
