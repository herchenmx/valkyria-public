package com.example.hevywatch.data.store

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** The two app-wide colour schemes the user can pick on the Settings screen. */
enum class ThemeMode {
    /** Original scheme — black backgrounds, white text, light-grey muted text. */
    DARK,

    /** Inverted greyscale — white backgrounds, black text, dark-grey muted text.
     *  Accent colours (orange / green / amber / red) are unchanged. */
    LIGHT;

    companion object {
        fun fromName(name: String?): ThemeMode =
            entries.firstOrNull { it.name == name } ?: DARK
    }
}

/**
 * Persists the user's chosen [ThemeMode]. Exposed as Compose state so the
 * top-level [com.example.hevywatch.ui.theme.HevyWatchTheme] recomposes the
 * whole tree the instant the user flips the toggle on the Settings screen.
 *
 * Defaults to [ThemeMode.DARK] so existing installs see no change until they
 * opt in.
 */
class ThemeSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("theme_prefs", Context.MODE_PRIVATE)

    var themeMode: ThemeMode by mutableStateOf(
        ThemeMode.fromName(prefs.getString(KEY_THEME, null))
    )
        private set

    fun updateThemeMode(mode: ThemeMode) {
        themeMode = mode
        prefs.edit().putString(KEY_THEME, mode.name).apply()
    }

    companion object {
        private const val KEY_THEME = "theme_mode"
    }
}
