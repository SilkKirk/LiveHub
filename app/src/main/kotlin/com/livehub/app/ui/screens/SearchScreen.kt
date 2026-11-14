package com.livehub.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.app.ui.components.EmptyState
import com.livehub.app.ui.components.InlineLoader
import com.livehub.app.ui.components.RefreshableBox
import com.livehub.app.ui.components.StreamerListSkeleton
import com.livehub.app.ui.components.StreamerSearchCard
import com.livehub.core.model.Platform
import com.livehub.core.model.Streamer

/**
 * Search screen.
 *
 * Four distinct states, each rendered deliberately:
 *  * nothing searched yet  -> recent searches, recently opened rooms, plus a hint for what this
 *                             platform accepts
 *  * searching             -> a skeleton of the result rows, not a bare spinner
 *  * results / no results  -> the list, or an empty state that offers to clear the query
 *  * failure               -> the platform's message and a retry
 *
 * "Nothing searched yet" is its own state rather than "no results": the field changes on every
 * keystroke but a search only runs on submit, so a page that keyed the empty state on the field
 * text announced "没有找到「英」" one character into typing.
 */
@Composable
fun SearchScreen(state: UiState, onAction: (UiAction) -> Unit) {
  val followedKeys = remember(state.followed) { state.followed.map { it.key }.toSet() }
  val listState = rememberLazyListState()

  /**
   * The state as of *this* recomposition, for the derived value below.
   *
   * Same defect as the home grid: [UiState] is an immutable data class, so a `derivedStateOf`
   * closing over the `state` parameter recomputes only when `listState` changes and then reads
   * whichever instance it captured — and `remember` with no key captures the first one. On this
   * screen that first instance is always `searchResults = emptyList(), searchHasMore = false`,
   * because the page is entered before anything has been searched. 加载更多 therefore never
   * fired and a keyword search was stuck on its first page forever.
   */
  val current by rememberUpdatedState(state)
  val shouldLoadMore by remember {
    derivedStateOf {
      val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      current.searchHasMore &&
        last >= current.searchResults.size - 3 &&
        current.searchResults.isNotEmpty()
    }
  }

  LaunchedEffect(shouldLoadMore, state.searchResults.size) {
    if (shouldLoadMore) onAction(UiAction.LoadMoreSearch)
  }

  Column(modifier = Modifier.fillMaxSize()) {
    SearchField(state = state, onAction = onAction)

    val idle = state.searchQuery.isBlank() && state.lastSearchQuery.isBlank()
    // A search has actually run — as opposed to the field merely having text in it.
    val searched = state.lastSearchQuery.isNotBlank()

    if (idle && state.searchHistory.isNotEmpty()) {
      RecentSearches(
        keywords = state.searchHistory,
        onPick = { onAction(UiAction.UseHistory(it)) },
        onClear = { onAction(UiAction.ClearSearchHistory) },
      )
    }

    // Its own condition rather than part of the block above: browsing 首页 is the common way into
    // a room, so a user can have a play history without ever having searched for anything.
    if (idle && state.recentRooms.isNotEmpty()) {
      RecentRooms(
        rooms = state.recentRooms,
        onPick = { onAction(UiAction.OpenRoom(it)) },
      )
    }

    Box(modifier = Modifier.fillMaxSize()) {
      when {
        state.loading -> StreamerListSkeleton()

        // `error` is shared with the home screen, so it only means "search failed" once a
        // search has been submitted; otherwise a category that failed to load would show up
        // here as a search failure.
        state.error != null && searched -> EmptyState(
          icon = Icons.Outlined.SearchOff,
          title = "搜索失败",
          description = state.error,
          actionLabel = "重试",
          onAction = { onAction(UiAction.SubmitSearch) },
        )

        state.searchResults.isNotEmpty() -> RefreshableBox(
          refreshing = state.refreshing,
          onRefresh = { onAction(UiAction.SubmitSearch) },
          modifier = Modifier.fillMaxSize(),
        ) {
          LazyColumn(
            state = listState,
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            modifier = Modifier.fillMaxSize(),
          ) {
            if (state.searchAllPlatforms) {
              // Grouped, in `Platform` order, with a header per platform.
              //
              // A single merged list sorted by viewers would mix four sources whose numbers are not
              // comparable, and the cards carry no platform label on purpose (they used to repeat
              // the search field's own label) — so in this mode the platform has to come from
              // somewhere. It comes from the section.
              //
              // The failure line is drawn whether or not the platform produced rows: a platform can
              // answer page one and fail on page two, and "this section stopped growing" deserves
              // the same explanation as "this platform answered nothing".
              Platform.entries.forEach { platform ->
                val rooms = state.searchResults.filter { it.platform == platform }
                if (rooms.isNotEmpty()) {
                  item(key = "header-${platform.id}") {
                    PlatformHeader(platform = platform, count = rooms.size)
                  }
                  items(items = rooms, key = { it.key }) { streamer ->
                    SearchResultRow(
                      streamer = streamer,
                      followed = followedKeys.contains(streamer.key),
                      onAction = onAction,
                    )
                  }
                }
                state.searchFailures[platform]?.let { reason ->
                  item(key = "failure-${platform.id}") {
                    SearchFailureLine(platform = platform, reason = reason)
                  }
                }
              }
            } else {
              items(items = state.searchResults, key = { it.key }) { streamer ->
                SearchResultRow(
                  streamer = streamer,
                  followed = followedKeys.contains(streamer.key),
                  onAction = onAction,
                )
              }
            }
            if (state.loadingMore) {
              item { InlineLoader() }
            }
          }
        }

        searched -> EmptyState(
          icon = Icons.Outlined.SearchOff,
          title = "没有找到「${state.lastSearchQuery}」",
          // In the cross-platform mode the reasons are the answer: 抖音 without a login is not
          // "nothing matched", and the user can act on it.
          description = if (state.searchFailures.isEmpty()) {
            "换个关键词试试，或者切换到其他平台再搜索。"
          } else {
            state.searchFailures.entries.joinToString("\n") { (platform, reason) ->
              "${platform.displayName}：$reason"
            }
          },
          actionLabel = "清空",
          onAction = { onAction(UiAction.UpdateQuery("")) },
        )

        else -> EmptyState(
          icon = Icons.Filled.Search,
          title = "搜索主播或直播间",
          description = if (state.searchAllPlatforms) {
            "同时在斗鱼、虎牙、抖音与哔哩哔哩里搜索，结果按平台分组。"
          } else {
            SEARCH_HINTS[state.platform] ?: "输入关键词后按回车搜索。"
          },
        )
      }
    }
  }
}

