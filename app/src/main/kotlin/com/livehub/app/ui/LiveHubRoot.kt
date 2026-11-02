package com.livehub.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.screens.FollowScreen
import com.livehub.app.ui.screens.HomeScreen
import com.livehub.app.ui.screens.PlayerScreen
import com.livehub.app.ui.screens.SearchScreen
import com.livehub.app.ui.screens.SettingsScreen
import com.livehub.app.ui.screens.SyncScreen
import com.livehub.core.designsystem.LiveHubTheme

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

  LiveHubTheme(mode = state.themeMode) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
      if (state.screen == Screen.Player) {
        BackSwipeContainer(onClose = { onAction(UiAction.ClosePlayer) }) {
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

/**
 * Left-edge swipe to leave a full-screen page, the gesture users expect for "back".
 *
 * The previous version could not work, for two independent reasons:
 *
 *  * `detectHorizontalDragGestures` reports the movement **per frame**, not since the
 *    gesture began. Comparing that against a 180px threshold meant a fast flick of a few
 *    dozen pixels per frame never reached it, so the gesture effectively did nothing;
 *  * when it did trip, it fired on *every* remaining frame, calling `ClosePlayer` dozens of
 *    times for one swipe.
 *
 * The travel is accumulated here and the callback fires once. The gesture also has to
 * start near the left edge, like the platform back gesture: a horizontal drag in the
 * middle of the picture is how the video is panned, not how you leave it.
 *
 * Internal rather than private so `BackSwipeContainerTest` can drive it; the bug it
 * guards against (a threshold that a per-frame delta can never reach) is invisible in any
 * test that only composes the screen and looks at it.
 */
@Composable
internal fun BackSwipeContainer(
  onClose: () -> Unit,
  content: @Composable () -> Unit,
) {
  var dragStartX by remember { mutableStateOf<Float?>(null) }
  // The edge zone is a fraction of the real width, so the pointer handler has to be rebuilt
  // once the layout reports it. Keying `pointerInput` on `Unit` meant the handler captured
  // the initial 0 and never saw the width at all.
  var widthPx by remember { mutableStateOf(0f) }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .onSizeChanged {
        widthPx = it.width.toFloat()
        dragStartX = null
      }
      .pointerInput(widthPx) {
        if (widthPx <= 0f) return@pointerInput
        val edgeZone = widthPx * EDGE_ZONE_FRACTION
        detectHorizontalDragGestures(
          onDragStart = { offset -> dragStartX = offset.x },
          onDragEnd = { dragStartX = null },
          onDragCancel = { dragStartX = null },
        ) { change, _ ->
          change.consume()
          val start = dragStartX ?: return@detectHorizontalDragGestures
          if (start > edgeZone) return@detectHorizontalDragGestures
          // `position.x` is relative to this Box, so this is the distance travelled from
          // where the finger went down.
          if (change.position.x - start >= SWIPE_THRESHOLD_PX) {
            dragStartX = null
            onClose()
          }
        }
      },
  ) {
    content()
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

/**
 * How close to the left edge a drag has to start to count as "go back".
 *
 * Matches the platform edge-back zone closely enough to feel the same, and keeps a
 * horizontal drag across the middle of the picture from closing the player.
 */
private const val EDGE_ZONE_FRACTION = 0.08f
