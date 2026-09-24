package com.example.hevycompanion.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * App-wide Material 3 theme for the companion phone app.
 *
 * The companion previously used the framework `MaterialTheme {}` with no
 * `ColorScheme` override at all, so every primary / secondary surface fell
 * through to Material 3's default purple-ish blue regardless of the
 * launcher icon's identity. This file ships a hand-tuned dark scheme keyed
 * off [BrandOrange] (the valkyria launcher disc, #FE6A16) so the
 * "Save" / "Log In" CTAs, the muscle-selector chips, and the "✓ Logged in"
 * status text all render in the brand colour.
 *
 * Forces dark by default — every screen renders against [Color.Black] (set
 * explicitly in `MainActivity` to keep the reserved status-bar slot
 * seamless), so a light scheme would only ever be reached via a future
 * settings toggle.
 */

val BrandOrange = Color(0xFFFE6A16)
val BrandOrangeDim = Color(0xFFB54508)   // tap / pressed state
val BrandAmber = Color(0xFFFFC107)        // matches watch ChipPalette.WarmupAmber
val SurfaceDark = Color(0xFF111111)
val SurfaceDarkElevated = Color(0xFF1C1C1C)
val OnSurfaceWhite = Color(0xFFEDEDED)
val OnSurfaceMidGrey = Color(0xFF8E8E93)
val ErrorRed = Color(0xFFE34B37)

private val DarkColors = darkColorScheme(
    primary = BrandOrange,
    onPrimary = Color.Black,
    primaryContainer = BrandOrangeDim,
    onPrimaryContainer = OnSurfaceWhite,
    secondary = BrandAmber,
    onSecondary = Color.Black,
    background = Color.Black,
    onBackground = OnSurfaceWhite,
    surface = SurfaceDark,
    onSurface = OnSurfaceWhite,
    surfaceVariant = SurfaceDarkElevated,
    onSurfaceVariant = OnSurfaceMidGrey,
    error = ErrorRed,
    onError = Color.Black,
)

private val LightColors = lightColorScheme(
    primary = BrandOrange,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFD9C2),
    onPrimaryContainer = Color(0xFF3A1A00),
    secondary = BrandAmber,
    onSecondary = Color.Black,
    error = ErrorRed,
)

@Composable
fun ValkyriaTheme(
    useDarkTheme: Boolean = true, // app forces dark; pass isSystemInDarkTheme() if a setting is added later
    content: @Composable () -> Unit
) {
    val colors = if (useDarkTheme) DarkColors else LightColors
    MaterialTheme(colorScheme = colors, content = content)
}
