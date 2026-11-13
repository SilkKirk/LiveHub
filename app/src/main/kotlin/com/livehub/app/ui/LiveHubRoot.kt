package com.livehub.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScaffoldDefaults
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
  /**
   * True while the window is a picture-in-picture thumbnail.
   *
   * Defaulted rather than required so every preview, screenshot and test keeps composing the
   * full-size shell without knowing about the activity's window mode.
   */
  inPictureInPicture: Boolean = false,
) {
  val snackbar = remember { SnackbarHostState() }

  /**
   * Where each dock screen's `rememberSaveable` state lives while it is off screen.
   *
   * The grids and the search results use `rememberLazyGridState` / `rememberLazyListState`, which
   * are `rememberSaveable`. That is not enough on its own: a screen leaves composition whenever
   * another one is shown — and the player replaces this whole scaffold, so *every* screen is gone
   * while a room is open. Without a holder the saved values are discarded with the composition, so
   * coming back from a room landed the user at the top of a grid they had scrolled halfway down.
   *
   * Held above the player branch on purpose: the holder itself has to outlive the branch that is
   * not composed, otherwise it would be re-created (empty) exactly when it is needed.
   */
  val screenStates = rememberSaveableStateHolder()

  // Only transient messages go through the snackbar. A load error is already rendered
  // inline by the screen (with a retry button), so echoing it here duplicates the text.
  LaunchedEffect(state.message) {
    val notice = state.message
    if (!notice.isNullOrBlank()) {
      snackbar.showSnackbar(notice)
      onAction(UiAction.DismissMessage)
    }
  }

  // System back leaves the player (and any non-home tab) instead of closing the app. On the start
  // screen it stops the stream in the mini window first, so the mini window is not something only
  // the 48dp × can reach.
  BackHandler(enabled = state.screen != Screen.Home || state.miniPlayer) {
    onAction(UiAction.Back)
  }

  LiveHubTheme(mode = state.themeMode) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
      val playerOnly = state.screen == Screen.Player
      // While picture-in-picture is up the system draws this window alone, so the shell steps
      // aside — but the player below keeps its place in the tree (see the note there).
      val shellHidden = playerOnly || inPictureInPicture
      // Compact means "thumbnail": the mini window, or the system's PiP window. Not the full player.
      val compactPlayer = inPictureInPicture || !playerOnly

      Scaffold(
        // The keyboard is not part of `Scaffold`'s default content insets, and from Android 15 an
        // edge-to-edge app has to make room for the IME itself — the window is no longer resized
        // for it. Without this the last search result sits under the keyboard.
        modifier = Modifier.imePadding(),
        // The browse screens draw below the status bar. The player applies that inset itself
        // (`PlayerContent`), so insetting it here as well would push the picture down by a status
        // bar's height and leave a band above it — which is why the insets are zeroed when the
        // player owns the window.
        contentWindowInsets = if (shellHidden) {
          WindowInsets(0, 0, 0, 0)
        } else {
          ScaffoldDefaults.contentWindowInsets
        },
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = { if (!shellHidden) AppBottomBar(state = state, onAction = onAction) },
      ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
          if (!shellHidden) {
            screenStates.SaveableStateProvider(state.screen.name) {
              when (state.screen) {
                Screen.Follow -> FollowScreen(state, onAction)
                Screen.Search -> SearchScreen(state, onAction)
                Screen.Sync -> SyncScreen(state, onAction)
                Screen.Settings -> SettingsScreen(state, onAction)
                else -> HomeScreen(state, onAction)
              }
            }
          }

          // One call site, three sizes.
          //
          // The player is composed from here whether it fills the window, sits in the corner or is
          // the system's picture-in-picture thumbnail; only the box around it changes. Composing it
          // anywhere else for one of those states would dispose the `AndroidView` that holds the
          // video surface — a new decoder and a fresh resolve — every time the user steps off the
          // player screen, which is the one thing this shape exists to avoid.
          if (state.room != null) {
            Box(
              modifier = if (shellHidden) {
                Modifier.fillMaxSize()
              } else {
                Modifier
                  .align(Alignment.BottomEnd)
                  .padding(end = 12.dp, bottom = 12.dp)
                  .width(MINI_PLAYER_WIDTH)
                  .aspectRatio(16f / 9f)
                  .shadow(MINI_PLAYER_ELEVATION, RoundedCornerShape(MINI_PLAYER_CORNER))
                  .clip(RoundedCornerShape(MINI_PLAYER_CORNER))
              },
            ) {
              PlayerScreen(state = state, onAction = onAction, compact = compactPlayer)
              if (!shellHidden) MiniPlayerChrome(onAction = onAction)
            }
          }
        }
      }
    }
  }
}

