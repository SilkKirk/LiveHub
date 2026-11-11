package com.livehub.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.background
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CloudOff
import androidx.compose.material.icons.outlined.LiveTv
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

/** Full-screen state message with an optional action, used by every list screen. */
@Composable
fun StateMessage(
  icon: ImageVector,
  title: String,
  modifier: Modifier = Modifier,
  description: String? = null,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
  secondaryLabel: String? = null,
  onSecondary: (() -> Unit)? = null,
) {
  Column(
    modifier = modifier.fillMaxSize().padding(32.dp),
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.Center,
  ) {
    Icon(
      imageVector = icon,
      contentDescription = null,
      modifier = Modifier.size(56.dp),
      tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
    )
    Text(
      text = title,
      style = MaterialTheme.typography.titleMedium,
      fontWeight = FontWeight.SemiBold,
      textAlign = TextAlign.Center,
      modifier = Modifier.padding(top = 16.dp),
    )
    if (!description.isNullOrBlank()) {
      Text(
        text = description,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.padding(top = 8.dp),
      )
    }
    if (actionLabel != null && onAction != null) {
      OutlinedButton(onClick = onAction, modifier = Modifier.padding(top = 20.dp)) {
        Text(actionLabel)
      }
    }
    if (secondaryLabel != null && onSecondary != null) {
      TextButton(onClick = onSecondary, modifier = Modifier.padding(top = 4.dp)) {
        Text(secondaryLabel)
      }
    }
  }
}

/** The standard "network failed" state. */
@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
  StateMessage(
    icon = Icons.Outlined.CloudOff,
    title = "加载失败",
    description = message,
    actionLabel = "重试",
    onAction = onRetry,
    modifier = modifier,
  )
}

/** The standard "nothing here" state. */
@Composable
fun EmptyState(
  title: String,
  modifier: Modifier = Modifier,
  description: String? = null,
  actionLabel: String? = null,
  onAction: (() -> Unit)? = null,
  icon: ImageVector = Icons.Outlined.LiveTv,
) {
  StateMessage(
    icon = icon,
    title = title,
    description = description,
    actionLabel = actionLabel,
    onAction = onAction,
    modifier = modifier,
  )
}

/**
 * A grid of shimmering placeholder cards shown while the first page loads.
 *
 * A bare spinner on an empty screen gives no sense of what is coming; skeletons keep
 * the layout stable and make the load feel faster.
 */
@Composable
fun StreamerGridSkeleton(
  modifier: Modifier = Modifier,
  itemCount: Int = 6,
) {
  LazyVerticalGrid(
    // Must match the real grid in HomeScreen/FollowScreen exactly, or the column count changes the
    // moment the first page arrives and every tile jumps sideways. Both use [CardMinWidth].
    columns = GridCells.Adaptive(minSize = CardMinWidth),
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(12.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
    userScrollEnabled = false,
  ) {
    items(count = itemCount) {
      Column {
        Box(
          modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(16f / 9f)
            .padding(0.dp)
            .background(
              MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
              RoundedCornerShape(12.dp),
            ),
        )
        SkeletonBar(widthFraction = 0.9f, topPadding = 8.dp)
        SkeletonBar(widthFraction = 0.55f, topPadding = 6.dp, height = 10.dp)
      }
    }
  }
}

/**
 * The list-shaped version of [StreamerGridSkeleton], for screens that render rows.
 *
 * The search screen switched from a two-column grid to a single-column list of result rows;
 * the grid skeleton would have promised a layout that never arrives, and the change of shape
 * mid-load is more distracting than no skeleton at all.
 */
@Composable
fun StreamerListSkeleton(
  modifier: Modifier = Modifier,
  itemCount: Int = 6,
) {
  LazyColumn(
    modifier = modifier.fillMaxSize(),
    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
    verticalArrangement = Arrangement.spacedBy(10.dp),
    userScrollEnabled = false,
  ) {
    items(count = itemCount) {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        Box(
          modifier = Modifier
            .size(width = 104.dp, height = 66.dp)
            .background(
              MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f),
              RoundedCornerShape(8.dp),
            ),
        )
        Column(modifier = Modifier.weight(1f)) {
          SkeletonBar(widthFraction = 0.9f, topPadding = 6.dp)
          SkeletonBar(widthFraction = 0.55f, topPadding = 8.dp, height = 10.dp)
        }
      }
    }
  }
}

@Composable
private fun SkeletonBar(
  widthFraction: Float,
  topPadding: androidx.compose.ui.unit.Dp,
  height: androidx.compose.ui.unit.Dp = 12.dp,
) {
  Box(
    modifier = Modifier
      .fillMaxWidth(widthFraction)
      .padding(top = topPadding)
      .height(height)
      .background(
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        RoundedCornerShape(4.dp),
      ),
  )
}

/** Centered spinner used for "load more" footers and inline refreshes. */
@Composable
fun InlineLoader(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 56.dp) {
  Box(modifier = modifier.fillMaxWidth().height(height), contentAlignment = Alignment.Center) {
    CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
  }
}
