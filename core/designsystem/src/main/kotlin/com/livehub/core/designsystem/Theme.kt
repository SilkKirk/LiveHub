package com.livehub.core.designsystem

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

/** Which palette the user selected in settings. */
enum class ThemeMode { System, Light, Dark }

// Brand palette: a vivid live-stream pink/red primary with a teal secondary.
private val BrandPink = Color(0xFFE23E57)
private val BrandPinkDark = Color(0xFFFF6B81)
private val BrandTeal = Color(0xFF0F7B8C)
private val BrandTealDark = Color(0xFF5FD3E4)
private val LiveRed = Color(0xFFF5222D)

/**
 * Neutrals.
 *
 * The surfaces were a flat near-white and a near-black, which is what made every screen read as
 * "default Material 3": cards, chips and the page background were all the same value, so nothing
 * looked like a layer. These are pulled apart deliberately — a cool page background, a whiter card
 * surface, and a chip surface that sits between them — so depth comes from tone rather than from
 * borders drawn on everything.
 */
private val BackgroundLight = Color(0xFFF6F5F8)
private val SurfaceLight = Color(0xFFFFFFFF)
private val SurfaceContainerLight = Color(0xFFF1EFF4)
private val SurfaceVariantLight = Color(0xFFEDEAF1)
private val OutlineLight = Color(0xFFE2DEE8)

private val BackgroundDark = Color(0xFF0F0E12)
private val SurfaceDark = Color(0xFF1A181F)
private val SurfaceContainerDark = Color(0xFF221F28)
private val SurfaceVariantDark = Color(0xFF2A2731)
private val OutlineDark = Color(0xFF3A3642)

private val LightScheme: ColorScheme = lightColorScheme(
  primary = BrandPink,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFFFDDE3),
  onPrimaryContainer = Color(0xFF41000D),
  secondary = BrandTeal,
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFC8EDF4),
  onSecondaryContainer = Color(0xFF00212A),
  background = BackgroundLight,
  onBackground = Color(0xFF1B1B1F),
  surface = SurfaceLight,
  onSurface = Color(0xFF1B1B1F),
  surfaceVariant = SurfaceVariantLight,
  onSurfaceVariant = Color(0xFF5A5560),
  surfaceContainer = SurfaceContainerLight,
  surfaceContainerHigh = Color(0xFFE9E6EF),
  surfaceContainerLow = Color(0xFFFBFAFD),
  outline = Color(0xFF9A94A3),
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
  secondaryContainer = Color(0xFF004F58),
  onSecondaryContainer = Color(0xFFC8EDF4),
  background = BackgroundDark,
  onBackground = Color(0xFFE7E3EA),
  surface = SurfaceDark,
  onSurface = Color(0xFFE7E3EA),
  surfaceVariant = SurfaceVariantDark,
  onSurfaceVariant = Color(0xFFB9B2C1),
  surfaceContainer = SurfaceContainerDark,
  surfaceContainerHigh = Color(0xFF2E2B36),
  surfaceContainerLow = Color(0xFF161419),
  outline = Color(0xFF6E6878),
  outlineVariant = OutlineDark,
  error = Color(0xFFFFB4AB),
  onError = Color(0xFF690005),
)

/** Colour used for the "LIVE" badge regardless of theme. */
val LiveBadgeColor: Color = LiveRed

/** Resolves the colour scheme for [mode] honouring Android 12+ dynamic colour. */
@Composable
fun liveHubColorScheme(
  mode: ThemeMode,
  /**
   * Material You. Off by default: the brand pink/teal is part of the identity and
   * wallpaper-derived colours made the covers and chips look washed out.
   */
  dynamicColor: Boolean = false,
): ColorScheme {
  val dark = when (mode) {
    ThemeMode.System -> isSystemInDarkTheme()
    ThemeMode.Light -> false
    ThemeMode.Dark -> true
  }
  val supportsDynamic = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
  return when {
    dynamicColor && supportsDynamic && dark ->
      dynamicDarkColorScheme(LocalContext.current)
    dynamicColor && supportsDynamic ->
      dynamicLightColorScheme(LocalContext.current)
    dark -> DarkScheme
    else -> LightScheme
  }
}

/** Root theme wrapper. */
@Composable
fun LiveHubTheme(
  mode: ThemeMode = ThemeMode.System,
  dynamicColor: Boolean = false,
  content: @Composable () -> Unit,
) {
  MaterialTheme(
    colorScheme = liveHubColorScheme(mode, dynamicColor),
    typography = LiveHubTypography,
    content = content,
  )
}