/** Width of the floating player. 16:9, so it is a thumbnail rather than a second window. */
private val MINI_PLAYER_WIDTH = 168.dp

private val MINI_PLAYER_CORNER = 12.dp

private val MINI_PLAYER_ELEVATION = 8.dp

/**
 * What the mini window adds on top of the picture: a tap target the size of the window, and a way
 * to stop.
 *
 * No expand arrow: tapping the picture is the gesture every video app uses for it, and the app's
 * own rule is that the picture is the control. The × is here because the alternative — a stream that
 * can only be stopped by reopening the player — is worse than one extra affordance.
 *
 * Order matters: in a `Box` the last child is drawn on top and gets the pointer events first, so the
 * × is declared *after* the full-window tap layer and is not swallowed by it (the same mistake the
 * player's gesture layer made once; see `PlayerContent`).
 */
@Composable
private fun BoxScope.MiniPlayerChrome(onAction: (UiAction) -> Unit) {
  Box(
    modifier = Modifier
      .matchParentSize()
      .clickable { onAction(UiAction.ExpandPlayer) }
      .semantics { contentDescription = "展开播放页" },
  )
  IconButton(
    onClick = { onAction(UiAction.ClosePlayer) },
    modifier = Modifier.align(Alignment.TopEnd).size(48.dp),
  ) {
    Icon(
      imageVector = Icons.Filled.Close,
      contentDescription = "关闭小窗",
      tint = Color.White,
      modifier = Modifier
        .size(24.dp)
        .background(Color.Black.copy(alpha = 0.45f), CircleShape)
        .padding(4.dp),
    )
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
 * Standard `NavigationBar` + `NavigationBarItem` with its built-in indicator: the selected item
 * gets the Material 3 pill behind its icon for free, and the bar keeps the platform's own insets,
 * ripple and TalkBack behaviour. The only thing configured here is colour — labels a step quieter
 * than icons, so the bar does not out-shout a screen full of video thumbnails.
 */
@Composable
private fun AppBottomBar(state: UiState, onAction: (UiAction) -> Unit) {
  Column {
    HorizontalDivider(
      thickness = 1.dp,
      color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
    )
    NavigationBar(
      containerColor = MaterialTheme.colorScheme.surface,
      tonalElevation = 0.dp,
    ) {
      Dock.entries.forEach { dock ->
        // No extra clause for the home tab: `Screen.Category` used to be OR-ed in here, but nothing
        // ever set it, so the check was dead. See the `Screen` declaration.
        val selected = state.screen == dock.screen
        NavigationBarItem(
          selected = selected,
          onClick = { onAction(UiAction.Navigate(dock.screen)) },
          icon = {
            Icon(
              imageVector = dock.icon,
              contentDescription = dock.label,
              modifier = Modifier.size(22.dp),
            )
          },
          label = {
            Text(
              text = dock.label,
              style = MaterialTheme.typography.labelSmall,
              fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
          },
          colors = NavigationBarItemDefaults.colors(
            selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
            selectedTextColor = MaterialTheme.colorScheme.primary,
            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
          ),
        )
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
