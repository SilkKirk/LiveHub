package com.livehub.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.Icons
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.components.CardMinWidth
import com.livehub.app.ui.components.EmptyState
import com.livehub.app.ui.components.ErrorState
import com.livehub.app.ui.components.InlineLoader
import com.livehub.app.ui.components.RefreshableBox
import com.livehub.app.ui.components.StreamerCard
import com.livehub.app.ui.components.StreamerGridSkeleton
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.model.Category
import com.livehub.core.model.Platform

/** Below this count a single-page result gets an explicit end-of-list hint. */
private const val NO_MORE_HINT_THRESHOLD = 21

/**
 * The category tree, resolved once and shared by the two inline rows.
 *
 * @property groups the distinct group names the platform publishes ("网游" / "手游" / …), in
 *   the order the platform lists them.
 * @property activeGroup the group whose partitions [visible] holds, or null when every group's
 *   partitions are listed (the group row's 全部 chip, or a platform with a single group).
 * @property visible the aggregate entry plus the partitions in scope.
 * @property browsingAll true when the group row's 全部 chip is the active one, so the row can
 *   mark it selected. [activeGroup] being null is not enough: a single-group platform also has
 *   no active group, and there the 全部 chip is not even drawn.
 */
private data class CategoryLevels(
  val groups: List<String>,
  val activeGroup: String?,
  val visible: List<Category>,
  val browsingAll: Boolean,
)

private fun categoryLevelsOf(state: UiState, requestedGroup: String?): CategoryLevels {
  // The aggregate entry belongs to no group, so it must be excluded from the group list and kept
  // in [visible] whatever group is active — otherwise picking any partition would make the only
  // way back to "先随便看看" disappear.
  val aggregates = state.categories.filter { it.isAggregate }
  val grouped = state.categories.filterNot { it.isAggregate }
  val groups = grouped.mapNotNull { it.parentName }.distinct()

  // Which group to show, in order of preference:
  //  1. the one the user last picked in the group row — including the explicit 全部;
  //  2. the one the *selected* partition belongs to — otherwise a partition reached from a
  //     favourite chip or a restored session would be highlighted while the row above it showed
  //     a different group's partitions, and the selection would look lost;
  //  3. the first group, which is what a cold start on the aggregate entry lands on.
  // A remembered group that no longer exists (platform switch, changed catalogue) falls through
  // to the same chain rather than to "no filter".
  val active = when {
    requestedGroup == ALL_GROUPS -> null
    requestedGroup != null && requestedGroup in groups -> requestedGroup
    state.selectedCategory?.parentName in groups -> state.selectedCategory?.parentName
    else -> groups.firstOrNull()
  }
  val visible = aggregates + if (active == null) grouped else grouped.filter { it.parentName == active }
  return CategoryLevels(
    groups = groups,
    activeGroup = active,
    visible = visible,
    browsingAll = requestedGroup == ALL_GROUPS,
  )
}

/**
 * Sentinel for the group row's 全部 chip: "every group's partitions".
 *
 * A sentinel rather than `null` because `null` already means "nothing picked yet" for the state
 * variable, and the two have to be distinguishable — the first falls through to the selected
 * partition's group, the second is an explicit choice that overrides it.
 */
private const val ALL_GROUPS = "\u0000all-groups"

