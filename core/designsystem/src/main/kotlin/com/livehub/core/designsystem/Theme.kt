package com.livehub.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/** Which palette the user selected in settings. */
enum class ThemeMode { System, Light, Dark }

// Brand palette: a vivid live-stream pink/red primary with a teal secondary.
private val BrandPink = Color(0xFFE23E57)
private val BrandPinkDark = Color(0xFFFF6B81)
private val BrandTeal = Color(0xFF0F7B8C)
private val BrandTealDark = Color(0xFF5FD3E4)
private val LiveRed = Color(0xFFF5222D)

/**
 * Neutrals — **true neutrals, not tinted ones**.
 *
 * These used to carry a lilac cast (#F6F5F8, #EDEAF1, #E2DEE8): every screen had a slightly purple
 * page, slightly purple chips and slightly purple borders. Against a vivid brand pink that cast does
 * real damage — the pink is meant to be the only saturated thing on screen, and a second weak hue
 * everywhere else makes the app read as "some purple-ish theme" rather than as a pink accent on a
 * neutral canvas.
 *
 * So the greys are neutral to the byte — equal R, G and B for the page, the containers and the
 * borders, with one to three points of step between layers. Depth comes from those steps plus the
 * white card surface, and the brand colour is the only colour.
 */
private val BackgroundLight = Color(0xFFF6F6F8)
private val SurfaceLight = Color(0xFFFFFFFF)
private val SurfaceContainerLight = Color(0xFFF1F1F4)
private val SurfaceVariantLight = Color(0xFFEDEDF1)
private val OutlineLight = Color(0xFFE1E1E6)

private val BackgroundDark = Color(0xFF0D0D10)
private val SurfaceDark = Color(0xFF17171C)
private val SurfaceContainerDark = Color(0xFF1E1E24)
private val SurfaceVariantDark = Color(0xFF26262E)
private val OutlineDark = Color(0xFF3A3A44)

private val LightScheme: ColorScheme = lightColorScheme(
  primary = BrandPink,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFFFDDE3),
  onPrimaryContainer = Color(0xFF41000D),
  secondary = BrandTeal,
  onSecondary = Color.White,
  // `secondaryContainer` is not "a second brand colour" in practice — it is the fill Material 3
  // gives a *selected* `FilterChip`, and the chips are the app's main selection control. As the
  // teal pair they put a cyan chip under a red tab indicator, beside a red LIVE badge and a red
  // follow star; the pair now shares the primary container family so selection reads as one colour
  // across the app. `secondary` itself stays teal: this is about what a selected chip is filled
  // with, not about the palette's second hue.
  secondaryContainer = Color(0xFFFFDDE3),
  onSecondaryContainer = Color(0xFF41000D),
  background = BackgroundLight,
  onBackground = Color(0xFF18181B),
  surface = SurfaceLight,
  onSurface = Color(0xFF18181B),
  surfaceVariant = SurfaceVariantLight,
  onSurfaceVariant = Color(0xFF5B5B66),
  surfaceContainer = SurfaceContainerLight,
  surfaceContainerHigh = Color(0xFFE9E9ED),
  // Two container tokens, and the surfaces that read them are not the two you would guess: Material's
  // filled `Card` (which every card on 设置 and 同步 is) takes `surfaceContainerHighest`, while
  // `surfaceContainerLow` is what `ElevatedCard` and `ModalBottomSheet` use. Neither was set here, so
  // both fell back to the M3 baseline — a purple-grey (#E6E0E9) belonging to no palette of ours, and
  // close enough to the page background that a column of cards read as one flat sheet with text
  // floating on it.
  //
  // Both are the card surface now: the page is `BackgroundLight` (a cool near-white) and the cards
  // are pure white, so a card is a thing sitting *on* the page rather than a tint of it — the same
  // relationship the room tiles already use (`StreamerCard` is `surface` over `background`).
  surfaceContainerLow = SurfaceLight,
  surfaceContainerHighest = SurfaceLight,
  outline = Color(0xFF8E8E99),
  outlineVariant = OutlineLight,
  error = Color(0xFFBA1A1A),
  onError = Color.White,
)

private val DarkScheme: ColorScheme = darkColorScheme(
  primary = BrandPinkDark,
  onPrimary = Color(0xFF4A0011),
  primaryContainer = Color(0xFF8A0022),
  onPrimaryContainer = Color(0xFFFFDDE3),
  secondary = BrandTealDark,
  onSecondary = Color(0xFF00363D),
  // Same reasoning as the light scheme: this is the selected-chip fill, held to the primary
  // container family so a chip does not go cyan while every other selected thing goes brand red.
  secondaryContainer = Color(0xFF8A0022),
  onSecondaryContainer = Color(0xFFFFDDE3),
  background = BackgroundDark,
  onBackground = Color(0xFFE8E8EC),
  surface = SurfaceDark,
  onSurface = Color(0xFFE8E8EC),
  surfaceVariant = SurfaceVariantDark,
  onSurfaceVariant = Color(0xFFAFAFB9),
  surfaceContainer = SurfaceContainerDark,
  surfaceContainerHigh = Color(0xFF26262E),
  // Same pair as the light scheme: cards are one step above the page, not a tint of it.
  surfaceContainerLow = SurfaceDark,
  surfaceContainerHighest = SurfaceDark,
  outline = Color(0xFF6B6B78),
  outlineVariant = OutlineDark,
  error = Color(0xFFFFB4AB),
  onError = Color(0xFF690005),
)

/** Colour used for the "LIVE" badge regardless of theme. */
val LiveBadgeColor: Color = LiveRed

/**
 * Resolves the colour scheme for [mode].
 *
 * Material You (dynamic colour) used to be a parameter here, threaded from a `dynamicColor` field
 * in `UserSettings` that **nothing ever read**: the setting existed, defaulted to `true`, and the
 * whole pipeline was unreachable because `LiveHubTheme` was only ever called with `mode`. Both are
 * gone rather than left as a switch nobody can flip — and the brand palette is deliberate anyway
 * (the comment in the old parameter said wallpaper-derived colours made covers and chips look
 * washed out).
 */
@Composable
fun liveHubColorScheme(mode: ThemeMode): ColorScheme {
  val dark = when (mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
  }
  return if (dark) DarkScheme else LightScheme
}

/** Root theme wrapper. */
@Composable
fun LiveHubTheme(
  mode: ThemeMode = ThemeMode.System,
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = liveHubColorScheme(mode),
    typography = LiveHubTypography,
    content = content,
  )
}