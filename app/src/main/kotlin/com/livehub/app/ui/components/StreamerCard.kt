package com.livehub.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.livehub.core.common.ViewerCountFormatter
import com.livehub.core.designsystem.LiveBadgeColor
import com.livehub.core.model.Streamer

/** Card corner radius. 16dp reads as "tile" rather than "button" at 2-column widths. */
private val CardShape = RoundedCornerShape(16.dp)

/**
 * Narrowest a room tile may get before the grid adds a column.
 *
 * 140dp, and the number is derived rather than picked. `GridCells.Adaptive` takes the largest `n`
 * with `n * minSize + (n - 1) * spacing <= width - 2 * contentPadding`, and the grids use 12dp of
 * padding with 10dp between tiles. At the 320dp phone width the app already ships on, that leaves
 * 296dp, so `minSize` must be **at most 143dp** for two columns to fit — 156dp, the first value
 * tried here, silently collapsed the phone grid to a single column. At 640dp this resolves to four.
 *
 * Shared by the real grids and by the loading skeleton so the column count cannot change when the
 * first page arrives.
 */
internal val CardMinWidth: Dp = 140.dp

/** The cover is inset inside the card, so its radius is the card's minus the inset. */
private val CoverShape = RoundedCornerShape(12.dp)

/** Badges are fully rounded rather than 4dp rectangles: they read as labels, not as chips. */
private val PillShape = RoundedCornerShape(percent = 50)

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
  /**
   * What the star means on this surface.
   *
   * The follow grid has no use for "关注" — every card in it is already followed — so there the
   * star pins the room to 特别关注 instead. The icon stays the same because it is the same kind of
   * action (a persistent mark on a room); only the words change, and they change because a screen
   * reader user would otherwise be told "取消关注" by a button that unfollows nothing.
   */
  starLabels: Pair<String, String> = "关注" to "取消关注",
  /**
   * A second tap gesture, for the screens that need one.
   *
   * Only the follow grid uses it (long-press opens the tag editor). Null on every other surface, so
   * no card there has to answer for a gesture nobody defined.
   */
  onLongClick: (() -> Unit)? = null,
  /**
   * Whether a live room draws the "LIVE" badge on its cover.
   *
   * This is a property of the *list*, not of the room. The home and search grids are filled from a
   * platform's "on air now" listing, so every tile in them is live and the badge says the same
   * word once per card — the premise of the whole page, repeated. The follow grid is the one list
   * that mixes live and offline rooms (an offline card says 未开播 instead), so there the badge is
   * the thing that lets the two be told apart at a glance.
   */
  showLiveBadge: Boolean = true,
) {
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .clip(CardShape)
      .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    // A card is a *layer* now, not a tinted copy of the page: a real surface colour plus a soft
    // shadow. Before this the tile was `surfaceVariant` at 35% over a background of the same
    // family, so the only thing separating it from the page was its cover image.
    color = MaterialTheme.colorScheme.surface,
    shape = CardShape,
    shadowElevation = 1.dp,
    tonalElevation = 0.dp,
  ) {
    Column {
      Box(
        modifier = Modifier
          .fillMaxWidth()
          .padding(6.dp)
          .clip(CoverShape)
          .aspectRatio(16f / 9f),
      ) {
        CoverImage(streamer = streamer)

        if (streamer.isLive && showLiveBadge) {
          Surface(
            color = LiveBadgeColor,
            shape = PillShape,
            modifier = Modifier.padding(6.dp).align(Alignment.TopStart),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(3.dp),
              modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            ) {
              Box(
                modifier = Modifier
                  .size(4.dp)
                  .clip(CircleShape)
                  .background(Color.White),
              )
              Text(
                text = "LIVE",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White,
                fontWeight = FontWeight.Bold,
              )
            }
          }
        // `else if`, not `else`: a live room on a list that suppresses the badge must draw nothing
        // at all, and a plain `else` would label it 未开播 — the one reading that is actually false.
        } else if (!streamer.isLive) {
          Surface(
            color = Color.Black.copy(alpha = 0.5f),
            shape = PillShape,
            modifier = Modifier.padding(6.dp).align(Alignment.TopStart),
          ) {
            Text(
              text = "未开播",
              style = MaterialTheme.typography.labelSmall,
              color = Color.White.copy(alpha = 0.9f),
              modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            )
          }
        }

        if (streamer.viewerCount > 0) {
          Surface(
            color = Color.Black.copy(alpha = 0.5f),
            shape = PillShape,
            modifier = Modifier.padding(6.dp).align(Alignment.BottomStart),
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(3.dp),
              modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            ) {
              Icon(
                imageVector = Icons.Outlined.Visibility,
                contentDescription = null,
                modifier = Modifier.size(11.dp),
                tint = Color.White.copy(alpha = 0.9f),
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
            color = Color.Black.copy(alpha = 0.5f),
            shape = PillShape,
            modifier = Modifier.padding(6.dp).align(Alignment.BottomEnd),
          ) {
            Text(
              text = partition,
              style = MaterialTheme.typography.labelSmall,
              color = Color.White.copy(alpha = 0.95f),
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
            )
          }
        }

        // `size(48.dp)`, and no padding.
        //
        // The trap is that `IconButton`'s own `minimumInteractiveComponentSize()` sits **inside**
        // the caller's modifier, so anything that constrains the button from the outside also
        // constrains what the finger can reach: `size(32.dp)` gave a 32dp target, and
        // `size(48.dp).padding(8.dp)` — the first attempt at fixing it — reported a 48dp semantics
        // node while leaving the pointer area at 32dp, because the padding is what the inner
        // `size(40.dp)` saw.
        //
        // What `size(48.dp)` buys is Material's own state layer: 40dp of pointer area centred in a
        // 48dp node. The outer 4dp ring belongs to the node, not to the button — a tap there falls
        // through to the card. That is the component's design, not something this call can change;
        // `TouchTargetTest` taps 6dp in precisely because that ring is inert.
        IconButton(
          onClick = onToggleFollow,
          modifier = Modifier.align(Alignment.TopEnd).size(48.dp),
        ) {
          Icon(
            imageVector = if (followed) Icons.Filled.Star else Icons.Filled.StarBorder,
            contentDescription = if (followed) starLabels.second else starLabels.first,
            // A filled star sits on a solid disc so it reads over any cover; the hollow one only
            // needs the scrim, which keeps the card calm when nothing is followed.
            tint = if (followed) MaterialTheme.colorScheme.primary else Color.White,
            modifier = Modifier
              .size(19.dp)
              .background(
                if (followed) Color.White.copy(alpha = 0.92f) else Color.Black.copy(alpha = 0.3f),
                CircleShape,
              )
              .padding(3.dp),
          )
        }
      }

      Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
        Text(
          text = streamer.title.ifBlank { streamer.streamerName },
          style = MaterialTheme.typography.bodyMedium,
          fontWeight = FontWeight.SemiBold,
          // Two lines, not one. The cover lost a few pixels of height to the inset, and a title
          // is the one piece of text on the card that actually says what the stream is about —
          // ellipsizing "【预告】7日6点 T1 vs KC" to "【预告】7日6点 T1…" told the user nothing.
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
          lineHeight = 18.sp,
        )
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(5.dp),
          modifier = Modifier.padding(top = 6.dp),
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
  /** The same per-list rule as [StreamerCard]: search results all come from rooms on air. */
  showLiveBadge: Boolean = true,
) {
  Surface(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick),
    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
  ) {
    Row(
      // Denser than the grid tile, because the trailing star and the viewer count have to share
      // one line with the name: at 320dp the row is 296dp wide, and every dp here comes out of
      // "一条小团团" (the name ellipsized two characters when the star grew to 48dp).
      modifier = Modifier.padding(start = 8.dp, top = 8.dp, end = 6.dp, bottom = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      Box(
        modifier = Modifier
          .size(width = 100.dp, height = 66.dp)
          .clip(RoundedCornerShape(8.dp)),
      ) {
        CoverImage(streamer = streamer)

        // Only the LIVE label is scene-dependent: an offline row still says 未开播, wherever the
        // row is drawn, because that is news the list itself does not carry.
        if (!streamer.isLive || showLiveBadge) {
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

      // `size(48.dp)` for the same reason as the grid tile's star: a caller's `size(36.dp)` used to
      // constrain Material's own minimum from the outside, so the pointer area was 36dp on the
      // row's outermost control — where a miss opens the room instead of following it.
      IconButton(onClick = onToggleFollow, modifier = Modifier.size(48.dp)) {
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
