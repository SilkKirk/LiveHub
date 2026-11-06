package com.livehub.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.components.EmptyState
import com.livehub.app.ui.components.ErrorState
import com.livehub.app.ui.components.InlineLoader
import com.livehub.app.ui.components.RefreshableBox
import com.livehub.app.ui.components.StreamerCard
import com.livehub.app.ui.components.StreamerGridSkeleton
import com.livehub.app.ui.components.longPressOnly
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.core.model.Category
import com.livehub.core.model.Platform

/** Below this count a single-page result gets an explicit end-of-list hint. */
private const val NO_MORE_HINT_THRESHOLD = 21

/**
 * The category tree, resolved once and shared by the inline row and the picker.
 *
 * @property groups the distinct group names the platform publishes ("网游" / "手游" / …), in
 *   the order the platform lists them.
 * @property activeGroup the group the selected category belongs to, null before a selection
 *   and when the aggregate entry is selected — it belongs to no group.
 * @property visible the categories of [activeGroup] — or every category when the platform
 *   publishes a single group, because then the grouping carries no information and hiding the
 *   rest behind a picker would be pure cost. The aggregate entry always leads it.
 * @property aggregateLabel what the group chip says while [activeGroup] is null, so the chip
 *   can tell "nothing chosen yet" from "the whole tree is showing".
 */
private data class CategoryLevels(
  val groups: List<String>,
  val activeGroup: String?,
  val visible: List<Category>,
  val aggregateLabel: String,
)

private fun categoryLevelsOf(state: UiState): CategoryLevels {
  // The aggregate entry belongs to no group, so it must be excluded from the group list and
  // kept in [visible] whatever group is active — otherwise picking any category would make
  // the only way back to "先随便看看" disappear.
  val aggregates = state.categories.filter { it.isAggregate }
  val grouped = state.categories.filterNot { it.isAggregate }
  val groups = grouped.mapNotNull { it.parentName }.distinct()
  val active = state.selectedCategory?.parentName
  val visible = aggregates + if (groups.size > 1 && active != null) {
    grouped.filter { it.parentName == active }
  } else {
    grouped
  }
  return CategoryLevels(
    groups = groups,
    activeGroup = active,
    visible = visible,
    // 全部分类 is Douyu's own name for the scope `/directory/all` browses (`$DATA` on that
    // page carries `cate1Name: "全部分类"`), so the chip says where the row is rather than
    // inventing a third wording.
    aggregateLabel = if (state.selectedCategory?.isAggregate == true) "全部分类" else "选择分类",
  )
}

/** Home screen: platform switcher, one category row and the room grid. */
@Composable
fun HomeScreen(state: UiState, onAction: (UiAction) -> Unit) {
  val gridState = rememberLazyGridState()
  val followedKeys = remember(state.followed) { state.followed.map { it.key }.toSet() }
  // `refreshing` is view-model driven: a pull sets `loading` for the reset fetch, and the
  // indicator stays up until that request finishes.
  val refreshing = state.refreshing
  // Only ask for another page when the platform actually reports one. Douyin's feed and
  // Douyu's directory both serve a single page, and without this guard the grid kept
  // requesting "more" on every scroll to the bottom.
  val shouldLoadMore by remember {
    derivedStateOf {
      val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      state.hasMore &&
        last >= state.rooms.size - 4 &&
        state.rooms.isNotEmpty()
    }
  }
  val levels = remember(state.categories, state.selectedCategory) { categoryLevelsOf(state) }
  // Keyed on the platform: the panel lists the tree of the platform that was showing, and a
  // switch must not leave it open over a tree that no longer exists.
  var pickerOpen by remember(state.platform) { mutableStateOf(false) }

  LaunchedEffect(shouldLoadMore, state.rooms.size) {
    if (shouldLoadMore) onAction(UiAction.LoadMore)
  }

  // Back closes the panel rather than the app. `LiveHubRoot` only claims back for other
  // destinations, so without this the panel would be a trap on the one screen that has none.
  BackHandler(enabled = pickerOpen) { pickerOpen = false }

  Box(modifier = Modifier.fillMaxSize()) {
    Column(modifier = Modifier.fillMaxSize()) {
      PlatformTabs(state = state, onAction = onAction)
      FavoritesRow(
        state = state,
        onOpenPicker = { pickerOpen = true },
        onAction = onAction,
      )
      CategoryRow(
        state = state,
        levels = levels,
        onOpenPicker = { pickerOpen = true },
        onAction = onAction,
      )

      RefreshableBox(
        refreshing = refreshing,
        enabled = !state.loading,
        onRefresh = { onAction(UiAction.Refresh) },
        modifier = Modifier.fillMaxSize(),
      ) {
        if (state.rooms.isEmpty() && state.loading) {
          StreamerGridSkeleton()
        } else if (state.rooms.isEmpty() && state.error != null) {
          ErrorState(message = state.error, onRetry = { onAction(UiAction.Refresh) })
        } else if (state.rooms.isEmpty()) {
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
        } else {
          LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            state = gridState,
            contentPadding = PaddingValues(12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
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
              // Only platforms that genuinely serve a short, single-page list land here —
              // Bilibili's hot tab (110 rooms, but no second page) and Douyin's feed (six).
              // Douyu and Huya now page properly, so a short list from them means the
              // category really is quiet, which is still worth saying: an endless empty
              // scroll looks like a failed load.
              item(span = { GridItemSpan(maxLineSpan) }) {
                Text(
                  text = "已经到底了 · ${state.platform.displayName}当前只提供这些直播",
                  style = MaterialTheme.typography.labelSmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  textAlign = TextAlign.Center,
                  modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                )
              }
            }
          }
        }
      }
    }

    if (pickerOpen) {
      CategoryPicker(
        state = state,
        levels = levels,
        onDismiss = { pickerOpen = false },
        onAction = onAction,
      )
    }
  }
}

