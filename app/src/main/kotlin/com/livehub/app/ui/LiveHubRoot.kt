package com.livehub.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.screens.FollowScreen
import com.livehub.app.ui.screens.HomeScreen
import com.livehub.app.ui.screens.PlayerScreen
import com.livehub.app.ui.screens.SearchScreen
import com.livehub.app.ui.screens.SettingsScreen
import com.livehub.app.ui.screens.SyncScreen
import com.livehub.core.designsystem.LiveHubTheme
import com.livehub.core.designsystem.ThemeMode

/** Bottom bar destinations, in display order. */
private enum class Dock(val screen: Screen, val label: String, val icon: ImageVector) {
  HOME(Screen.Home, "首页", Icons.Filled.Home),
  FOLLOW(Screen.Follow, "关注", Icons.Filled.Star),
  SEARCH(Screen.Search, "搜索", Icons.Filled.Search),
  SYNC(Screen.Sync, "同步", Icons.Filled.Sync),
  SETTINGS(Screen.Settings, "设置", Icons.Filled.Settings),
}

@Composable
fun LiveHubRoot(
  state: UiState,
  onAction: (UiAction) -> Unit,
) {
  val snackbar = remember { SnackbarHostState() }
  val themeMode = if (state.darkTheme) ThemeMode.Dark else ThemeMode.System

  // Only transient messages go through the snackbar. A load error is already rendered
  // inline by the screen (with a retry button), so echoing it here duplicates the text.
  LaunchedEffect(state.message) {
    val notice = state.message
    if (!notice.isNullOrBlank()) {
      snackbar.showSnackbar(notice)
      onAction(UiAction.DismissMessage)
    }
  }

  // System back leaves the player (and any non-home tab) instead of closing the app.
  BackHandler(enabled = state.screen != Screen.Home) {
    onAction(UiAction.Back)
  }

  LiveHubTheme(mode = themeMode) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
      if (state.screen == Screen.Player) {
        // Swipe from the left edge is the gesture users expect for "go back".
        Box(
          modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
              detectHorizontalDragGestures { change, dragAmount ->
                change.consume()
                if (dragAmount > SWIPE_THRESHOLD_PX) {
                  onAction(UiAction.ClosePlayer)
                }
              }
            },
        ) {
          PlayerScreen(state = state, onAction = onAction)
        }
      } else {
        Scaffold(
          snackbarHost = { SnackbarHost(snackbar) },
          bottomBar = { AppBottomBar(state = state, onAction = onAction) },
        ) { padding ->
          Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state.screen) {
              Screen.Follow -> FollowScreen(state, onAction)
              Screen.Search -> SearchScreen(state, onAction)
              Screen.Sync -> SyncScreen(state, onAction)
              Screen.Settings -> SettingsScreen(state, onAction)
              else -> HomeScreen(state, onAction)
            }
          }
        }
      }
    }
  }
}

@Composable
private fun AppBottomBar(state: UiState, onAction: (UiAction) -> Unit) {
  NavigationBar {
    Dock.entries.forEach { dock ->
      NavigationBarItem(
        selected = state.screen == dock.screen ||
          (dock == Dock.HOME && state.screen == Screen.Category),
        onClick = { onAction(UiAction.Navigate(dock.screen)) },
        icon = { Icon(dock.icon, contentDescription = dock.label) },
        label = { Text(dock.label) },
      )
    }
  }
}

/** A finger travel of roughly 60dp counts as a back gesture. */
private const val SWIPE_THRESHOLD_PX = 180f