/** Home screen: platform switcher, the category tree and the room grid. */
@Composable
fun HomeScreen(state: UiState, onAction: (UiAction) -> Unit) {
  // The pager is the source of truth for "which platform is on screen"; the ViewModel's
  // `state.platform` follows it. Keeping the two in sync in this direction means a swipe updates
  // the tabs and a tab tap animates the pager, without either fighting the other.
  val pagerState = rememberPagerState(
    initialPage = Platform.entries.indexOf(state.platform).coerceAtLeast(0),
  ) { Platform.entries.size }

  // A tab tap changes `state.platform`; the pager has to follow it. Guarded on the settled page so
  // this does not re-trigger while a swipe is already animating to that same page.
  LaunchedEffect(state.platform) {
    val target = Platform.entries.indexOf(state.platform)
    if (target >= 0 && target != pagerState.currentPage) pagerState.animateScrollToPage(target)
  }

  // A swipe settles on a page; tell the ViewModel. `settledPage` rather than `currentPage` so the
  // request for a platform's rooms only fires once, when the gesture ends — not on every pixel.
  LaunchedEffect(pagerState.settledPage) {
    val platform = Platform.entries.getOrNull(pagerState.settledPage) ?: return@LaunchedEffect
    if (platform != state.platform) onAction(UiAction.SelectPlatform(platform))
  }

  Column(modifier = Modifier.fillMaxSize()) {
    PlatformTabs(
      state = state,
      pagerState = pagerState,
      onAction = onAction,
    )
    HorizontalPager(
      state = pagerState,
      modifier = Modifier.fillMaxSize(),
      // Beyond the neighbouring page there is nothing to pre-compose: each platform's grid is a
      // network-backed list, and prefetching two of them on every swipe is pure cost.
      beyondViewportPageCount = 0,
      key = { index -> Platform.entries[index].name },
    ) { page ->
      // Only the settled page renders real content. A page mid-swipe shows its skeleton, because
      // its rooms belong to the platform that is about to become current and the ViewModel has
      // not fetched them yet.
      val isCurrent = Platform.entries[page] == state.platform
      PlatformPage(
        state = state,
        isCurrent = isCurrent,
        onAction = onAction,
      )
    }
  }
}

/**
 * One platform's page: the category tree and the grid.
 *
 * Extracted from the old single-platform body so the pager can compose two of them at once during
 * a swipe. Everything it reads that is *per platform* — the tree, the group being browsed, the
 * grid — is either passed in through [state] (which the ViewModel swaps when the platform changes)
 * or remembered locally, keyed on the platform so a swipe back restores it.
 */
@Composable
private fun PlatformPage(
  state: UiState,
  isCurrent: Boolean,
  onAction: (UiAction) -> Unit,
) {
  val gridState = rememberLazyGridState()
  val followedKeys = remember(state.followed) { state.followed.map { it.key }.toSet() }
  val refreshing = state.refreshing
  /**
   * The state as of *this* recomposition, for the derived value below.
   *
   * [UiState] is an immutable data class, not a snapshot state object: a `derivedStateOf` that
   * closed over the `state` parameter would only recompute when `gridState` changed, and would
   * then read whichever instance it captured. `remember` with no key captures the **first** one,
   * which on a cold start is `rooms = emptyList(), hasNextPage = false` — so `hasMore` stayed
   * false for the lifetime of the page and 加载更多 was never dispatched. It appeared to work
   * after visiting another tab only because that destroys and re-creates this composable, so the
   * `remember` captured a state that already had rooms in it.
   */
  val current by rememberUpdatedState(state)
  // Only ask for another page when the platform actually reports one. Douyin's feed and Douyu's
  // directory both serve a single page, and without this guard the grid kept requesting "more" on
  // every scroll to the bottom.
  val shouldLoadMore by remember {
    derivedStateOf {
      val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      current.hasMore &&
        last >= current.rooms.size - 4 &&
        current.rooms.isNotEmpty()
    }
  }
  // Which group the partition row is showing. Kept per platform so a switch back restores the tree
  // the user was in rather than dropping them on 网游 again.
  var browseGroup by remember(state.platform) { mutableStateOf<String?>(null) }
  // Collapsed shows one scrolling chip row; expanded wraps every group into a FlowRow.
  var expanded by remember(state.platform) { mutableStateOf(false) }
  val levels = remember(state.categories, browseGroup) { categoryLevelsOf(state, browseGroup) }

  LaunchedEffect(shouldLoadMore, state.rooms.size, isCurrent) {
    if (shouldLoadMore && isCurrent) onAction(UiAction.LoadMore)
  }

  // Back collapses the tree before it leaves the page: an expanded row of 400 partitions would
  // otherwise be a dead end on the one screen that has no back destination.
  BackHandler(enabled = expanded) { expanded = false }

  // Picking a partition or a third level is the end of the browse: collapse the tree so the result
  // is visible. Without this the page stayed expanded over the grid it just loaded, and the user
  // had to scroll past a screenful of chips to see the streams they had asked for.
  //
  // Keyed on the selection, not on the tap: a selection restored from a saved session must not
  // collapse anything, and re-tapping the partition that is already selected must not either.
  LaunchedEffect(state.selectedCategory?.key, state.selectedSubCategory?.key) {
    expanded = false
  }

  Column(modifier = Modifier.fillMaxSize()) {
    CategoryTree(
      state = state,
      levels = levels,
      expanded = expanded,
      onToggleExpanded = { expanded = !expanded },
      onSelectGroup = { browseGroup = it },
      onAction = onAction,
    )

    RefreshableBox(
      refreshing = refreshing,
      enabled = !state.loading && isCurrent,
      onRefresh = { onAction(UiAction.Refresh) },
      modifier = Modifier.fillMaxSize(),
    ) {
      when {
        state.rooms.isEmpty() && state.loading -> StreamerGridSkeleton()
        state.rooms.isEmpty() && state.error != null ->
          ErrorState(message = state.error, onRetry = { onAction(UiAction.Refresh) })
        state.rooms.isEmpty() -> {
          // The aggregate entry is not a partition, so the copy cannot call it one.
          val aggregate = state.selectedCategory?.isAggregate == true
          EmptyState(
            title = if (aggregate) "现在没有人在直播" else "这个分区暂时没有直播",
            description = if (aggregate) {
              "换一个分区看看？"
            } else {
              "${state.selectedCategory?.name ?: ""} 还没有人在播，换个分区看看？"
            },
            actionLabel = "刷新",
            onAction = { onAction(UiAction.Refresh) },
          )
        }
        else -> RoomGrid(
          state = state,
          gridState = gridState,
          followedKeys = followedKeys,
          onAction = onAction,
        )
      }
    }
  }
}

