package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardReturn
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.app.ui.components.EmptyState
import com.livehub.app.ui.components.InlineLoader
import com.livehub.app.ui.components.StreamerCard
import com.livehub.app.ui.components.StreamerGridSkeleton

/**
 * Search screen.
 *
 * Three distinct states, each rendered deliberately:
 *  * nothing searched yet  -> recent searches with a "clear" action
 *  * searching            -> skeleton grid, not a bare spinner
 *  * results / no results -> grid, or an empty state that offers to clear the query
 */
@Composable
fun SearchScreen(state: UiState, onAction: (UiAction) -> Unit) {
  val followedKeys = remember(state.followed) { state.followed.map { it.key }.toSet() }
  val gridState = androidx.compose.foundation.lazy.grid.rememberLazyGridState()

  val shouldLoadMore by androidx.compose.runtime.remember {
    androidx.compose.runtime.derivedStateOf {
      val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      state.searchHasMore && last >= state.searchResults.size - 4 && state.searchResults.isNotEmpty()
    }
  }

  androidx.compose.runtime.LaunchedEffect(shouldLoadMore, state.searchResults.size) {
    if (shouldLoadMore) onAction(UiAction.LoadMoreSearch)
  }

  Column(modifier = Modifier.fillMaxSize()) {
    SearchField(state = state, onAction = onAction)

    val hasQuery = state.searchQuery.isNotBlank()
    val hasResults = state.searchResults.isNotEmpty()
    val idle = !hasQuery && !hasResults

    if (idle && state.searchHistory.isNotEmpty()) {
      RecentSearches(
        keywords = state.searchHistory,
        onPick = { onAction(UiAction.UseHistory(it)) },
        onClear = { onAction(UiAction.ClearSearchHistory) },
      )
    }

    Box(modifier = Modifier.fillMaxSize()) {
      when {
        state.loading -> StreamerGridSkeleton()

        state.error != null && !hasResults -> EmptyState(
          icon = Icons.Outlined.SearchOff,
          title = "搜索失败",
          description = state.error,
          actionLabel = "重试",
          onAction = { onAction(UiAction.SubmitSearch) },
        )

        idle -> EmptyState(
          icon = Icons.Filled.Search,
          title = "搜索主播或直播间",
          description = "输入关键词后按回车，可以搜索 ${state.platform.displayName} 上的主播与直播间。",
        )

        !hasResults -> EmptyState(
          icon = Icons.Outlined.SearchOff,
          title = "没有找到「${state.searchQuery}」",
          description = "换个关键词试试，或者切换到其他平台再搜索。",
          actionLabel = "清空",
          onAction = { onAction(UiAction.UpdateQuery("")) },
        )

        else -> LazyVerticalGrid(
          columns = GridCells.Fixed(2),
          state = gridState,
          contentPadding = PaddingValues(12.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.fillMaxSize(),
        ) {
          items(items = state.searchResults, key = { it.key }) { streamer ->
            StreamerCard(
              streamer = streamer,
              followed = followedKeys.contains(streamer.key),
              onClick = { onAction(UiAction.OpenRoom(streamer)) },
              onToggleFollow = { onAction(UiAction.ToggleFollow(streamer)) },
            )
          }
          if (state.loadingMore) {
            item(span = { GridItemSpan(maxLineSpan) }) { InlineLoader() }
          }
        }
      }
    }
  }
}

@Composable
private fun SearchField(state: UiState, onAction: (UiAction) -> Unit) {
  OutlinedTextField(
    value = state.searchQuery,
    onValueChange = { onAction(UiAction.UpdateQuery(it)) },
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    label = { Text("搜索 ${state.platform.displayName} 主播 / 直播间") },
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
