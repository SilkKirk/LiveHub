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

/** Badges are fully rounded rather than 4dp rectangles: they read as labels, not as chips. */
private val PillShape = RoundedCornerShape(percent = 50)

/**
 * A room tile for the home, category and follow grids.
 *
 * **There is no card.** A tile is a cover and two lines of text, drawn straight on the page: no
 * white container, no shadow, no border. The reference client's grid looks the way it does because
 * the pictures carry the page and everything else gets out of their way, and a 144dp-wide tile with
 * a 6dp frame around its thumbnail plus a shadow under the frame was three surfaces where one was
 * needed. Round 104 removed the container; what is left is the same information, quieter.
 *
 * Only two things sit on the cover: the live state in one corner and the follow star in the other.
 * Everything that is a *fact about the room* rather than a state of it — title, streamer, audience
 * — lives in the text block underneath, which is where the eye goes after the picture.
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
  /**
   * Whether a room draws its partition badge on the cover.
   *
   * The same per-list rule as [showLiveBadge], for a different reason: on the home grid the user has
   * just picked the partition, so every card there repeats the chip they tapped — while on the
   * follow grid a partition is a word about a room somebody chose to keep.
   */
  showCategoryBadge: Boolean = true,
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      // One target, and the clip is what keeps the press ripple inside the tile instead of
      // painting a rounded rectangle's worth of ripple over a transparent text area.
      .clip(CardShape)
      .combinedClickable(onClick = onClick, onLongClick = onLongClick),
  ) {
    Box(
      modifier = Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 9f),
    ) {
      CoverImage(streamer = streamer)

      if (streamer.isLive && showLiveBadge) {
        Surface(
          color = LiveBadgeColor,
          shape = PillShape,
          modifier = Modifier.padding(8.dp).align(Alignment.TopStart),
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
          color = OverlayPlateColor,
          shape = PillShape,
          modifier = Modifier.padding(8.dp).align(Alignment.TopStart),
        ) {
          Text(
            text = "未开播",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp),
          )
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
      if (showCategoryBadge && !partition.isNullOrBlank()) {
        Surface(
          color = OverlayPlateColor,
          shape = PillShape,
          modifier = Modifier.padding(8.dp).align(Alignment.BottomEnd),
        ) {
          Text(
            text = partition,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface,
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
      //
      // The glyph sits on a frosted plate in both states. It used to be a black scrim when
      // unfollowed and a white disc when followed, which made one control look like two; a light
      // plate with a dark outline (and a brand-coloured fill once followed) reads as one button
      // whose state changed.
      IconButton(
        onClick = onToggleFollow,
        modifier = Modifier.align(Alignment.TopEnd).size(48.dp),
      ) {
        Icon(
          imageVector = if (followed) Icons.Filled.Star else Icons.Filled.StarBorder,
          contentDescription = if (followed) starLabels.second else starLabels.first,
          tint = if (followed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
          modifier = Modifier
            .size(28.dp)
            .background(OverlayPlateColor, CircleShape)
            .padding(4.dp),
        )
      }
    }

    Column(modifier = Modifier.padding(start = 2.dp, end = 2.dp, top = 8.dp)) {
      Text(
        text = streamer.title.ifBlank { streamer.streamerName },
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
        // Two lines, not one: the title is the one piece of text on the tile that says what the
        // stream is about, and ellipsizing "【预告】7日6点 T1 vs KC" to "【预告】7日6点 T1…"
        // told the user nothing.
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.padding(top = 4.dp),
      ) {
        Text(
          text = streamer.streamerName.ifBlank { "未知主播" },
          style = MaterialTheme.typography.labelMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.weight(1f, fill = false),
        )
        // The audience figure, moved here from the cover. `fill = false` on the name is what lets
        // a short name sit next to the number instead of being pushed to the far edge by the
        // weight, and the eye glyph is what still says *what* the number counts.
        if (streamer.viewerCount > 0) {
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
}

/**
 * The pale plate behind a glyph or a label that sits on a cover.
 *
 * A cover can be any colour, so an overlay needs its own background; the question is which one.
 * Black scrims were the old answer and they read as holes punched in the picture. This is the
 * surface colour at 92% — the same plate the platform's own badges use — which stays legible over
 * a bright thumbnail and disappears into a dark one instead of stamping a rectangle on it.
 */
private val OverlayPlateColor: Color
  @Composable get() = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f)

/**
 * A search result, as a wide row rather than a tile.
 *
 * The reference client has a second card shape for search: a small cover beside the text, not the
 * 16:9 tile the grid uses. The row carries two title lines plus the streamer and the audience as
 * ordinary text, because a row is read one after another while a tile has to be recognised at a
 * glance.
 *
 * **No container.** Round 104 dropped the pale rounded rectangle this row was painted on: in the
 * grid the same idea is a cover with text under it on the page, and a list of translucent bars was
 * a second way of saying "a row" on the same app. The cover and the text are the row; the gap
 * between rows is what separates them.
 *
 * Results are always from one platform, so there is no platform chip — the search field above
 * already says which platform these came from.
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
  Row(
    // Denser than the grid tile, because the trailing star and the viewer count have to share
    // one line with the name: at 320dp the row is 296dp wide, and every dp here comes out of
    // "一条小团团" (the name ellipsized two characters when the star grew to 48dp).
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(12.dp))
      .clickable(onClick = onClick)
      .padding(start = 2.dp, top = 6.dp, end = 2.dp, bottom = 6.dp),
    horizontalArrangement = Arrangement.spacedBy(10.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(
      modifier = Modifier
        .size(width = 104.dp, height = 68.dp)
        .clip(RoundedCornerShape(10.dp)),
    ) {
      CoverImage(streamer = streamer)

      // Only the LIVE label is scene-dependent: an offline row still says 未开播, wherever the
      // row is drawn, because that is news the list itself does not carry.
      if (!streamer.isLive || showLiveBadge) {
        Surface(
          color = if (streamer.isLive) LiveBadgeColor else OverlayPlateColor,
          shape = PillShape,
          modifier = Modifier.padding(5.dp).align(Alignment.TopStart),
        ) {
          Text(
            text = if (streamer.isLive) "LIVE" else "未开播",
            style = MaterialTheme.typography.labelSmall,
            color = if (streamer.isLive) Color.White else MaterialTheme.colorScheme.onSurface,
            fontWeight = if (streamer.isLive) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp),
          )
        }
      }
    }

    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
      Text(
        text = streamer.title.ifBlank { streamer.streamerName },
        style = MaterialTheme.typography.titleSmall,
        fontWeight = FontWeight.SemiBold,
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

/**
 * Deterministic gradient placeholder so a missing cover is still visually informative.
 *
 * **One family, four depths — not four hues.** The palette used to be a saturated dark teal, a
 * purple-to-rose, a teal-to-green and a purple-to-pink: four unrelated hues, any two of which could
 * land side by side in the grid. A screen of them reads as a bag of colours with the brand pink lost
 * among it. These four keep the same mid value and low chroma (dusty rose, slate, muted plum, sage),
 * so a grid looks composed and the pink accents on top of it are still the loudest thing on screen.
 */
@Composable
private fun CoverPlaceholder(seed: String) {
  val palette = listOf(
    Color(0xFF6E3B4A) to Color(0xFFA9868F),
    Color(0xFF3D4653) to Color(0xFF8593A5),
    Color(0xFF4E3B58) to Color(0xFF8F7F9C),
    Color(0xFF46503F) to Color(0xFF8C987F),
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
private val Int.absoluteValue: Int get() = if (this == Int.MIN_VALUE) 0 else kotlin.math.abs(this)