/** The waterfall of room cards, with its end-of-list hint. */
@Composable
private fun RoomGrid(
  state: UiState,
  gridState: LazyGridState,
  followedKeys: Set<String>,
  onAction: (UiAction) -> Unit,
) {
  LazyVerticalGrid(
    // Adaptive rather than a fixed two columns: at 320dp this still resolves to two, but a tablet
    // or a landscape phone gets four or five instead of two enormous tiles. `CardMinWidth` is
    // shared with the skeleton and the follow grid so a load never changes the column count
    // mid-flight.
    columns = GridCells.Adaptive(minSize = CardMinWidth),
    state = gridState,
    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier.fillMaxSize(),
  ) {
    items(items = state.rooms, key = { it.key }) { streamer ->
      StreamerCard(
        streamer = streamer,
        followed = followedKeys.contains(streamer.key),
        onClick = { onAction(UiAction.OpenRoom(streamer)) },
        onToggleFollow = { onAction(UiAction.ToggleFollow(streamer)) },
      )
    }
    if (state.loadingMore) {
      item(span = { GridItemSpan(maxLineSpan) }) {
        InlineLoader()
      }
    } else if (!state.hasNextPage && state.rooms.size < NO_MORE_HINT_THRESHOLD) {
      // Only platforms that genuinely serve a short, single-page list land here — Bilibili's hot
      // tab (110 rooms, but no second page) and Douyin's feed (six). Douyu and Huya page properly,
      // so a short list from them means the category really is quiet, which is still worth saying:
      // an endless empty scroll looks like a failed load.
      item(span = { GridItemSpan(maxLineSpan) }) {
        EndOfListHint(platform = state.platform)
      }
    }
  }
}

