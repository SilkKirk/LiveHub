package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Clear
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.app.ui.components.EmptyState
import com.livehub.app.ui.components.InlineLoader
import com.livehub.app.ui.components.RefreshableBox
import com.livehub.app.ui.components.StreamerListSkeleton
import com.livehub.app.ui.components.StreamerSearchCard
import com.livehub.core.model.Platform

/**
 * Search screen.
 *
 * Four distinct states, each rendered deliberately:
 *  * nothing searched yet  -> recent searches, plus a hint for what this platform accepts
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
            items(items = state.searchResults, key = { it.key }) { streamer ->
              StreamerSearchCard(
                streamer = streamer,
                followed = followedKeys.contains(streamer.key),
                onClick = { onAction(UiAction.OpenRoom(streamer)) },
                onToggleFollow = { onAction(UiAction.ToggleFollow(streamer)) },
              )
            }
            if (state.loadingMore) {
              item { InlineLoader() }
            }
          }
        }

        searched -> EmptyState(
          icon = Icons.Outlined.SearchOff,
          title = "没有找到「${state.lastSearchQuery}」",
          description = "换个关键词试试，或者切换到其他平台再搜索。",
          actionLabel = "清空",
          onAction = { onAction(UiAction.UpdateQuery("")) },
        )

        else -> EmptyState(
          icon = Icons.Filled.Search,
          title = "搜索主播或直播间",
          description = SEARCH_HINTS[state.platform] ?: "输入关键词后按回车搜索。",
        )
      }
    }
  }
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
    "在「设置 → 抖音账号」扫码登录后，可按主播昵称搜索。",
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
        Text(SEARCH_LABELS[state.platform] ?: "搜索 ${state.platform.displayName} 主播 / 直播间")
      },
      singleLine = true,
      leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
      trailingIcon = {
        if (state.searchQuery.isNotEmpty()) {
          IconButton(onClick = { onAction(UiAction.UpdateQuery("")) }) {
            Icon(Icons.Filled.Clear, contentDescription = "清空")
          }
        } else {
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
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PlatformFilterRow(state: UiState, onAction: (UiAction) -> Unit) {
  FlowRow(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    Platform.entries.forEach { platform ->
      FilterChip(
        selected = platform == state.platform,
        onClick = { onAction(UiAction.SelectSearchPlatform(platform)) },
        label = { Text(platform.displayName, maxLines = 1, softWrap = false) },
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