/** One result row, shared by both modes so the two lists cannot drift apart. */
@Composable
private fun SearchResultRow(
  streamer: Streamer,
  followed: Boolean,
  onAction: (UiAction) -> Unit,
) {
  StreamerSearchCard(
    streamer = streamer,
    followed = followed,
    onClick = { onAction(UiAction.OpenRoom(streamer)) },
    onToggleFollow = { onAction(UiAction.ToggleFollow(streamer)) },
    // Search only ever returns rooms that are on air, so the LIVE label would repeat itself on
    // every row; the follow grid is where live-vs-offline is actually worth marking.
    showLiveBadge = false,
  )
}

/** The platform a group of results came from, with how many it contributed. */
@Composable
private fun PlatformHeader(platform: Platform, count: Int) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Text(
      text = platform.displayName,
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
    )
    Spacer(Modifier.width(6.dp))
    Text(
      text = "$count 个结果",
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

/** "抖音：需要登录" — a platform that could not answer, said out loud. */
@Composable
private fun SearchFailureLine(platform: Platform, reason: String) {
  Text(
    text = "${platform.displayName}：$reason",
    style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant,
    modifier = Modifier.fillMaxWidth(),
  )
}

/**
 * What the search field actually accepts, per platform.
 *
 * A single generic placeholder hid a real constraint: Douyin's search only resolves a room
 * id, so typing a streamer's name there returns nothing no matter how it is spelled.
 */
private val SEARCH_HINTS = mapOf(
  Platform.DOUYU to "输入关键词后按回车，可搜索斗鱼主播与直播间。",
  Platform.HUYA to "输入关键词后按回车，可搜索虎牙主播与直播间。",
  Platform.DOUYIN to "输入直播间号（数字或 AYM0707 这样的字母号）或直接粘贴直播间链接即可定位；" +
    "在「设置 → 抖音账号」登录后，可按主播昵称搜索。",
  Platform.BILIBILI to "输入关键词后按回车，可搜索 B 站主播与直播间。",
)

/**
 * What the field's own label promises, where the generic wording would mislead.
 *
 * The label used to read「搜索 抖音 主播 / 直播间」on a screen whose only working input was a
 * room number — so the first thing a user tried (a streamer's name) was also the one thing
 * that could not work without a login. Keyword search exists now, but only for a logged-in
 * session, so the label names both inputs.
 */
private val SEARCH_LABELS = mapOf(
  Platform.DOUYIN to "输入抖音直播间号、直播间链接或主播昵称",
)

@Composable
private fun SearchField(state: UiState, onAction: (UiAction) -> Unit) {
  Column(modifier = Modifier.fillMaxWidth()) {
    OutlinedTextField(
      value = state.searchQuery,
      onValueChange = { onAction(UiAction.UpdateQuery(it)) },
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
      label = {
        // In the cross-platform mode the label names no platform on purpose: the field is one
        // keyword for four searches, and "搜索 斗鱼 …" would describe only a quarter of it.
        Text(
          when {
            state.searchAllPlatforms -> "在斗鱼、虎牙、抖音、哔哩哔哩里搜索"
            else -> SEARCH_LABELS[state.platform] ?: "搜索 ${state.platform.displayName} 主播 / 直播间"
          },
        )
      },
      singleLine = true,
      leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
      // Both controls, always.
      //
      // This used to be an either/or: as soon as the field had text the submit button was replaced
      // by 清空, so after typing there was no tappable "search" anywhere on the screen — the only
      // remaining way was the keyboard's own search key, which several IMEs keep one panel deep.
      // A search field whose submit affordance disappears exactly when it becomes usable is the
      // wrong trade, even at the cost of ~48dp of text width.
      trailingIcon = {
        Row(verticalAlignment = Alignment.CenterVertically) {
          if (state.searchQuery.isNotEmpty()) {
            IconButton(onClick = { onAction(UiAction.UpdateQuery("")) }) {
              Icon(Icons.Filled.Clear, contentDescription = "清空")
            }
          }
          IconButton(
            onClick = { onAction(UiAction.SubmitSearch) },
            enabled = state.searchQuery.isNotBlank(),
          ) {
            Icon(Icons.AutoMirrored.Filled.KeyboardReturn, contentDescription = "搜索")
          }
        }
      },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { onAction(UiAction.SubmitSearch) }),
    )
    PlatformFilterRow(state = state, onAction = onAction)
  }
}