/**
 * The platform switcher.
 *
 * Uses the Material 3 `PrimaryTabRow` rather than a hand-rolled row of labels with a `Box`
 * underline: the tab row already animates its indicator between positions, sizes it to the label,
 * and exposes `Tab` semantics (so `assertIsSelected` and TalkBack both work without extra wiring).
 * The pager is driven from the same `selectedTabIndex`, which is what keeps a swipe and a tap
 * looking identical.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlatformTabs(
  state: UiState,
  pagerState: PagerState,
  onAction: (UiAction) -> Unit,
) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 4.dp, end = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    PrimaryTabRow(
      selectedTabIndex = pagerState.currentPage,
      containerColor = Color.Transparent,
      contentColor = MaterialTheme.colorScheme.primary,
      divider = {},
      indicator = {
        // The default indicator is a full-width bar under the tab. This one is narrower and sits
        // just under the label, which reads as an underline rather than as a second divider —
        // there is already a hairline between the tabs and the grid.
        Box(
          modifier = Modifier
            .tabIndicatorOffset(pagerState.currentPage, matchContentSize = false)
            .padding(horizontal = 14.dp)
            .height(3.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(MaterialTheme.colorScheme.primary),
        )
      },
      modifier = Modifier.weight(1f),
    ) {
      Platform.entries.forEachIndexed { index, platform ->
        Tab(
          selected = pagerState.currentPage == index,
          onClick = { onAction(UiAction.SelectPlatform(platform)) },
          selectedContentColor = MaterialTheme.colorScheme.primary,
          unselectedContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
          text = {
            Text(
              text = platform.displayName,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = if (pagerState.currentPage == index) FontWeight.Bold else FontWeight.Medium,
              maxLines = 1,
            )
          },
        )
      }
    }
    IconButton(onClick = { onAction(UiAction.Refresh) }) {
      Icon(Icons.Filled.Refresh, contentDescription = "刷新", modifier = Modifier.size(20.dp))
    }
  }
}

/** The muted "that is all of them" line at the end of a short grid. */
@Composable
private fun EndOfListHint(platform: Platform) {
  Column(
    modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(
      modifier = Modifier
        .width(28.dp)
        .height(2.dp)
        .clip(RoundedCornerShape(1.dp))
        .background(MaterialTheme.colorScheme.outlineVariant),
    )
    Text(
      text = "已经到底了 · ${platform.displayName}当前只提供这些直播",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 10.dp),
    )
  }
}

