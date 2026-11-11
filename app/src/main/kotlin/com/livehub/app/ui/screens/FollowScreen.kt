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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.FilterChip
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.livehub.app.di.FollowOrdering
import com.livehub.app.ui.FollowLiveFilter
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.app.ui.components.CardMinWidth
import com.livehub.app.ui.components.EmptyState
import com.livehub.app.ui.components.RefreshableBox
import com.livehub.app.ui.components.SpecialFollowRow
import com.livehub.app.ui.components.StreamerCard
import com.livehub.core.designsystem.LiveBadgeColor
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer

/**
 * Follow list: the 特别关注 avatar row on top, the rest of the follows as a two-column grid.
 *
 * The layout is the reference client's (`screenshots/home.jpeg`, which is a *follow list*, not a
 * home page): circular avatars across the top, then covers two per row. The old single-column row
 * list carried a cover at 56dp — big enough for a name, useless as a preview of what is on — and
 * the reference spends that width on the picture instead.
 *
 * **Two ordering axes, and they are different on purpose.** The avatar row is the only thing the
 * user drags, because a drag inside a grid has to resolve a two-dimensional offset into a cell and
 * that fights the scroll. The grid below is strictly "live first, then the stored order": it is a
 * browse surface, so what is on right now outranks the arrangement.
 *
 * Both filters live in `UiState` rather than in a `remember`, because they are selections the user
 * expects to survive opening a room and coming back.
 */
@Composable
fun FollowScreen(state: UiState, onAction: (UiAction) -> Unit) {
  val filter = state.followLiveFilter
  val platformFilter = state.followPlatformFilter
  // Which panel is open is the only local state left: everything the screen *selects* lives in
  // `UiState` so it survives a round trip through the player.
  var pickerOpen by remember { mutableStateOf(false) }

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

  val specialKeys = state.specialFollowKeys
  /**
   * The starred rooms, in the avatar row's own order.
   *
   * Deliberately **not** filtered by the live/platform row below it: the avatar row is a shortcut
   * to the people you always watch, and hiding half of them because the grid is filtered to 直播中
   * would make the row's contents depend on a control that sits underneath it. It is intersected
   * with [state.followed] only, which is what keeps an unfollowed room out.
   */
  val special = remember(state.followed, specialKeys) {
    val byKey = state.followed.associateBy { it.key }
    specialKeys.mapNotNull { byKey[it] }
  }

  val liveCount = remember(state.followed) { state.followed.count { it.isLive } }

  Box(modifier = Modifier.fillMaxSize()) {
    if (state.followed.isEmpty()) {
      EmptyState(
        icon = Icons.Outlined.StarOutline,
        title = "还没有关注的主播",
        description = "在首页点击卡片上的星标即可关注。关注列表会通过局域网同步到你的其他设备，" +
          "关注之后可以在页顶点亮星标，把常看的主播放进特别关注。",
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
          special = special,
          onOpenSpecial = { streamer -> onAction(UiAction.OpenRoom(streamer)) },
          onReorderSpecial = { streamer, index ->
            // `full` is the whole follow list, not `special`: the stored order is one order over
            // every followed room, and the avatar row is only a filtered view of it. Reconciling
            // against `special` would hand `SetFollowOrder` a partial list and drop everyone the
            // row was filtering out.
            onAction(
              UiAction.SetFollowOrder(
                FollowOrdering.moveWithin(state.followed, special, streamer, index),
              ),
            )
          },
          onAddSpecial = { pickerOpen = true },
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
            FollowGrid(
              streamers = visible,
              specialKeys = specialKeys.toSet(),
              onClick = { streamer -> onAction(UiAction.OpenRoom(streamer)) },
              onToggleSpecial = { streamer -> onAction(UiAction.ToggleSpecialFollow(streamer)) },
            )
          }
        }
      }
    }

    if (pickerOpen) {
      SpecialPickerSheet(
        followed = state.followed,
        specialKeys = specialKeys,
        onToggle = { streamer -> onAction(UiAction.ToggleSpecialFollow(streamer)) },
        onDismiss = { pickerOpen = false },
      )
    }
  }
}

/**
 * Who is on the 特别关注 row: every followed room, with the starred ones checked.
 *
 * A panel rather than "tap the star on the card": the grid can be hundreds of rooms long and
 * sorted live-first, so finding the one you want to pin means scrolling until it happens to be on
 * screen. Here the whole list is one tap away, and the ＋ in the avatar row finally does something.
 *
 * Drawn inside the activity window rather than as a `ModalBottomSheet`, for the same reason the
 * other panels are: the screenshot harness captures `onRoot()`, and a dialog is a separate window
 * it cannot see.
 */
