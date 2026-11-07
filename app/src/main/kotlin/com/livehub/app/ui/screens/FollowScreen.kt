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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.livehub.app.di.FollowOrdering
import com.livehub.app.ui.FollowLiveFilter
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.app.ui.components.EmptyState
import com.livehub.app.ui.components.RefreshableBox
import com.livehub.app.ui.components.ReorderableFollowList
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer

/**
 * Follow list: the rooms the user follows, with a state/platform filter row.
 *
 * Ordering inside the list is the user's manual arrangement (long press + drag), which is itself
 * a synchronised value. The live-first sort is a *view* rule only: it used to be written back to
 * the store on every drag, which sent whoever went offline to the bottom of the list for good.
 *
 * The two filters live in `UiState` rather than in a `remember`, because they are selections the
 * user expects to survive opening a room and coming back.
 */
@Composable
fun FollowScreen(state: UiState, onAction: (UiAction) -> Unit) {
  val filter = state.followLiveFilter
  val platformFilter = state.followPlatformFilter

  val platforms = remember(state.followed) { state.followed.map { it.platform }.distinct() }

  val visible = remember(state.followed, filter, platformFilter) {
    state.followed
      .filter { platformFilter == null || it.platform == platformFilter }
      .filter(filter::matches)
      // Live rooms first. The stored order is the user's manual arrangement, but burying whoever
      // is actually streaming under offline names defeated the point of the list. `sortedBy` is
      // stable, so manual order still breaks ties.
      .sortedBy { if (it.isLive) 0 else 1 }
  }

  val liveCount = remember(state.followed) { state.followed.count { it.isLive } }

  Box(modifier = Modifier.fillMaxSize()) {
    if (state.followed.isEmpty()) {
      EmptyState(
        icon = Icons.Outlined.StarOutline,
        title = "还没有关注的主播",
        description = "在首页点击卡片右下角的星标即可关注。关注列表会通过局域网同步到你的其他设备，" +
          "长按列表项可以拖动排序。",
      )
    } else {
      Column(modifier = Modifier.fillMaxSize()) {
        FollowHeader(
          total = state.followed.size,
          liveCount = liveCount,
          filter = filter,
          onFilterChange = { onAction(UiAction.SetFollowLiveFilter(it)) },
          platforms = platforms,
          platformFilter = platformFilter,
          onPlatformChange = { onAction(UiAction.SetFollowPlatformFilter(it)) },
        )

        if (visible.isEmpty()) {
          EmptyState(
            title = when (filter) {
              FollowLiveFilter.LIVE -> "关注的主播都没有在播"
              FollowLiveFilter.OFFLINE -> "所有关注的主播都在直播"
              FollowLiveFilter.ALL -> "没有符合条件的关注"
            },
            description = if (platformFilter != null) {
              "当前只显示 ${platformFilter.displayName} 的主播"
            } else {
              null
            },
            actionLabel = "显示全部",
            onAction = {
              onAction(UiAction.SetFollowLiveFilter(FollowLiveFilter.ALL))
              onAction(UiAction.SetFollowPlatformFilter(null))
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
                val keys = FollowOrdering.moveWithin(state.followed, visible, streamer, index)
                onAction(UiAction.SetFollowOrder(keys))
              },
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
  filter: FollowLiveFilter,
  onFilterChange: (FollowLiveFilter) -> Unit,
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

    // The state/platform row only earns its space when it can actually narrow something.
    if (filter != FollowLiveFilter.ALL || platforms.size > 1) {
      LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(FollowLiveFilter.entries.toList()) { option ->
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
