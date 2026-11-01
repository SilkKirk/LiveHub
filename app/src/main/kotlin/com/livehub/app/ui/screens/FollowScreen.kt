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
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.app.ui.components.EmptyState
import com.livehub.app.ui.components.RefreshableBox
import com.livehub.app.ui.components.ReorderableFollowList
import com.livehub.core.model.Platform

/** Which slice of the follow list is visible. */
private enum class FollowFilter(val label: String) {
  ALL("全部"),
  LIVE("直播中"),
  OFFLINE("未开播"),
}

/**
 * Follow list.
 *
 * Ordered by the user's manual arrangement (long press + drag), which is itself a
 * synchronised value. A filter row keeps a long follow list usable: most users only
 * care about who is live right now.
 */
@Composable
fun FollowScreen(state: UiState, onAction: (UiAction) -> Unit) {
  var filter by remember { mutableStateOf(FollowFilter.ALL) }
  var platformFilter by remember { mutableStateOf<Platform?>(null) }

  val platforms = remember(state.followed) {
    state.followed.map { it.platform }.distinct()
  }

  val visible = remember(state.followed, filter, platformFilter) {
    state.followed
      .filter { platformFilter == null || it.platform == platformFilter }
      .filter {
        when (filter) {
          FollowFilter.ALL -> true
          FollowFilter.LIVE -> it.isLive
          FollowFilter.OFFLINE -> !it.isLive
        }
      }
      // Live rooms first. The stored order is the user's manual arrangement, but burying
      // whoever is actually streaming under offline names defeated the point of the list.
      // `sortedBy` is stable, so manual order still breaks ties inside each group.
      .sortedBy { if (it.isLive) 0 else 1 }
  }

  val liveCount = remember(state.followed) { state.followed.count { it.isLive } }

  Box(modifier = Modifier.fillMaxSize()) {
    if (state.followed.isEmpty()) {
      EmptyState(
        icon = Icons.Outlined.StarOutline,
        title = "还没有关注的主播",
        description = "在首页点击卡片右下角的星标即可关注。关注列表会通过局域网同步到你的其他设备，长按列表项可以拖动排序。",
      )
    } else {
      Column(modifier = Modifier.fillMaxSize()) {
        FollowHeader(
          total = state.followed.size,
          liveCount = liveCount,
          filter = filter,
          onFilterChange = { filter = it },
          platforms = platforms,
          platformFilter = platformFilter,
          onPlatformChange = { platformFilter = it },
        )

        if (visible.isEmpty()) {
          EmptyState(
            title = when (filter) {
              FollowFilter.LIVE -> "关注的主播都没有在播"
              FollowFilter.OFFLINE -> "所有关注的主播都在直播"
              FollowFilter.ALL -> "没有符合条件的关注"
            },
            description = if (platformFilter != null) {
              "当前只显示 ${platformFilter!!.displayName} 的主播"
            } else {
              null
            },
            actionLabel = "显示全部",
            onAction = {
              filter = FollowFilter.ALL
              platformFilter = null
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
              onReorder = { streamer, index -> onAction(UiAction.MoveFollow(streamer, index)) },
              onRemove = { streamer -> onAction(UiAction.ToggleFollow(streamer)) },
              modifier = Modifier.fillMaxSize(),
            )
          }
        }
      }
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
) {
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
    }

    LazyRow(
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
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
