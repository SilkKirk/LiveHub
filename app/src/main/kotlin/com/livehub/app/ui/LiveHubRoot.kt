package com.livehub.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.livehub.app.ui.screens.FollowScreen
import com.livehub.app.ui.screens.HomeScreen
import com.livehub.app.ui.screens.PlayerScreen
import com.livehub.app.ui.screens.SearchScreen
import com.livehub.app.ui.screens.SettingsScreen
import com.livehub.app.ui.screens.SyncScreen
import com.livehub.core.designsystem.LiveHubTheme
import kotlin.math.abs

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
  // The edge zone is a fraction of the real width, so the pointer handler has to be rebuilt
  // once the layout reports it. Keying `pointerInput` on `Unit` meant the handler captured
  // the initial 0 and never saw the width at all.
  var widthPx by remember { mutableStateOf(0f) }

  Box(
    modifier = Modifier
      .fillMaxSize()
      .onSizeChanged { widthPx = it.width.toFloat() }
      .pointerInput(widthPx) {
        if (widthPx <= 0f) return@pointerInput
        val edgeZone = widthPx * EDGE_ZONE_FRACTION
        val swThresholdPx = SWIPE_THRESHOLD.toPx()
        val slop = viewConfiguration.touchSlop

        // Hand-rolled rather than `detectHorizontalDragGestures`, because that helper's
        // `onDragStart` reports the position *after* touch slop has been consumed, not
        // where the finger went down. Judging "did this start at the edge?" from a point
        // already tens of pixels inland rejects genuine edge swipes — which is exactly how
        // the first version of this fix failed its own test.
        awaitEachGesture {
          val down = awaitFirstDown(requireUnconsumed = false)
          val startX = down.position.x
          // Not an edge swipe: leave the events alone so panning the video still works.
          if (startX > edgeZone) return@awaitEachGesture

          var pastSlop = false
          while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) break

            if (!pastSlop) {
              // Ignore the slop distance itself: a vertical-ish drag should not be claimed
              // by the back gesture just because it drifted sideways a little first.
              pastSlop = abs(change.position.x - startX) > slop
            } else {
              change.consume()
              if (change.position.x - startX >= swThresholdPx) {
                onClose()
                break
              }
            }
          }
        }
      },
  ) {
    content()
  }
}

/**
 * The bottom bar.
 *
 * Restyled rather than replaced: the five destinations and their icons are unchanged, but the bar
 * now sits on its own surface with a hairline above it, the selected item gets a tinted pill
 * behind its icon instead of only a colour change, and the labels stay a step quieter than the
 * icons. The default `NavigationBar` colours every label at full strength, which made the bar the
 * busiest thing on a screen whose content is video thumbnails.
 */
@Composable
private fun AppBottomBar(state: UiState, onAction: (UiAction) -> Unit) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    contentColor = MaterialTheme.colorScheme.onSurface,
  ) {
    Column {
      HorizontalDivider(
        thickness = 1.dp,
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
      )
      NavigationBar(
        containerColor = Color.Transparent,
        tonalElevation = 0.dp,
        windowInsets = NavigationBarDefaults.windowInsets,
        modifier = Modifier.height(64.dp),
      ) {
        Dock.entries.forEach { dock ->
          val selected = state.screen == dock.screen ||
            (dock == Dock.HOME && state.screen == Screen.Category)
          NavigationBarItem(
            selected = selected,
            onClick = { onAction(UiAction.Navigate(dock.screen)) },
            icon = {
              Box(
                modifier = Modifier
                  .clip(RoundedCornerShape(percent = 50))
                  .background(
                    if (selected) {
                      MaterialTheme.colorScheme.primaryContainer
                    } else {
                      Color.Transparent
                    },
                  )
                  .padding(horizontal = 14.dp, vertical = 3.dp),
                contentAlignment = Alignment.Center,
              ) {
                Icon(
                  imageVector = dock.icon,
                  contentDescription = dock.label,
                  modifier = Modifier.size(21.dp),
                )
              }
            },
            label = {
              Text(
                text = dock.label,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
              )
            },
            colors = NavigationBarItemDefaults.colors(
              selectedIconColor = MaterialTheme.colorScheme.primary,
              selectedTextColor = MaterialTheme.colorScheme.primary,
              unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
              unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
              indicatorColor = Color.Transparent,
            ),
          )
        }
      }
    }
  }
}

/**
 * How far the finger must travel before the swipe counts, in dp.
 *
 * dp rather than raw pixels: the constant used to be `180f` px with a comment claiming it
 * was "roughly 60dp", which is only true on a 3x screen. On a 1x screen it demanded three
 * inches of travel.
 */
private val SWIPE_THRESHOLD = 60.dp

/**
 * How close to the left edge a drag has to start to count as "go back".
 *
 * Matches the platform edge-back zone closely enough to feel the same, and keeps a
 * horizontal drag across the middle of the picture from closing the player.
 */
private const val EDGE_ZONE_FRACTION = 0.08f
