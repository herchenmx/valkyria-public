package com.example.hevywatch.ui.theme

import androidx.compose.ui.graphics.Color

// App palette — dark theme, with the orange `BrandOrange` sampled from the
// valkyria launcher icon (#FE6A16) used as the on-primary accent across
// titles, the active rest-timer indicator, and the post-workout heading.
val HevyWhite = Color(0xFFFFFFFF)
val HevyBlack = Color(0xFF000000)
val BrandOrange = Color(0xFFFE6A16)         // primary accent — used as onPrimary
val HevyMidGrey = Color(0xFF8E8E93)         // muted/secondary text — legible on black
val HevyRed = Color(0xFFE34B37)             // error color

// ── Light-theme greyscale ───────────────────────────────────────────────────
// The light scheme is a greyscale inversion of the dark one: black backgrounds
// become white, white text becomes black, and the light-grey muted text
// (HevyMidGrey) becomes a dark grey. Accent colours are shared across both
// themes and stay in the block above. See `HevyWatchTheme` / `LightColorPalette`.
val LightBackground = Color(0xFFFFFFFF)     // was HevyBlack — full-screen canvas
val LightSurface = Color(0xFFF2F2F2)        // chip/button fill — a hair off-white so
                                            // chips separate from the white canvas
                                            // (mirrors the dark scheme's black-on-black
                                            // chips having their own subtle fill)
val HevyDarkGrey = Color(0xFF5E5E63)        // muted/secondary text — legible on white
