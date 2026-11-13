package com.livehub.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.FilterChip
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

/**
 * How many follows before the name filter is worth a box on screen.
 *
 * Twelve is about six rows of two cards — two flicks on the smallest phone this app targets. Below
 * that, scrolling *is* the search. The same rule as the chip row below it: a control that cannot
 * help does not get drawn, and 56dp of text field above four cards is chrome nobody uses.
 */
private const val FOLLOW_SEARCH_THRESHOLD = 12

@Composable
fun FollowScreen(state: UiState, onAction: (UiAction) -> Unit) {
  val filter = state.followLiveFilter
  val platformFilter = state.followPlatformFilter
  val query = state.followQuery.trim()
  val tagFilter = state.followTagFilter
  // Which panel is open is the only local state left: everything the screen *selects* lives in
  // `UiState` so it survives a round trip through the player.
  var pickerOpen by remember { mutableStateOf(false) }
  var taggingRoom by remember { mutableStateOf<Streamer?>(null) }

  val platforms = remember(state.followed) { state.followed.map { it.platform }.distinct() }
  val tags = state.followTags
  val knownTags = remember(tags) { tags.values.flatten().distinct().sorted() }

  val visible = remember(state.followed, filter, platformFilter, query, tagFilter, tags) {
    state.followed
      .filter { platformFilter == null || it.platform == platformFilter }
      .filter(filter::matches)
      .filter { query.isEmpty() || it.matchesName(query) }
      .filter { tagFilter == null || tags[it.key]?.contains(tagFilter) == true }
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
          query = state.followQuery,
          onQueryChange = { onAction(UiAction.SetFollowQuery(it)) },
          tags = knownTags,
          tagFilter = tagFilter,
          onTagFilterChange = { onAction(UiAction.SetFollowTagFilter(it)) },
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
          // The name filter and the tag filter each get their own copy: "关注的主播都没有在播" would be
          // a lie when the real reason is that nothing matches what was typed or picked.
          EmptyState(
            title = when {
              query.isNotEmpty() -> "没有找到「$query」"
              tagFilter != null -> "没有带「$tagFilter」的主播"
              filter == FollowLiveFilter.LIVE -> "关注的主播都没有在播"
              filter == FollowLiveFilter.OFFLINE -> "所有关注的主播都在直播"
              else -> "没有符合条件的关注"
            },
            description = when {
              query.isNotEmpty() -> "关注列表里没有名字或标题包含这两个字的主播。"
              tagFilter != null -> "给直播间加标签可以在关注页按标签筛选。"
              platformFilter != null -> "当前只显示 ${platformFilter.displayName} 的主播"
              else -> null
            },
            actionLabel = if (query.isNotEmpty() || tagFilter != null) "清空筛选" else "显示全部",
            onAction = {
              if (query.isNotEmpty() || tagFilter != null) {
                onAction(UiAction.SetFollowQuery(""))
                onAction(UiAction.SetFollowTagFilter(null))
              } else {
                onAction(UiAction.SetFollowLiveFilter(FollowLiveFilter.ALL))
                onAction(UiAction.SetFollowPlatformFilter(null))
              }
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
              onLongPress = { streamer -> taggingRoom = streamer },
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

    // The tag editor: long-press a card to open it for that room.
    taggingRoom?.let { room ->
      TagEditorSheet(
        streamer = room,
        tags = tags[room.key].orEmpty(),
        known = knownTags,
        onTagsChange = { onAction(UiAction.SetRoomTags(room, it)) },
        onDismiss = { taggingRoom = null },
      )
    }
  }
}

/**
 * Long-press editor for one room's labels.
 *
 * Long-press rather than a button on the card: the card already carries a tap (open) and a star
 * (特别关注), and a third visible control would crowd a 140dp tile for something used once in a
 * while. The sheet shows every tag already in use, so the second room you tag is one tap instead of
 * typing the same word again.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagEditorSheet(
  streamer: Streamer,
  tags: List<String>,
  known: List<String>,
  onTagsChange: (List<String>) -> Unit,
  onDismiss: () -> Unit,
) {
  BackHandler { onDismiss() }
  var draft by remember { mutableStateOf("") }

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
      Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = streamer.streamerName.ifBlank { "未知主播" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
          )
          TextButton(onClick = onDismiss) { Text("完成") }
        }
        Text(
          text = "标签只用于关注页筛选，会随关注列表一起同步到已配对的设备。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 6.dp),
        )

        if (known.isEmpty() && tags.isEmpty()) {
          Text(
            text = "还没有任何标签，先加一个试试。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        LazyColumn(modifier = Modifier.weight(1f)) {
          item {
            FlowRow(
              horizontalArrangement = Arrangement.spacedBy(8.dp),
              verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              (known + tags).distinct().forEach { tag ->
                val selected = tag in tags
                FilterChip(
                  selected = selected,
                  onClick = {
                    onTagsChange(if (selected) tags - tag else tags + tag)
                  },
                  label = { Text(tag) },
                )
              }
            }
          }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
          OutlinedTextField(
            value = draft,
            onValueChange = { draft = it },
            label = { Text("新标签") },
            singleLine = true,
            modifier = Modifier.weight(1f),
          )
          TextButton(
            onClick = {
              val tag = draft.trim()
              if (tag.isNotEmpty() && tag !in tags) onTagsChange(tags + tag)
              draft = ""
            },
          ) { Text("添加") }
        }
      }
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
  onLongPress: (Streamer) -> Unit,
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
        onLongClick = { onLongPress(streamer) },
      )
    }
  }
}

/**
 * The name filter on the follow screen.
 *
 * A label rather than a placeholder: `OutlinedTextField` keeps the label visible once the field has
 * text, which is what tells the user — and this screen's tests — which box the typed text went into.
 * The clear button only exists while there is something to clear.
 */
@Composable
private fun FollowSearchField(query: String, onQueryChange: (String) -> Unit) {
  OutlinedTextField(
    value = query,
    onValueChange = onQueryChange,
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
    label = { Text("搜索关注的主播") },
    singleLine = true,
    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null, modifier = Modifier.size(18.dp)) },
    trailingIcon = if (query.isNotEmpty()) {
      {
        IconButton(onClick = { onQueryChange("") }) {
          Icon(Icons.Filled.Clear, contentDescription = "清空搜索")
        }
      }
    } else {
      null
    },
  )
}

