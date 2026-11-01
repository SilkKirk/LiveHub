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
private val SurfaceLight = Color(0xFFFDFBFF)
private val SurfaceDark = Color(0xFF141218)

private val LightScheme: ColorScheme = lightColorScheme(
  primary = BrandPink,
  onPrimary = Color.White,
  primaryContainer = Color(0xFFFFD9DE),
  onPrimaryContainer = Color(0xFF40000F),
  secondary = BrandTeal,
  onSecondary = Color.White,
  secondaryContainer = Color(0xFFB8EBF5),
  onSecondaryContainer = Color(0xFF001F24),
  background = SurfaceLight,
  onBackground = Color(0xFF1B1B1F),
  surface = SurfaceLight,
  onSurface = Color(0xFF1B1B1F),
  surfaceVariant = Color(0xFFF2DEE0),
  onSurfaceVariant = Color(0xFF524345),
  error = Color(0xFFBA1A1A),
  onError = Color.White,
)

private val DarkScheme: ColorScheme = darkColorScheme(
  primary = BrandPinkDark,
  onPrimary = Color(0xFF680017),
  primaryContainer = Color(0xFF930024),
  onPrimaryContainer = Color(0xFFFFD9DE),
  secondary = BrandTealDark,
  onSecondary = Color(0xFF00363D),
  secondaryContainer = Color(0xFF004F58),
  onSecondaryContainer = Color(0xFFB8EBF5),
  background = SurfaceDark,
  onBackground = Color(0xFFE6E1E5),
  surface = SurfaceDark,
  onSurface = Color(0xFFE6E1E5),
  surfaceVariant = Color(0xFF524345),
  onSurfaceVariant = Color(0xFFD7C1C4),
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
