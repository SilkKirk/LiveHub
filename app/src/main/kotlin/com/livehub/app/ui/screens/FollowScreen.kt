package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.livehub.app.di.FollowOrdering
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.app.ui.components.EmptyState
import com.livehub.app.ui.components.RefreshableBox
import com.livehub.app.ui.components.ReorderableFollowList
import com.livehub.core.model.FollowGroup
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer

/** Which slice of the follow list is visible. */
private enum class FollowFilter(val label: String) {
  ALL("全部"),
  LIVE("直播中"),
  OFFLINE("未开播"),
}

/**
 * Follow list.
 *
 * Two independent axes, one row each. The **group** row is the primary navigation — a room can
 * sit in several groups, so the chips are a multi-select filter rather than a single choice —
 * and the **state/platform** row stays a plain filter. They used to share one `LazyRow`, which
 * made "直播中" and "斗鱼" look like members of the same set when they are not.
 *
 * Ordering inside the list is the user's manual arrangement (long press + drag), which is itself
 * a synchronised value. The live-first sort is a *view* rule only: it used to be written back to
 * the store on every drag, which sent whoever went offline to the bottom of the list for good.
 */
@Composable
fun FollowScreen(state: UiState, onAction: (UiAction) -> Unit) {
  var filter by remember { mutableStateOf(FollowFilter.ALL) }
  var platformFilter by remember { mutableStateOf<Platform?>(null) }
  var sheetOpen by remember { mutableStateOf(false) }
  var filing by remember { mutableStateOf<Streamer?>(null) }
  // Bulk filing: a selection set plus a target-group picker. Null means "not selecting".
  var selectedKeys by remember { mutableStateOf<Set<String>?>(null) }
  var bulkTargets by remember { mutableStateOf(false) }

  val selectedGroup = state.selectedFollowGroupId
  val groups = state.followGroups

  /**
   * The rooms in scope, in the order that scope uses.
   *
   * Outside a group that is the stored follow order. Inside one, **membership comes from
   * [UiState.roomGroups]** and only the *order* comes from [UiState.groupRoomOrder]: the two are
   * separate facts, and reading the order map as the membership list made a group whose order was
   * not populated fall back to "every followed room" — the group filter silently did nothing.
   */
  val scoped: List<Streamer> = remember(state.followed, state.roomGroups, state.groupRoomOrder, selectedGroup) {
    if (selectedGroup == null) {
      state.followed
    } else {
      val positions = state.groupRoomOrder[selectedGroup].orEmpty()
        .withIndex()
        .associate { it.value to it.index }
      state.followed
        .filter { selectedGroup in state.roomGroups[it.key].orEmpty() }
        .sortedBy { positions[it.key] ?: Int.MAX_VALUE }
    }
  }

  val platforms = remember(state.followed) { state.followed.map { it.platform }.distinct() }

  val visible = remember(scoped, filter, platformFilter) {
    scoped
      .filter { platformFilter == null || it.platform == platformFilter }
      .filter {
        when (filter) {
          FollowFilter.ALL -> true
          FollowFilter.LIVE -> it.isLive
          FollowFilter.OFFLINE -> !it.isLive
        }
      }
      // Live rooms first. The stored order is the user's manual arrangement, but burying whoever
      // is actually streaming under offline names defeated the point of the list. `sortedBy` is
      // stable, so manual order still breaks ties.
      .sortedBy { if (it.isLive) 0 else 1 }
  }

  val liveCount = remember(scoped) { scoped.count { it.isLive } }
  val scopeSize = scoped.size

  Box(modifier = Modifier.fillMaxSize()) {
    if (state.followed.isEmpty()) {
      EmptyState(
        icon = Icons.Outlined.StarOutline,
        title = "还没有关注的主播",
        description = "在首页点击卡片右下角的星标即可关注。关注列表会通过局域网同步到你的其他设备，" +
          "长按列表项可以拖动排序，点行尾的 ⋮ 可以把主播放进自定义分组。",
      )
    } else {
      Column(modifier = Modifier.fillMaxSize()) {
        FollowHeader(
          total = scopeSize,
          liveCount = liveCount,
          filter = filter,
          onFilterChange = { filter = it },
          platforms = platforms,
          platformFilter = platformFilter,
          onPlatformChange = { platformFilter = it },
          groups = groups,
          selectedGroupId = selectedGroup,
          onSelectGroup = { onAction(UiAction.SelectFollowGroup(it)) },
          onManageGroups = { sheetOpen = true },
          selection = selectedKeys,
          onToggleSelection = {
            selectedKeys = if (selectedKeys == null) emptySet() else null
          },
          onSelectAllVisible = { selectedKeys = visible.map { it.key }.toSet() },
          onFileSelected = { bulkTargets = true },
        )

        if (visible.isEmpty()) {
          EmptyState(
            title = when (filter) {
              FollowFilter.LIVE -> "关注的主播都没有在播"
              FollowFilter.OFFLINE -> "所有关注的主播都在直播"
              FollowFilter.ALL -> if (selectedGroup != null) "这个分组还没有主播" else "没有符合条件的关注"
            },
            description = when {
              selectedGroup != null -> "点行尾的 ⋮ 可以把主播放进这个分组"
              platformFilter != null -> "当前只显示 ${platformFilter!!.displayName} 的主播"
              else -> null
            },
            actionLabel = "显示全部",
            onAction = {
              filter = FollowFilter.ALL
              platformFilter = null
              onAction(UiAction.SelectFollowGroup(null))
            },
          )
        } else {
          RefreshableBox(
            refreshing = state.refreshing,
            onRefresh = { onAction(UiAction.Refresh) },
            modifier = Modifier.fillMaxSize(),
          ) {
            ReorderableFollowList(
              streamers = visible,
              onClick = { streamer -> onAction(UiAction.OpenRoom(streamer)) },
              onReorder = { streamer, index ->
                // Resolve the drop against the list that actually owns the order before handing
                // it over: the row index belongs to `visible`, and writing it straight to the
                // store moved whoever happened to sit at that index in the full list.
                //
                // Inside a group the target is that group's own order, so the reconciliation runs
                // against `scoped` (the group's rooms) rather than the whole list — otherwise
                // arranging rooms inside 游戏 would rewrite the global arrangement.
                val keys = FollowOrdering.moveWithin(scoped, visible, streamer, index)
                if (selectedGroup != null) {
                  onAction(UiAction.SetGroupOrder(selectedGroup, keys))
                } else {
                  onAction(UiAction.SetFollowOrder(keys))
                }
              },
              onRemove = { streamer -> onAction(UiAction.ToggleFollow(streamer)) },
              onFile = { streamer -> filing = streamer },
              groupNamesOf = { streamer ->
                val ids = state.roomGroups[streamer.key].orEmpty()
                groups.filter { it.id in ids }.map { it.name }
              },
              selectedKeys = selectedKeys,
              onToggleSelected = { streamer ->
                val current = selectedKeys
                if (current != null) {
                  selectedKeys = if (streamer.key in current) current - streamer.key else current + streamer.key
                }
              },
              modifier = Modifier.fillMaxSize(),
            )
          }
        }
      }
    }

    val target = filing
    if (sheetOpen || target != null) {
      FollowGroupSheet(
        state = state,
        filing = target,
        onDismiss = {
          sheetOpen = false
          filing = null
        },
        onAction = onAction,
      )
    }

    val keys = selectedKeys
    if (bulkTargets && keys != null) {
      BulkFileSheet(
        groups = groups,
        count = keys.size,
        onDismiss = { bulkTargets = false },
        onPick = { groupIds ->
          val rooms = state.followed.filter { it.key in keys }
          onAction(UiAction.AddRoomsToGroups(rooms, groupIds))
          bulkTargets = false
          selectedKeys = null
        },
      )
    }
  }
}