@Composable
private fun SpecialPickerSheet(
  followed: List<Streamer>,
  specialKeys: List<String>,
  onToggle: (Streamer) -> Unit,
  onDismiss: () -> Unit,
) {
  BackHandler { onDismiss() }
  val chosen = specialKeys.toSet()

  Box(modifier = Modifier.fillMaxSize()) {
    Box(
      modifier = Modifier
        .fillMaxSize()
        .background(Color.Black.copy(alpha = 0.45f))
        .pointerInput(Unit) { detectTapGestures { onDismiss() } },
    )
    Surface(
      modifier = Modifier.fillMaxWidth().fillMaxHeight(0.6f).align(Alignment.BottomCenter),
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
            text = "选择特别关注",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
          )
          TextButton(onClick = onDismiss) { Text("完成") }
        }
        Text(
          text = "选中的主播会排在关注页顶部；长按头像可以调整它们的顺序。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
        )
        LazyColumn(
          contentPadding = PaddingValues(vertical = 8.dp),
          modifier = Modifier.fillMaxWidth().weight(1f, fill = false),
        ) {
          items(followed, key = { it.key }) { streamer ->
            val selected = streamer.key in chosen
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .clickable { onToggle(streamer) }
                .padding(horizontal = 16.dp, vertical = 12.dp),
              verticalAlignment = Alignment.CenterVertically,
            ) {
              // The live dot is the reason to look at this list before the grid: pinning somebody
              // who is offline right now is still a valid choice, but it should be a visible one.
              Box(
                modifier = Modifier
                  .size(6.dp)
                  .clip(CircleShape)
                  .background(
                    if (streamer.isLive) LiveBadgeColor else MaterialTheme.colorScheme.outlineVariant,
                  ),
              )
              Text(
                text = streamer.streamerName.ifBlank { "未知主播" },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                color = if (selected) {
                  MaterialTheme.colorScheme.primary
                } else {
                  MaterialTheme.colorScheme.onSurface
                },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(start = 10.dp),
              )
              Text(
                text = streamer.platform.displayName,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              if (selected) {
                Icon(
                  imageVector = Icons.Filled.Check,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(18.dp).padding(start = 6.dp),
                )
              }
            }
          }
        }
      }
    }
  }
}

/**
 * The grid of followed rooms, two per row.
 *
 * The star on the card means "特别关注" here rather than "关注": every room in this grid is already
 * followed, so the follow toggle would be a button that only ever removes. Unfollowing stays
 * available from the room itself and from the home grid.
 */
@Composable
private fun FollowGrid(
  streamers: List<Streamer>,
  specialKeys: Set<String>,
  onClick: (Streamer) -> Unit,
  onToggleSpecial: (Streamer) -> Unit,
) {
  val gridState = rememberLazyGridState()
  LazyVerticalGrid(
    // Same adaptive rule as the home grid; see CardMinWidth.
    columns = GridCells.Adaptive(minSize = CardMinWidth),
    state = gridState,
    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier.fillMaxSize(),
  ) {
    items(items = streamers, key = { it.key }, span = { GridItemSpan(1) }) { streamer ->
      StreamerCard(
        streamer = streamer,
        followed = streamer.key in specialKeys,
        onClick = { onClick(streamer) },
        onToggleFollow = { onToggleSpecial(streamer) },
        starLabels = "设为特别关注" to "取消特别关注",
      )
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
  special: List<Streamer>,
  onOpenSpecial: (Streamer) -> Unit,
  onReorderSpecial: (Streamer, Int) -> Unit,
  onAddSpecial: () -> Unit,
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

    // Always drawn, even while empty: the ＋ is how the feature is discovered, and a row that
    // appears only after the first pin would need a second explanation nobody reads.
    SpecialFollowRow(
      streamers = special,
      onClick = onOpenSpecial,
      onReorder = onReorderSpecial,
      onAdd = onAddSpecial,
    )

    // The state/platform row appears when it can narrow *something*.
    //
    // The old condition was `filter != ALL || platforms.size > 1`, which hid the row on the most
    // common shape of all — a single-platform follow list nobody has filtered yet — and took the
    // 直播中 / 未开播 chips with it, so the feature was undiscoverable until the user happened to
    // follow a second platform. The live filter narrows whenever there are both live and offline
    // rooms, which is exactly `liveCount in 1 until total`.
    val canNarrowByLive = liveCount in 1 until total
    if (filter != FollowLiveFilter.ALL || platformFilter != null || platforms.size > 1 || canNarrowByLive) {
      LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 2.dp),
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