/**
 * True when [query] appears in the streamer's name or in the title, ignoring case.
 *
 * Both fields, because a followed room is remembered by whichever the user recognises — the name on
 * a list of people, the title on a list of shows. Ignoring case because the names mix Latin and CJK
 * on all four platforms ("DNF", "dnf", "PDD").
 */
private fun Streamer.matchesName(query: String): Boolean =
  streamerName.contains(query, ignoreCase = true) || title.contains(query, ignoreCase = true)

@Composable
private fun FollowHeader(
  total: Int,
  liveCount: Int,
  filter: FollowLiveFilter,
  onFilterChange: (FollowLiveFilter) -> Unit,
  platforms: List<Platform>,
  platformFilter: Platform?,
  onPlatformChange: (Platform?) -> Unit,
  query: String,
  onQueryChange: (String) -> Unit,
  tags: List<String>,
  tagFilter: String?,
  onTagFilterChange: (String?) -> Unit,
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

    // The name filter, once the list is long enough for scrolling alone to be a problem.
    //
    // Same rule as the chip row below: a control that cannot help does not get drawn. Six follows
    // are one flick away — half a screen of search box above them is chrome nobody uses — while
    // the sort of list this app encourages needs a way to jump straight to a name.
    //
    // A non-empty query keeps the field whatever the list looks like: it has to stay there while
    // the user types, or it would disappear under their finger the moment the results got short.
    if (total >= FOLLOW_SEARCH_THRESHOLD || query.isNotEmpty()) {
      FollowSearchField(query = query, onQueryChange = onQueryChange)
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
    val canNarrowByTag = tags.isNotEmpty() || tagFilter != null
    if (filter != FollowLiveFilter.ALL || platformFilter != null || platforms.size > 1 ||
      canNarrowByLive || canNarrowByTag
    ) {
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
        // Tags ride in the same row and carry a '#', so a tag named after a platform ("斗鱼")
        // cannot be mistaken for the platform filter next to it.
        items(tags) { tag ->
          FilterChip(
            selected = tagFilter == tag,
            onClick = { onTagFilterChange(if (tagFilter == tag) null else tag) },
            label = { Text("#$tag") },
          )
        }
      }
    }
  }
}
