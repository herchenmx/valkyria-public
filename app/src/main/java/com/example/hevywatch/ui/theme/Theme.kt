package com.example.hevywatch.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.wear.compose.material.Colors
import androidx.wear.compose.material.LocalContentColor
import androidx.wear.compose.material.MaterialTheme
import com.example.hevywatch.data.store.ThemeMode

// ── Dark scheme ─────────────────────────────────────────────────────────────
// Matches official hevyColorPalette from decompiled ColorKt.java.
private val DarkColorPalette = Colors(
    primary = HevyWhite,
    primaryVariant = HevyWhite,
    secondary = HevyWhite,
    secondaryVariant = HevyWhite,
    background = HevyBlack,
    surface = HevyBlack,
    error = HevyRed,
    onPrimary = BrandOrange,
    onSecondary = HevyMidGrey,
    onBackground = HevyWhite,
    onSurface = HevyWhite,
    onError = HevyWhite,
    onSurfaceVariant = HevyMidGrey
)

// ── Light scheme ────────────────────────────────────────────────────────────
// Greyscale inversion of the dark scheme: white canvas, black text, dark-grey
// muted text. Accents (onPrimary = BrandOrange, error = HevyRed) are shared.
private val LightColorPalette = Colors(
    primary = HevyBlack,
    primaryVariant = HevyBlack,
    secondary = HevyBlack,
    secondaryVariant = HevyBlack,
    background = LightBackground,
    surface = LightSurface,
    error = HevyRed,
    onPrimary = BrandOrange,
    onSecondary = HevyDarkGrey,
    onBackground = HevyBlack,
    onSurface = HevyBlack,
    onError = HevyWhite,
    onSurfaceVariant = HevyDarkGrey
)

/**
 * Theme-dependent colours that don't have a slot in Wear's [Colors]. These are
 * the semantic colours that must flip between schemes but aren't a plain
 * foreground/background: the exercise-status chip tints, the "normal set"
 * indicator (white on dark, black on light), the PR badge pill, and the
 * connectivity/alert banners. Scheme-independent accent foregrounds
 * (PoGreen, WarmupAmber, SetFailure, …) stay in [ChipPalette].
 *
 * Provided by [HevyWatchTheme] and read via [LocalHevyColors] / [hevyExtendedColors].
 */
@Immutable
data class HevyExtendedColors(
    val statusCompleteBg: Color,
    val statusInProgressBg: Color,
    val statusMissingBg: Color,
    /** Indicator/value colour for a NORMAL set — the only [SetType] colour that
     *  is plain foreground rather than an accent, so it flips with the theme. */
    val setNormal: Color,
    val prBadgeBg: Color,
    val prBadgeFg: Color,
    val bannerOfflineBg: Color,
    val bannerOfflineFg: Color,
    val bannerWarnBg: Color,
    val bannerWarnFg: Color,
    val bannerInfoBg: Color,
    val bannerInfoFg: Color,
)

internal val DarkExtendedColors = HevyExtendedColors(
    statusCompleteBg = ChipPalette.StatusCompleteBg,
    statusInProgressBg = ChipPalette.StatusInProgressBg,
    statusMissingBg = ChipPalette.StatusMissingBg,
    setNormal = HevyWhite,
    prBadgeBg = ChipPalette.PrBadgeBg,
    prBadgeFg = ChipPalette.PrBadgeFg,
    bannerOfflineBg = ChipPalette.BannerOfflineBg,
    bannerOfflineFg = ChipPalette.BannerOfflineFg,
    bannerWarnBg = ChipPalette.BannerWarnBg,
    bannerWarnFg = ChipPalette.BannerWarnFg,
    bannerInfoBg = ChipPalette.BannerInfoBg,
    bannerInfoFg = ChipPalette.BannerInfoFg,
)

internal val LightExtendedColors = HevyExtendedColors(
    statusCompleteBg = ChipPalette.StatusCompleteBgLight,
    statusInProgressBg = ChipPalette.StatusInProgressBgLight,
    statusMissingBg = ChipPalette.StatusMissingBgLight,
    setNormal = HevyBlack,
    prBadgeBg = ChipPalette.PrBadgeBgLight,
    prBadgeFg = ChipPalette.PrBadgeFgLight,
    bannerOfflineBg = ChipPalette.BannerOfflineBgLight,
    bannerOfflineFg = ChipPalette.BannerOfflineFgLight,
    bannerWarnBg = ChipPalette.BannerWarnBgLight,
    bannerWarnFg = ChipPalette.BannerWarnFgLight,
    bannerInfoBg = ChipPalette.BannerInfoBgLight,
    bannerInfoFg = ChipPalette.BannerInfoFgLight,
)

/** Current scheme's extended colours. Defaults to the dark set so previews /
 *  composables outside [HevyWatchTheme] still resolve. */
val LocalHevyColors = staticCompositionLocalOf { DarkExtendedColors }

/** Convenience accessor mirroring `MaterialTheme.colors`. */
val hevyExtendedColors: HevyExtendedColors
    @Composable @ReadOnlyComposable get() = LocalHevyColors.current

@Composable
fun HevyWatchTheme(
    themeMode: ThemeMode = ThemeMode.DARK,
    content: @Composable () -> Unit
) {
    val dark = themeMode == ThemeMode.DARK
    val colors = if (dark) DarkColorPalette else LightColorPalette
    val extended = if (dark) DarkExtendedColors else LightExtendedColors
    MaterialTheme(
        colors = colors,
        typography = WearTypography,
    ) {
        // Wear's default LocalContentColor is a fixed light value, so any bare
        // Text/Icon (no explicit colour, not inside a Chip/Button/Card that sets
        // its own content colour) would be invisible on the light scheme's white
        // canvas. Pin it to the theme foreground so the default tracks the scheme
        // — this is the single root-cause fix for all "white-on-white" text.
        // (Components like Chip/Button still override this for their own content.)
        CompositionLocalProvider(
            LocalHevyColors provides extended,
            LocalContentColor provides colors.onBackground,
        ) {
            content()
        }
    }
}
