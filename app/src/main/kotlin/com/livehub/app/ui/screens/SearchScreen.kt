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
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AssistChip
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.UiAction
import com.livehub.app.ui.UiState
import com.livehub.app.ui.components.StreamerCard

/** Search screen with history chips and a results grid. */
@Composable
fun SearchScreen(state: UiState, onAction: (UiAction) -> Unit) {
  val keys = remember(state.followed) { state.followed.map { it.key }.toSet() }

  Column(modifier = Modifier.fillMaxSize()) {
    OutlinedTextField(
      value = state.searchQuery,
      onValueChange = { onAction(UiAction.UpdateQuery(it)) },
      modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
      label = { Text("搜索主播或直播间") },
      singleLine = true,
      trailingIcon = {
        IconButton(onClick = { onAction(UiAction.SubmitSearch) }) {
          Icon(Icons.Filled.Search, contentDescription = "搜索")
        }
      },
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
      keyboardActions = KeyboardActions(onSearch = { onAction(UiAction.SubmitSearch) }),
    )

    if (state.searchHistory.isNotEmpty() && state.searchResults.isEmpty()) {
      LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
      ) {
        items(state.searchHistory) { keyword ->
          AssistChip(
            onClick = { onAction(UiAction.UseHistory(keyword)) },
            label = { Text(keyword) },
          )
        }
      }
    }

    Box(modifier = Modifier.fillMaxSize()) {
      when {
        state.loading -> CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
        state.searchResults.isEmpty() && state.searchQuery.isNotBlank() ->
          Text(
            text = "没有找到相关直播",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.align(Alignment.Center),
          )
        else -> LazyVerticalGrid(
          columns = GridCells.Fixed(2),
          contentPadding = PaddingValues(12.dp),
          horizontalArrangement = Arrangement.spacedBy(10.dp),
          verticalArrangement = Arrangement.spacedBy(10.dp),
          modifier = Modifier.fillMaxSize(),
        ) {
          items(items = state.searchResults, key = { it.key }) { streamer ->
            StreamerCard(
              streamer = streamer,
              followed = keys.contains(streamer.key),
              onClick = { onAction(UiAction.OpenRoom(streamer)) },
              onToggleFollow = { onAction(UiAction.ToggleFollow(streamer)) },
            )
          }
        }
      }
    }
  }
}