/**
 * The platform switcher, one line tall.
 *
 * It used to be a wrapping `FlowRow` of `FilterChip`s, and four of those need about 296dp of
 * label plus padding — more than the 256dp a 320dp phone leaves beside the refresh button. So
 * 哔哩哔哩 always wrapped onto a line of its own, under a row of three and level with nothing,
 * and the bar cost 96dp of a 640dp screen on *every* home render. Text tabs need about 232dp
 * for the same four labels and stay on one line, and a platform is a top-level destination
 * rather than a filter, which is the shape a tab already means.
 */
@Composable
private fun PlatformTabs(state: UiState, onAction: (UiAction) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    LazyRow(
      horizontalArrangement = Arrangement.spacedBy(2.dp),
      modifier = Modifier.weight(1f),
    ) {
      items(Platform.entries, key = { it.name }) { platform ->
        PlatformTab(
          label = platform.displayName,
          selected = state.platform == platform,
          onClick = { onAction(UiAction.SelectPlatform(platform)) },
        )
      }
    }
    IconButton(onClick = { onAction(UiAction.Refresh) }) {
      Icon(Icons.Filled.Refresh, contentDescription = "刷新")
    }
  }
}

@Composable
private fun PlatformTab(label: String, selected: Boolean, onClick: () -> Unit) {
  val color =
    if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
  Column(
    modifier = Modifier
      .clip(RoundedCornerShape(10.dp))
      .clickable(onClick = onClick)
      .padding(horizontal = 10.dp, vertical = 4.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(2.dp),
  ) {
    Text(
      text = label,
      style = MaterialTheme.typography.labelLarge,
      fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
      color = color,
      maxLines = 1,
    )
    // The marker is a fixed-width underline rather than a filled pill: a tab whose background
    // grows with the label would shift every neighbour as the selection moves.
    Box(
      modifier = Modifier
        .width(16.dp)
        .height(2.dp)
        .clip(RoundedCornerShape(1.dp))
        .background(if (selected) color else Color.Transparent),
    )
  }
}

/**
 * The pinned partitions of the **current platform**, directly under the platform tabs.
 *
 * Sitting between the tab row and the category row is a claim about scope: everything under a
 * platform tab belongs to that platform, so a row that mixed all four would be the one thing in
 * the column that the tab does not govern — and tapping a Bilibili chip two centimetres under the
 * 斗鱼 tab would silently switch platform. So the row is filtered to [UiState.platform].
 *
 * It disappears entirely when the platform has no favourites. That is the whole reason the
 * previous attempt at this feature was removed: a permanent row that only repeated "select a
 * category" cost 40dp on every home render. An empty row costs nothing.
 *
 * The only thing shown in the empty case is a single muted chip when *another* platform has
 * favourites, so the feature is still findable without being a permanent fixture.
 */
@Composable
private fun FavoritesRow(
  state: UiState,
  onOpenPicker: () -> Unit,
  onAction: (UiAction) -> Unit,
) {
  // Aggregates are filtered here as well as in the store: the row's contract is "every chip is
  // one concrete partition", and a hand-edited record or an older payload must not be able to
  // put a chip in it that means "the whole platform".
  val here = remember(state.favorites, state.platform) {
    state.favorites.filter { it.platform == state.platform && !it.isAggregate && it.id != Category.ALL_ID }
  }
  val elsewhere = remember(state.favorites, state.platform) {
    state.favorites.filter { it.platform != state.platform && !it.isAggregate && it.id != Category.ALL_ID }
  }
  if (here.isEmpty() && elsewhere.isEmpty()) return

  LazyRow(
    contentPadding = PaddingValues(horizontal = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().padding(top = 2.dp),
  ) {
    items(here, key = { "fav-${it.key}" }) { category ->
      // Smaller and star-prefixed on purpose: this row sits directly above the category row and
      // the two must not read as one strip of chips.
      FilterChip(
        selected = state.selectedCategory?.key == category.key,
        onClick = { onAction(UiAction.SelectCategory(category)) },
        label = { Text("★ ${category.name}", maxLines = 1) },
        modifier = Modifier.height(32.dp),
      )
    }
    if (here.isEmpty()) {
      item(key = "fav-elsewhere") {
        AssistChip(
          onClick = {
            val first = elsewhere.firstOrNull() ?: return@AssistChip
            onAction(UiAction.SelectPlatform(first.platform))
          },
          label = {
            Text(
              text = "在 ${elsewhere.map { it.platform }.distinct().size} 个其他平台有收藏 ›",
              maxLines = 1,
            )
          },
          modifier = Modifier.height(32.dp),
        )
      }
    }
    item(key = "fav-add") {
      IconButton(onClick = onOpenPicker, modifier = Modifier.size(32.dp)) {
        Icon(Icons.Filled.Add, contentDescription = "收藏分区", modifier = Modifier.size(18.dp))
      }
    }
  }
}

/**
 * The category selector: one row.
 *
 * It used to be up to three stacked chip rows — group, category, third level — which at 320dp
 * cost 112dp of chips under a two-line platform row and left less than two rows of cards
 * visible. The reference client keeps exactly one chip row above its grid
 * (`screenshots/douyu.jpeg`) and puts the rest of the tree behind a "选择分类" sheet
 * (`DouyuHomeScreen.kt`), so the group is now a single chip that opens [CategoryPicker]
 * instead of a row of its own.
 *
 * The third level stays inline. It is a filter *inside* the selected category rather than a
 * sibling of it, and the reference keeps its `Cate3Chips` row inline for the same reason.
 * 全部 leads it so the level can be cleared without hunting for the parent chip — without it
 * the only exit was re-tapping the parent, which was not marked as selected.
 */
@Composable
private fun CategoryRow(
  state: UiState,
  levels: CategoryLevels,
  onOpenPicker: () -> Unit,
  onAction: (UiAction) -> Unit,
) {
  if (state.categories.isEmpty()) return

  Column(modifier = Modifier.fillMaxWidth()) {
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      // Only when there is something to hide: a platform with a single group shows every
      // category inline, so a picker would be a second way to do what the row already does.
      if (levels.groups.size > 1) {
        AssistChip(
          onClick = onOpenPicker,
          // The chip names the scope the row is filtered to. With the aggregate selected
          // there is no group, and the old `?: "选择分类"` fallback then claimed nothing had
          // been chosen while a chip right beside it was highlighted — the first render of
          // `01c-home-all-live` said 选择分类 next to a selected 全部直播. 全部分类 is what
          // that state actually is: every group's categories, which is also what the row
          // shows once [activeGroup] is null.
          label = { Text(levels.activeGroup ?: levels.aggregateLabel, maxLines = 1) },
          trailingIcon = {
            Icon(
              imageVector = Icons.Filled.ArrowDropDown,
              contentDescription = null,
              modifier = Modifier.size(18.dp),
            )
          },
          modifier = Modifier.padding(start = 12.dp, end = 4.dp),
        )
      }
      LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        state = rememberLazyListState(),
        modifier = Modifier.weight(1f),
      ) {
        items(levels.visible, key = { it.key }) { category ->
          FilterChip(
            // The parent stays highlighted while a third level is open, so the row still says
            // which category the list is inside. It used to require
            // `selectedSubCategory == null`, which left both rows looking unselected and made
            // "go back to the whole category" look like a no-op.
            selected = state.selectedCategory?.key == category.key,
            onClick = { onAction(UiAction.SelectCategory(category)) },
            label = { Text(category.name, maxLines = 1) },
            // Long press pins/unpins instead of adding a second tap target: a star on every chip
            // would eat the label width at 320dp. The aggregate chip is reachable here too, and
            // it has no favourite to toggle — the ViewModel explains that rather than ignoring
            // the gesture.
            modifier = Modifier.longPressOnly { onAction(UiAction.ToggleFavorite(category)) },
          )
        }
      }
    }

    if (state.subCategories.isNotEmpty()) {
      LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        item(key = "sub-all") {
          FilterChip(
            selected = state.selectedSubCategory == null,
            onClick = { onAction(UiAction.SelectSubCategory(null)) },
            label = { Text("全部") },
          )
        }
        items(state.subCategories, key = { "sub-${it.key}" }) { category ->
          FilterChip(
            selected = state.selectedSubCategory?.key == category.key,
            onClick = { onAction(UiAction.SelectSubCategory(category)) },
            label = { Text(category.name, maxLines = 1) },
          )
        }
      }
    }
  }
}