/**
 * The platform switcher, on the search screen itself.
 *
 * Without it the field's own label ("搜索 斗鱼 主播 / 直播间") named a platform the user could not
 * change without going back to 首页 — so comparing the same streamer across platforms, which is
 * the main reason to search at all in an aggregator, meant four taps and losing the keyword.
 *
 * It emits [UiAction.SelectSearchPlatform], **not** `SelectPlatform`: the latter is the home
 * screen's switcher and resets the browse state and navigates to `Screen.Home`, which would throw
 * the user out of search.
 */
@Composable
private fun PlatformFilterRow(state: UiState, onAction: (UiAction) -> Unit) {
  val listState = rememberLazyListState()
  val selectedIndex = if (state.searchAllPlatforms) 0 else 1 + Platform.entries.indexOf(state.platform)

  // Keep the selected chip on screen. Five Material chips need about 364dp and the phone this
  // app targets is 320dp, so the last one sits past the right edge; without this, a platform
  // restored from `lastPlatform` can be selected while nothing on screen says so — the same
  // "it exists, it is just off screen" shape round 76 fixed for the follow tags.
  //
  // Nothing is measured before the row's first layout, and `layoutInfo` is empty until then. The
  // first version treated that as "nothing visible" and scrolled unconditionally, which put 斗鱼
  // — the *default* selection, visible all along — at the left edge and cut 全部 in half on the
  // idle screen. Waiting for a measured layout is what makes this a repair rather than a nudge.
  //
  // `withFrameNanos` first, and that is not decoration: a `snapshotFlow` on `layoutInfo` resumes
  // inside the very layout pass that produced the snapshot, and `scrollToItem` from there asks the
  // list to measure while it is already measuring —
  //
  //   IllegalArgumentException: performMeasureAndLayout called during measure layout
  //
  // Which is a real crash, not a test artefact: it was found by the first large-font render, where
  // the fifth chip is finally far enough off screen for the scroll to be more than a no-op. So the
  // scroll waits for the next frame, whose clock callback runs before composition and measure.
  //
  // `scrollToItem`, not `animateScrollToItem`: an animation would leave a frozen-clock render
  // mid-flight and buys nothing for a one-off jump.
  LaunchedEffect(selectedIndex) {
    withFrameNanos { }
    val measured = listState.layoutInfo
    if (measured.visibleItemsInfo.isNotEmpty() &&
      measured.visibleItemsInfo.none { it.index == selectedIndex }
    ) {
      listState.scrollToItem(selectedIndex)
    }
  }

  LazyRow(
    state = listState,
    modifier = Modifier.fillMaxWidth(),
    contentPadding = PaddingValues(horizontal = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    // 全部 leads, because it is what the app is for: one keyword, four platforms, grouped results.
    // The per-platform chips stay for the cases where the aggregate is noise — a room number that
    // only means something on one platform, or a platform whose search needs a login.
    item(key = "all-platforms") {
      FilterChip(
        selected = state.searchAllPlatforms,
        onClick = { onAction(UiAction.SelectSearchPlatform(null)) },
        label = { Text("全部", maxLines = 1, softWrap = false) },
      )
    }
    items(Platform.entries.toList(), key = { it.id }) { platform ->
      FilterChip(
        selected = !state.searchAllPlatforms && platform == state.platform,
        onClick = { onAction(UiAction.SelectSearchPlatform(platform)) },
        // `shortName`: five chips have to share one 320dp line, and "哔哩哔哩" is what made this
        // row wrap 4 + 1 with the last chip alone on its own line. See `Platform.shortName`.
        label = { Text(platform.shortName, maxLines = 1, softWrap = false) },
      )
    }
  }
}


@Composable
private fun RecentSearches(
  keywords: List<String>,
  onPick: (String) -> Unit,
  onClear: () -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.padding(horizontal = 12.dp)) {
      Text(
        text = "最近搜索",
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
      )
    }
    LazyRow(
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      items(keywords) { keyword ->
        AssistChip(onClick = { onPick(keyword) }, label = { Text(keyword) })
      }
    }
    TextButton(onClick = onClear, modifier = Modifier.padding(start = 4.dp)) {
      Text("清空历史")
    }
  }
}