/**
 * The category tree: two inline rows, expanded or collapsed.
 *
 * Shaped after the Bilibili client's live tab (`screenshots/b.jpeg`): a row of groups with a
 * chevron at the right end, and — once a group is picked — a second row of that group's
 * partitions. Tapping the chevron wraps every group into a `FlowRow` instead of scrolling one
 * line, and the partition row follows it, which is what makes 400+ Douyu partitions reachable
 * without a separate sheet.
 *
 * This replaces a "选择分类" chip that opened a half-screen panel. The panel worked but cost two
 * things the reference does not pay: the tree was invisible until opened, and picking a
 * partition closed it, so comparing two partitions meant reopening it each time. Both rows are
 * cheap because they are only as tall as the group actually selected — the collapsed state is
 * one line, the expanded state is bounded by the number of *groups* (12 on Douyu), not by the
 * number of partitions.
 *
 * Selection vs browse is the distinction that makes it work: [CategoryLevels.activeGroup] is
 * where you are looking, [UiState.selectedCategory] is what the grid shows. Picking 网游 does
 * not change the grid; picking 英雄联盟 does, and 网游 stays highlighted underneath.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategoryTree(
  state: UiState,
  levels: CategoryLevels,
  expanded: Boolean,
  onToggleExpanded: () -> Unit,
  onSelectGroup: (String) -> Unit,
  onAction: (UiAction) -> Unit,
) {
  if (state.categories.isEmpty()) return
  val multiGroup = levels.groups.size > 1

  Column(modifier = Modifier.fillMaxWidth()) {
    if (expanded && multiGroup) {
      FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        // 全部 leads the group row, as it does in the reference client: "show every group's
        // partitions" is a real choice, and without it the widest scope is the biggest group.
        GroupChip(
          name = "全部",
          selected = levels.browsingAll,
          onClick = { onSelectGroup(ALL_GROUPS) },
        )
        levels.groups.forEach { name ->
          GroupChip(
            name = name,
            selected = name == levels.activeGroup,
            onClick = { onSelectGroup(name) },
          )
        }
        // The collapse control rides at the end of the wrapped flow, where the reference puts it:
        // one chevron, always in the last row, so it never disappears while the list grows.
        ChevronButton(expanded = true, onClick = onToggleExpanded)
      }
    } else {
      Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        LazyRow(
          contentPadding = PaddingValues(horizontal = 12.dp),
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          modifier = Modifier.weight(1f),
        ) {
          if (multiGroup) {
            item(key = "group-all") {
              GroupChip(
                name = "全部",
                selected = levels.browsingAll,
                onClick = { onSelectGroup(ALL_GROUPS) },
              )
            }
            items(levels.groups, key = { it }) { name ->
              GroupChip(
                name = name,
                selected = name == levels.activeGroup,
                onClick = { onSelectGroup(name) },
              )
            }
          }
        }
        // Drawn for a single-group platform too. The tree is still expandable there — it is how
        // the *partitions* get wrapped into a readable grid instead of a scroll — and gating this
        // on `multiGroup` left such a platform with no way back into the expanded state once the
        // tree collapsed.
        ChevronButton(expanded = false, onClick = onToggleExpanded)
      }
    }

    // The partitions of the active group, with the aggregate entry leading.
    //
    // Collapsed is **one scrolling line**; expanded wraps. This was the bug that shipped in
    // v0.15.0: the partition row was a `FlowRow` unconditionally, so on Douyu the collapsed state
    // still wrapped ~40 partitions into a screenful of chips and pushed the grid — the thing the
    // user actually opened the app for — entirely below the fold.
    if (expanded) {
      FlowRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        levels.visible.forEach { category ->
          PartitionChip(
            name = category.name,
            selected = state.selectedCategory?.key == category.key,
            onClick = { onAction(UiAction.SelectCategory(category)) },
          )
        }
      }
    } else {
      LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
      ) {
        items(levels.visible, key = { it.key }) { category ->
          PartitionChip(
            name = category.name,
            selected = state.selectedCategory?.key == category.key,
            onClick = { onAction(UiAction.SelectCategory(category)) },
          )
        }
      }
    }

    if (state.subCategories.isNotEmpty()) {
      // The third level is a filter *inside* the selected partition, so it stays one line whatever
      // the tree above it is doing: wrapping it too would double the height of an expanded page.
      LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
      ) {
        // 全部 leads the third level so it can be cleared without hunting for the parent chip —
        // without it the only exit was re-tapping the parent, which was not marked as selected.
        item(key = "sub-all") {
          PartitionChip(
            name = "全部",
            selected = state.selectedSubCategory == null,
            onClick = { onAction(UiAction.SelectSubCategory(null)) },
          )
        }
        items(state.subCategories, key = { "sub-${it.key}" }) { category ->
          PartitionChip(
            name = category.name,
            selected = state.selectedSubCategory?.key == category.key,
            onClick = { onAction(UiAction.SelectSubCategory(category)) },
          )
        }
      }
    }
  }
}

/** A group chip: 网游 / 手游 / …. Highlighted while that group's partitions are showing. */
@Composable
private fun GroupChip(name: String, selected: Boolean, onClick: () -> Unit) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(name, maxLines = 1) },
  )
}

/**
 * A partition chip, with the checkmark the reference puts in front of the selected one.
 *
 * The tick is not decoration: the aggregate chip (全部直播) and the first real partition can both
 * be highlighted-looking once a third level is open, and a filled background alone was ambiguous
 * at chip size. The reference marks the current partition with ✓ for the same reason.
 */
@Composable
private fun PartitionChip(
  name: String,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  FilterChip(
    selected = selected,
    onClick = onClick,
    label = { Text(name, maxLines = 1) },
    leadingIcon = if (selected) {
      {
        Icon(
          imageVector = Icons.Filled.Check,
          contentDescription = null,
          modifier = Modifier.size(16.dp),
        )
      }
    } else {
      null
    },
    modifier = modifier,
  )
}

/** The chevron that swaps between the one-line row and the wrapped tree. */
@Composable
private fun ChevronButton(expanded: Boolean, onClick: () -> Unit) {
  IconButton(onClick = onClick, modifier = Modifier.size(36.dp)) {
    Icon(
      imageVector = if (expanded) Icons.Filled.KeyboardArrowUp else Icons.Filled.KeyboardArrowDown,
      contentDescription = if (expanded) "收起分类" else "展开分类",
      tint = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.size(22.dp),
    )
  }
}
