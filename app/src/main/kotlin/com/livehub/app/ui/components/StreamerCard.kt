package com.livehub.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.livehub.core.common.ViewerCountFormatter
import com.livehub.core.designsystem.LiveBadgeColor
import com.livehub.core.model.Streamer

/** A room card used by the home, category, search and follow grids. */
@Composable
fun StreamerCard(
  streamer: Streamer,
  followed: Boolean,
  onClick: () -> Unit,
  onToggleFollow: () -> Unit,
  modifier: Modifier = Modifier,
) {
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
  ) {
    Column {
      Box(modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f)) {
        AsyncImage(
          model = streamer.coverUrl,
          contentDescription = streamer.title,
          contentScale = ContentScale.Crop,
          modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.surfaceVariant),
        )
        if (streamer.isLive) {
          Surface(
            color = LiveBadgeColor,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.padding(6.dp).align(Alignment.TopStart),
          ) {
            Text(
              text = "LIVE",
              style = MaterialTheme.typography.labelSmall,
              color = MaterialTheme.colorScheme.onPrimary,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
        }
        IconButton(
          onClick = onToggleFollow,
          modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp),
        ) {
          Icon(
            imageVector = Icons.Filled.Star,
            contentDescription = if (followed) "取消关注" else "关注",
            tint = if (followed) {
              MaterialTheme.colorScheme.primary
            } else {
              MaterialTheme.colorScheme.onSurfaceVariant
            },
          )
        }
      }
      Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
        Text(
          text = streamer.title.ifBlank { streamer.streamerName },
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(4.dp),
          modifier = Modifier.padding(top = 4.dp),
        ) {
          AsyncImage(
            model = streamer.avatarUrl,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(16.dp).clip(CircleShape)
              .background(MaterialTheme.colorScheme.surfaceVariant),
          )
          Text(
            text = streamer.streamerName.ifBlank { "未知主播" },
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(96.dp),
          )
          Icon(
            imageVector = Icons.Outlined.Visibility,
            contentDescription = null,
            modifier = Modifier.size(12.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
          )
          Text(
            text = ViewerCountFormatter.format(streamer.viewerCount),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}
