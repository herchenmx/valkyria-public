package com.example.hevywatch.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * App-wide chip styling convention.
 *
 * **Title** (Chip `label`) is always `Color.White` regardless of state. State
 * is communicated via the background color, not the title color. Screen titles
 * use `MaterialTheme.colors.onPrimary` (BrandOrange) instead.
 *
 * **Subtitle** (Chip `secondaryLabel`) is `MaterialTheme.colors.onSecondary`
 * (mid-grey) by default, with two semantic overrides:
 *  - [PoGreen] when the subtitle describes a progressive-overload target weight
 *    (the weight has been auto-increased from last session).
 *  - [WarmupAmber] when the subtitle describes something surfaced by the
 *    warmup advisor — injected warmup sets, or a target weight suggested from
 *    a similar exercise. Amber rather than orange so it stays visually distinct
 *    from the BrandOrange `onPrimary` after the app rebranded to orange.
 *
 * **Status backgrounds** — chips that convey exercise completedness:
 *  - [StatusCompleteBg]   — all prescribed normal sets recorded (or an in-workout
 *    chip where every set is done).
 *  - [StatusInProgressBg] — some normal sets recorded but less than prescribed,
 *    or during a live workout an exercise with partial set completion.
 *  - [StatusMissingBg]    — the exercise was prescribed but not recorded at all
 *    (only applies on WorkoutDetailScreen's post-workout comparison).
 *
 * Chips that don't convey status (folders, recent workouts, refresh / resume
 * action chips, routine list, exercise rows on RoutineDetailScreen) keep the
 * default Wear secondary chip background.
 */
object ChipPalette {
    val PoGreen     = Color(0xFF4CAF50)
    val WarmupAmber = Color(0xFFFFC107)

    val StatusCompleteBg    = Color(0xFF1B3A1F)  // dark green tint  (dark theme)
    val StatusInProgressBg  = Color(0xFF1A2F3E)  // dark blue tint   (dark theme)
    val StatusMissingBg     = Color(0xFF3E1A1A)  // dark red tint    (dark theme)

    // Light-theme counterparts — pale tints that read under black (onSurface)
    // text the same way the dark tints read under white text. Selected per
    // theme via `HevyExtendedColors` / `LocalHevyColors`.
    val StatusCompleteBgLight    = Color(0xFFC8E6C9)  // pale green
    val StatusInProgressBgLight  = Color(0xFFBBDEFB)  // pale blue
    val StatusMissingBgLight     = Color(0xFFFFCDD2)  // pale red

    // ── Set-type accent colors ─────────────────────────────────────────────
    // Single source of truth for the dot/chip-subtitle colors we paint per
    // set type. Used by LogSetScreen's dot indicator, LogWorkoutScreen's
    // chip subtitle, and the rest-timer countdown hint.
    val SetNormal  = Color.White
    val SetWarmup  = WarmupAmber
    val SetFailure = Color(0xFFEF5350)
    val SetDropset = Color(0xFFCE93D8)

    // ── Other accent foregrounds ────────────────────────────────────────────
    // Scheme-independent: these saturated mid-tones read on both the dark and
    // light canvas, so they are NOT flipped between themes. Centralised here so
    // the screens stop hardcoding raw hex.
    val ConfirmGreen  = Color(0xFF66BB6A)  // PO-applied weight emphasis: value picker, rest-timer next-weight, confirm ✓
    val DeltaPositive = PoGreen            // RoutineDetail progress — weight up vs last session (#4CAF50)
    val DeltaNegative = Color(0xFFF44336)  // RoutineDetail progress — weight down vs last session
    val ResumeChipBg  = Color(0xFF2E7D32)  // "Resume Workout" chip background (white label on green, both themes)

    // ── PR badge (theme-dependent) ──────────────────────────────────────────
    // CongratsScreen's per-exercise personal-record pill. Dark: gold-on-brown.
    // Light: dark-gold on a pale-gold fill. Selected via HevyExtendedColors.
    val PrBadgeBg       = Color(0xFF3E2A1A)  // warm brown   (dark)
    val PrBadgeFg       = Color(0xFFFFC857)  // brand gold   (dark)
    val PrBadgeBgLight  = Color(0xFFFFF1CC)  // pale gold    (light)
    val PrBadgeFgLight  = Color(0xFF7A5A00)  // deep gold    (light)

    // ── Connectivity / status banners (theme-dependent) ─────────────────────
    // Dark scheme: dark saturated fill + pale text. Light scheme inverts to a
    // pale fill + dark text so the alert reads on a white canvas. Selected via
    // HevyExtendedColors.
    val BannerOfflineBg = Color(0xFF5A1A1A); val BannerOfflineFg = Color(0xFFFFD2D2)
    val BannerWarnBg    = Color(0xFF5A3A1A); val BannerWarnFg    = Color(0xFFFFE4B5)
    val BannerInfoBg    = Color(0xFF1A3A5A); val BannerInfoFg    = Color(0xFFB5D4FF)

    val BannerOfflineBgLight = Color(0xFFFFDAD6); val BannerOfflineFgLight = Color(0xFF5A1A1A)
    val BannerWarnBgLight    = Color(0xFFFFE8C7); val BannerWarnFgLight    = Color(0xFF5A3A1A)
    val BannerInfoBgLight    = Color(0xFFD6E8FF); val BannerInfoFgLight    = Color(0xFF1A3A5A)
}

/** Set-type → on-screen color. Centralized so re-theming is single-site. */
fun com.example.hevywatch.data.model.SetType.color(): Color = when (this) {
    com.example.hevywatch.data.model.SetType.NORMAL  -> ChipPalette.SetNormal
    com.example.hevywatch.data.model.SetType.WARMUP  -> ChipPalette.SetWarmup
    com.example.hevywatch.data.model.SetType.FAILURE -> ChipPalette.SetFailure
    com.example.hevywatch.data.model.SetType.DROPSET -> ChipPalette.SetDropset
}
