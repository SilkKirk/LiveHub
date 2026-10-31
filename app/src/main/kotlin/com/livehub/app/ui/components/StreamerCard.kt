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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.StarBorder
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.livehub.core.common.ViewerCountFormatter
import com.livehub.core.designsystem.LiveBadgeColor
import com.livehub.core.model.Streamer

/**
 * A room card used by the home, category, search and follow grids.
 *
 * The cover falls back to a solid placeholder with a play glyph rather than an empty
 * box, so a grid of rooms whose images failed to load still looks intentional. The
 * follow toggle sits on the cover with a scrim so it stays readable over bright
 * thumbnails.
 */
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
        CoverImage(streamer = streamer)

        if (streamer.isLive) {
          Surface(
            color = LiveBadgeColor,
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.padding(6.dp).align(Alignment.TopStart),
          ) {
            Text(
              text = "LIVE",
              style = MaterialTheme.typography.labelSmall,
              color = Color.White,
              fontWeight = FontWeight.Bold,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
        } else {
          Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.padding(6.dp).align(Alignment.TopStart),
          ) {
            Text(
              text = "未开播",
              style = MaterialTheme.typography.labelSmall,
              color = Color.White,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
        }

        if (streamer.viewerCount > 0) {
          Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.padding(6.dp).align(Alignment.BottomStart),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(3.dp),
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
              Icon(
                imageVector = Icons.Outlined.Visibility,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                tint = Color.White,
              )
              Text(
                text = ViewerCountFormatter.format(streamer.viewerCount),
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
              )
            }
          }
        }

        IconButton(
          onClick = onToggleFollow,
          modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp),
        ) {
          Icon(
            imageVector = if (followed) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = if (followed) "取消关注" else "关注",
            tint = if (followed) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier
              .size(20.dp)
              .background(Color.Black.copy(alpha = 0.35f), CircleShape),
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
          Avatar(streamer = streamer)
          Text(
            text = streamer.streamerName.ifBlank { "未知主播" },
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
          )
          PlatformChip(name = streamer.platform.displayName)
        }
      }
    }
  }
}

@Composable
private fun CoverImage(streamer: Streamer) {
  if (streamer.coverUrl.isNullOrBlank()) {
    CoverPlaceholder(title = streamer.title.ifBlank { streamer.streamerName })
  } else {
    AsyncImage(
      model = streamer.coverUrl,
      contentDescription = streamer.title,
      contentScale = ContentScale.Crop,
      modifier = Modifier
        .fillMaxSize()
        .background(MaterialTheme.colorScheme.surfaceVariant),
    )
  }
}

/** Deterministic gradient placeholder so a missing cover is still visually informative. */
@Composable
private fun CoverPlaceholder(title: String) {
  val palette = listOf(
    Color(0xFF2C3E50) to Color(0xFF4CA1AF),
    Color(0xFF3A1C71) to Color(0xFFD76D77),
    Color(0xFF134E5E) to Color(0xFF71B280),
    Color(0xFF642B73) to Color(0xFFC6426E),
  )
  val pair = palette[(title.hashCode().absoluteValue) % palette.size]

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(androidx.compose.ui.graphics.Brush.linearGradient(listOf(pair.first, pair.second))),
    contentAlignment = Alignment.Center,
  ) {
    Text(
      text = title.take(8).ifBlank { "直播" },
      style = MaterialTheme.typography.titleMedium,
      color = Color.White.copy(alpha = 0.85f),
      textAlign = androidx.compose.ui.text.style.TextAlign.Center,
      modifier = Modifier.padding(horizontal = 8.dp),
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun Avatar(streamer: Streamer) {
  if (streamer.avatarUrl.isNullOrBlank()) {
    val initial = streamer.streamerName.firstOrNull()?.toString() ?: "?"
    Box(
      modifier = Modifier
        .size(16.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.25f)),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = initial,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
      )
    }
  } else {
    AsyncImage(
      model = streamer.avatarUrl,
      contentDescription = null,
      contentScale = ContentScale.Crop,
      modifier = Modifier
        .size(16.dp)
        .clip(CircleShape)
        .background(MaterialTheme.colorScheme.surfaceVariant),
    )
  }
}

@Composable
private fun PlatformChip(name: String) {
  Surface(
    color = MaterialTheme.colorScheme.secondaryContainer,
    shape = RoundedCornerShape(4.dp),
  ) {
    Text(
      text = name,
      style = MaterialTheme.typography.labelSmall,
      color = MaterialTheme.colorScheme.onSecondaryContainer,
      modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp),
    )
  }
}

private val Int.absoluteValue: Int get() = if (this == Int.MIN_VALUE) 0 else kotlin.math.abs(this)
