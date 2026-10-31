package com.livehub.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.filled.Star
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.app.ui.components.StreamerCard
import com.livehub.core.model.Platform

/** Home screen: platform switcher, category pills and the room grid. */
@Composable
fun HomeScreen(state: UiState, onAction: (UiAction) -> Unit) {
  val gridState = rememberLazyGridState()
  val followedKeys = remember(state.followed) { state.followed.map { it.key }.toSet() }
  val shouldLoadMore by remember {
    derivedStateOf {
      val last = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
      last >= state.rooms.size - 4 && state.rooms.isNotEmpty()
    }
  }

  LaunchedEffect(shouldLoadMore, state.rooms.size) {
    if (shouldLoadMore) onAction(UiAction.LoadMore)
  }

  Column(modifier = Modifier.fillMaxSize()) {
    PlatformBar(state = state, onAction = onAction)
    CategoryRow(state = state, onAction = onAction)

    Box(modifier = Modifier.fillMaxSize()) {
      if (state.rooms.isEmpty() && state.loading) {
        CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
      } else if (state.rooms.isEmpty()) {
        Text(
          text = state.error ?: "暂无直播",
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.align(Alignment.Center),
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
              Box(modifier = Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(modifier = Modifier.height(24.dp))
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun PlatformBar(state: UiState, onAction: (UiAction) -> Unit) {
  Row(
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    LazyRow(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.weight(1f),
    ) {
      items(Platform.supported) { platform ->
        FilterChip(
          selected = state.platform == platform,
          onClick = { onAction(UiAction.SelectPlatform(platform)) },
          label = { Text(platform.displayName) },
        )
      }
    }
    IconButton(onClick = { onAction(UiAction.Refresh) }) {
      Icon(Icons.Filled.Refresh, contentDescription = "刷新")
    }
  }
}

/**
 * Two level category selector.
 *
 * The top row pins the user's subscribed categories (long press any chip to pin or
 * unpin); the second row lists the categories of the currently selected parent. Pinned
 * entries are synchronised between devices through the CATEGORIES scope.
 */
@Composable
private fun CategoryRow(state: UiState, onAction: (UiAction) -> Unit) {
  if (state.categories.isEmpty()) return
  val parents = remember(state.categories) {
    state.categories.mapNotNull { it.parentName }.distinct()
  }
  val pinnedKeys = remember(state.subscribedCategories) {
    state.subscribedCategories.map { it.key }.toSet()
  }

  Column(modifier = Modifier.fillMaxWidth()) {
    if (state.subscribedCategories.isNotEmpty()) {
      LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(state.subscribedCategories, key = { "pinned-${it.key}" }) { category ->
          FilterChip(
            selected = state.selectedCategory?.key == category.key,
            onClick = { onAction(UiAction.SelectCategory(category)) },
            label = { Text("★ ${category.name}") },
            leadingIcon = null,
          )
        }
      }
    }

    LazyRow(
      contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      state = rememberLazyListState(),
    ) {
      if (parents.size > 1) {
        items(parents) { parent ->
          val first = state.categories.firstOrNull { it.parentName == parent }
          FilterChip(
            selected = state.selectedCategory?.parentName == parent,
            onClick = { first?.let { onAction(UiAction.SelectCategory(it)) } },
            label = { Text(parent, fontWeight = FontWeight.Medium) },
          )
        }
      } else {
        items(state.categories, key = { it.key }) { category ->
          FilterChip(
            selected = state.selectedCategory?.key == category.key,
            onClick = { onAction(UiAction.SelectCategory(category)) },
            label = { Text(category.name) },
            leadingIcon = if (pinnedKeys.contains(category.key)) {
              { Icon(Icons.Filled.Star, contentDescription = "已订阅", modifier = Modifier.size(14.dp)) }
            } else {
              null
            },
          )
        }
      }
    }
  }
}