/**
 * The 最近看过 strip on the idle screen.
 *
 * A different question from 最近搜索 above it — that row is "what did I look for", this one is
 * "what was I watching" — so they are two lists rather than one merged one. The room cards are the
 * only way back into a room the user just closed without going to a platform at all, which is why
 * this row is worth the vertical space on a screen whose other content is a hint.
 */
@Composable
private fun RecentRooms(rooms: List<Streamer>, onPick: (Streamer) -> Unit) {
  Column(modifier = Modifier.fillMaxWidth()) {
    Text(
      text = "最近看过",
      style = MaterialTheme.typography.titleSmall,
      fontWeight = FontWeight.SemiBold,
      modifier = Modifier.padding(horizontal = 12.dp),
    )
    LazyRow(
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // Keyed by room rather than by position: opening a card from this row moves that room to the
      // front, and without a key every card behind it would re-bind its image instead of the list
      // simply moving one item.
      items(rooms, key = { it.key }) { streamer ->
        RecentRoomCard(streamer = streamer, onClick = { onPick(streamer) })
      }
    }
  }
}

/**
 * One visited room: a cover with a single name line under it.
 *
 * Smaller than a result row on purpose — this is a shortcut, not a browse surface, and up to
 * twelve of them share one horizontal strip. The whole card is the tap target, and at
 * [RECENT_ROOM_WIDTH_DP] wide it is already ~68dp of cover plus the name, so the 48dp minimum is
 * met on both axes without an invisible box around it.
 */
@Composable
private fun RecentRoomCard(streamer: Streamer, onClick: () -> Unit) {
  Column(
    modifier = Modifier
      .width(RECENT_ROOM_WIDTH_DP.dp)
      .clip(RoundedCornerShape(10.dp))
      .clickable(onClick = onClick)
      // Says *why* the card is here, which the visible name alone does not: on this screen a room
      // could equally well be a search result.
      .semantics { contentDescription = "最近看过 ${streamer.streamerName}" },
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 9f)
        .clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surfaceVariant),
      contentAlignment = Alignment.Center,
    ) {
      val cover = streamer.coverUrl
      if (cover.isNullOrBlank()) {
        // The same rule the room cards follow: a tile whose image is missing still gets a
        // deliberate glyph, because a strip of blank rectangles reads as a rendering bug.
        Icon(
          imageVector = Icons.Filled.PlayArrow,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.size(24.dp),
        )
      } else {
        AsyncImage(
          model = cover,
          // The name is the text directly below; describing the image too would say it twice.
          contentDescription = null,
          contentScale = ContentScale.Crop,
          modifier = Modifier.fillMaxSize(),
        )
      }
    }
    Text(
      text = streamer.streamerName.ifBlank { "未知主播" },
      style = MaterialTheme.typography.labelMedium,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.padding(top = 4.dp, start = 2.dp, end = 2.dp),
    )
  }
}

/**
 * Card width on the 最近看过 strip.
 *
 * At the 320dp phone width this app targets, two full cards plus a sliver of the third fit in the
 * window — the sliver is what tells the user the row scrolls, which a row of exactly-fitting cards
 * does not.
 */
private const val RECENT_ROOM_WIDTH_DP = 120