@Composable
private fun FollowHeader(
  total: Int,
  liveCount: Int,
  filter: FollowFilter,
  onFilterChange: (FollowFilter) -> Unit,
  platforms: List<Platform>,
  platformFilter: Platform?,
  onPlatformChange: (Platform?) -> Unit,
  groups: List<FollowGroup>,
  selectedGroupId: String?,
  onSelectGroup: (String?) -> Unit,
  onManageGroups: () -> Unit,
  /** Non-null while the list is in bulk-selection mode. */
  selection: Set<String>?,
  onToggleSelection: () -> Unit,
  onSelectAllVisible: () -> Unit,
  onFileSelected: () -> Unit,
) {
  if (selection != null) {
    // Selection mode replaces the whole header. Leaving the group chips and the filters on screen
    // would invite the user to change the scope mid-selection, which silently drops the rooms that
    // just left the view from the pending bulk move.
    Row(
      modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp, top = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
        text = "已选 ${selection.size} 位",
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
      )
      Box(modifier = Modifier.weight(1f))
      TextButton(onClick = onSelectAllVisible) { Text("全选") }
      TextButton(onClick = onToggleSelection) { Text("取消") }
      TextButton(onClick = onFileSelected, enabled = selection.isNotEmpty()) { Text("放入分组") }
    }
    return
  }

  Column(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
    Row(
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Text("关注", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
      Text(
        text = "  $liveCount/$total 正在直播",
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Box(modifier = Modifier.weight(1f))
      TextButton(onClick = onToggleSelection) { Text("多选") }
      TextButton(onClick = onManageGroups) { Text("管理") }
    }

    // Group row. Always present: it is how the list is navigated, not an optional filter. 未分类
    // is a virtual chip — a room is unfiled exactly when it has no membership — so it is never
    // created, renamed or deleted.
    LazyRow(
      contentPadding = PaddingValues(horizontal = 12.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      item(key = "group-all") {
        FilterChip(
          selected = selectedGroupId == null,
          onClick = { onSelectGroup(null) },
          label = { Text("全部") },
        )
      }
      items(groups, key = { it.id }) { group ->
        FilterChip(
          selected = selectedGroupId == group.id,
          onClick = { onSelectGroup(if (selectedGroupId == group.id) null else group.id) },
          label = { Text(group.name) },
        )
      }
      item(key = "group-add") {
        IconButton(onClick = onManageGroups, modifier = Modifier.padding(horizontal = 2.dp)) {
          Icon(Icons.Filled.Add, contentDescription = "新建分组")
        }
      }
    }

    // The state/platform row only earns its space when it can actually narrow something.
    if (filter != FollowFilter.ALL || platforms.size > 1) {
      LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(FollowFilter.entries.toList()) { option ->
          FilterChip(
            selected = filter == option,
            onClick = { onFilterChange(option) },
            label = { Text(option.label) },
          )
        }
        if (platforms.size > 1) {
          items(platforms) { platform ->
            FilterChip(
              selected = platformFilter == platform,
              onClick = { onPlatformChange(if (platformFilter == platform) null else platform) },
              label = { Text(platform.displayName) },
            )
          }
        }
      }
    }
  }
}
