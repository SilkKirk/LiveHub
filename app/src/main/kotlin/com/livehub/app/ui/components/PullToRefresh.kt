package com.livehub.app.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * Pull-to-refresh wrapper used by every list screen.
 *
 * Material3's own [PullToRefreshBox] is enough here; the wrapper exists so the indicator
 * style stays identical on the home, follow and search surfaces and so a screen cannot
 * forget the `refreshing` guard that would otherwise let a second pull start a second
 * request mid-flight.
 *
 * The reference client puts the same affordance on all four platform screens and the
 * follow list; without it the only way to see fresh rooms was to switch category and back.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RefreshableBox(
  refreshing: Boolean,
  onRefresh: () -> Unit,
  modifier: Modifier = Modifier,
  enabled: Boolean = true,
  content: @Composable () -> Unit,
) {
  val state = rememberPullToRefreshState()
  PullToRefreshBox(
    isRefreshing = refreshing,
    onRefresh = { if (enabled && !refreshing) onRefresh() },
    modifier = modifier.fillMaxSize(),
    state = state,
    indicator = {
      PullToRefreshDefaults.Indicator(
        state = state,
        isRefreshing = refreshing,
        modifier = Modifier.align(Alignment.TopCenter),
      )
    },
  ) {
    Box(modifier = Modifier.fillMaxSize()) { content() }
  }
}
