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
import androidx.compose.material.icons.filled.PlayArrow
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
import androidx.compose.ui.graphics.Brush
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

        // The partition, bottom-right, the way the reference client labels every card. It is
        // rendered only when the platform actually reports one: Douyu (`cate2_name`) and Huya
        // (the page's game tag) do, while Bilibili's room endpoint carries no area name and
        // Douyin has no partition on the room at all. An empty badge on those two platforms
        // would be worse than no badge.
        //
        // The local is not cosmetic: `categoryName` lives in `:core:model`, so a smart cast after
        // the null check is impossible and `text =` would not compile against `String?`.
        val partition = streamer.categoryName
        if (!partition.isNullOrBlank()) {
          Surface(
            color = Color.Black.copy(alpha = 0.55f),
            shape = RoundedCornerShape(4.dp),
            modifier = Modifier.padding(6.dp).align(Alignment.BottomEnd),
          ) {
            Text(
              text = partition,
              style = MaterialTheme.typography.labelSmall,
              color = Color.White,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
          }
        }

        IconButton(
          onClick = onToggleFollow,
          modifier = Modifier.align(Alignment.TopEnd).padding(2.dp),
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
          // No platform chip. This tile only appears on the home grid, which the switcher
          // above it already scopes to one platform, so every card repeated the same label —
          // and the label took width from the streamer name. The search rows dropped it for
          // the same reason in round 29; the follow list filters by platform instead of
          // labelling each row.
        }
      }
    }
  }
}

/**
 * A search result, as a wide row rather than a grid tile.
 *
 * The reference client has a second card shape for search (`StreamerCardStyle.Search` in
 * `dtv_mobile`: a `104x66` cover beside the text, not the 16:10 tile the grids use), and the
 * two shapes carry different amounts of text: the tile has room for one ellipsized title line,
 * while a search result can afford two, plus the viewer count as a normal label instead of a
 * small overlay on the cover. Results are also always one platform, so the platform chip the
 * tile shows would repeat the search field's own label on every row.
 *
 * The follow affordance stays the star the rest of the app uses, not the reference's heart:
 * the same action should not change icon when it appears on a different screen.
 */
@Composable
fun StreamerSearchCard(
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
    Row(
      modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(width = 104.dp, height = 66.dp)
          .clip(RoundedCornerShape(8.dp)),
      ) {
        CoverImage(streamer = streamer)

        Surface(
          color = if (streamer.isLive) LiveBadgeColor else Color.Black.copy(alpha = 0.55f),
          shape = RoundedCornerShape(4.dp),
          modifier = Modifier.padding(5.dp).align(Alignment.TopStart),
        ) {
          Text(
            text = if (streamer.isLive) "LIVE" else "未开播",
            style = MaterialTheme.typography.labelSmall,
            color = Color.White,
            fontWeight = if (streamer.isLive) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
          )
        }
      }

      Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
          text = streamer.title.ifBlank { streamer.streamerName },
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.Medium,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
          Text(
            text = streamer.streamerName.ifBlank { "未知主播" },
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f, fill = false),
          )
          if (streamer.viewerCount > 0) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
              Icon(
                imageVector = Icons.Outlined.Visibility,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
              )
              Text(
                text = ViewerCountFormatter.format(streamer.viewerCount),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
              )
            }
          }
        }
      }

      IconButton(onClick = onToggleFollow, modifier = Modifier.size(36.dp)) {
        Icon(
          imageVector = if (followed) Icons.Filled.Star else Icons.Filled.StarBorder,
          contentDescription = if (followed) "取消关注" else "关注",
          tint = if (followed) {
            MaterialTheme.colorScheme.primary
          } else {
            MaterialTheme.colorScheme.onSurfaceVariant
          },
          modifier = Modifier.size(20.dp),
        )
      }
    }
  }
}

@Composable
private fun CoverImage(streamer: Streamer) {
  if (streamer.coverUrl.isNullOrBlank()) {
    CoverPlaceholder(seed = streamer.title.ifBlank { streamer.streamerName })
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
private fun CoverPlaceholder(seed: String) {
  val palette = listOf(
    Color(0xFF2C3E50) to Color(0xFF4CA1AF),
    Color(0xFF3A1C71) to Color(0xFFD76D77),
    Color(0xFF134E5E) to Color(0xFF71B280),
    Color(0xFF642B73) to Color(0xFFC6426E),
  )
  val pair = palette[(seed.hashCode().absoluteValue) % palette.size]

  Box(
    modifier = Modifier
      .fillMaxSize()
      .background(Brush.linearGradient(listOf(pair.first, pair.second))),
    contentAlignment = Alignment.Center,
  ) {
    // A play glyph, not the title: the title is already shown directly below the cover,
    // so repeating it inside the placeholder just reads as duplicated text.
    Icon(
      imageVector = Icons.Filled.PlayArrow,
      contentDescription = null,
      tint = Color.White.copy(alpha = 0.55f),
      modifier = Modifier.size(36.dp),
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

private val Int.absoluteValue: Int get() = if (this == Int.MIN_VALUE) 0 else kotlin.math.abs(this)
