package com.livehub.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * The app's corner scale, handed to `MaterialTheme` so components read radii from one place.
 *
 * `MaterialTheme` owns three systems — colour, type and shape — and this app only ever supplied
 * the first two. Every corner was a literal at the call site (`RoundedCornerShape(12.dp)` on a
 * card, `10.dp` on the 最近看过 tile, `20.dp` on a panel, `28.dp` on the dialog), which made the
 * shape scale unreadable as a scale: two surfaces that are the same kind of thing could carry two
 * different radii and nothing would notice.
 *
 * The values are the radii the app already draws, not new ones:
 *
 *  * `medium` 12dp — cards (`Card` reads `shapes.medium` through `CardDefaults.shape`), so the
 *    card corner is now a theme value rather than a number next to a `Card` call;
 *  * `large` 20dp and `extraLarge` 28dp — the in-window sheets and the matching-code dialog;
 *  * `small` 10dp — the small cover tiles; `extraSmall` 6dp — text fields, which Material draws at
 *    4dp by default. That 4 → 6 is the one deliberate change: the fields are the only component
 *    whose corner the theme actually moved, and it is what makes them read as the same family as
 *    the cards they sit in.
 */
val LiveHubShapes: Shapes = Shapes(
  extraSmall = RoundedCornerShape(6.dp),
  small = RoundedCornerShape(10.dp),
  medium = RoundedCornerShape(12.dp),
  large = RoundedCornerShape(20.dp),
  extraLarge = RoundedCornerShape(28.dp),
)