/**
 * The rest of the category tree: groups on top, the chosen group's categories below.
 *
 * A sheet is what the reference client does for the same job, but this is drawn in the
 * activity window rather than as a `ModalBottomSheet`, for two reasons. A dialog-hosted sheet
 * is a separate window, and the screenshot harness captures `onRoot()` of the activity — a
 * render is the only visual proof this page has, so the panel has to be in it. And the panel
 * needs no animation, so a frozen-clock capture shows it fully open instead of mid-slide.
 *
 * Switching the group here is a browse step, not a selection: it only changes the list below.
 * Picking a category is what changes the page, and it closes the panel.
 */
@Composable
private fun CategoryPicker(
  state: UiState,
  levels: CategoryLevels,
  onDismiss: () -> Unit,
  onAction: (UiAction) -> Unit,
) {
  var group by remember { mutableStateOf(levels.activeGroup) }
  // The aggregate entry is already the row's first chip, so listing it here as well would be
  // a second control for the same job — and it belongs to no group, so it would sit at the
  // top of every group's list.
  val pickable = remember(state.categories) { state.categories.filterNot { it.isAggregate } }
  val listed = remember(pickable, group) {
    if (group == null) pickable else pickable.filter { it.parentName == group }
  }

  Box(modifier = Modifier.fillMaxSize()) {
    // Scrim: tapping outside closes the panel, as the reference sheet's scrim does.
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
        Text(
          text = "选择分类",
          style = MaterialTheme.typography.titleMedium,
          fontWeight = FontWeight.SemiBold,
          modifier = Modifier.padding(horizontal = 16.dp),
        )
        if (levels.groups.size > 1) {
          LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
          ) {
            items(levels.groups, key = { it }) { name ->
              FilterChip(
                selected = name == group,
                onClick = { group = name },
                label = { Text(name) },
              )
            }
          }
        }
        LazyColumn(
          contentPadding = PaddingValues(vertical = 8.dp),
          modifier = Modifier.fillMaxWidth().weight(1f),
        ) {
          items(listed, key = { it.key }) { category ->
            CategoryPickerRow(
              name = category.name,
              selected = category.key == state.selectedCategory?.key,
              // The aggregate entry is not listed here at all (`pickable` drops it), so this star
              // is never offered for something that cannot be pinned.
              favorite = state.favorites.any {
                it.platform == category.platform && it.id == category.id
              },
              onToggleFavorite = { onAction(UiAction.ToggleFavorite(category)) },
              onClick = {
                onAction(UiAction.SelectCategory(category))
                onDismiss()
              },
            )
          }
        }
      }
    }
  }
}

@Composable
private fun CategoryPickerRow(
  name: String,
  selected: Boolean,
  favorite: Boolean,
  onToggleFavorite: () -> Unit,
  onClick: () -> Unit,
) {
  Row(
    modifier = Modifier
      .fillMaxWidth()
      .clickable(onClick = onClick)
      .padding(start = 16.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(
      text = name,
      style = MaterialTheme.typography.bodyLarge,
      fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
      color =
        if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
      modifier = Modifier.weight(1f).padding(vertical = 8.dp),
    )
    if (selected) {
      Icon(
        imageVector = Icons.Filled.Check,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(18.dp),
      )
    }
    // The one place that can show the whole tree, so it is the main way to pin a partition.
    IconButton(onClick = onToggleFavorite, modifier = Modifier.size(40.dp)) {
      Icon(
        imageVector = if (favorite) Icons.Filled.Star else Icons.Filled.StarBorder,
        contentDescription = if (favorite) "取消收藏 $name" else "收藏 $name",
        tint = if (favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(20.dp),
      )
    }
  }
}
