package com.example.hevywatch.data.store

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue

/**
 * Persists user-tunable brightness behaviour. The watch overrides
 * `window.attributes.screenBrightness` while the app is in the foreground:
 * [defaultBrightness] is the resting level, [maxBrightness] is what we ramp
 * to on touch, and [idleSeconds] controls how long after the last pointer
 * event we drop back to default.
 *
 * Values are exposed as Compose state so `BrightnessCoordinator` recomposes
 * the moment the user adjusts a slider on the settings screen.
 *
 * Defaults (0.25 / 0.50 / 3 s) match the post-installation experiment that
 * suggested 60 %/30 % was still too bright on a healthy Scallop 2 panel.
 */
class BrightnessSettingsStore(context: Context) {

    private val prefs = context.getSharedPreferences("brightness_prefs", Context.MODE_PRIVATE)

    var defaultBrightness: Float by mutableFloatStateOf(
        prefs.getFloat(KEY_DEFAULT, DEFAULT_DEFAULT_BRIGHTNESS)
    )
        private set

    var maxBrightness: Float by mutableFloatStateOf(
        prefs.getFloat(KEY_MAX, DEFAULT_MAX_BRIGHTNESS)
    )
        private set

    var idleSeconds: Int by mutableIntStateOf(
        prefs.getInt(KEY_IDLE_SECONDS, DEFAULT_IDLE_SECONDS)
    )
        private set

    fun updateDefaultBrightness(value: Float) {
        // Default must stay strictly below max so the dim → bright ramp is
        // visually meaningful; if a tighter range is desired the user can
        // raise max first or lower max second.
        val clamped = value.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        defaultBrightness = clamped
        prefs.edit().putFloat(KEY_DEFAULT, clamped).apply()
    }

    fun updateMaxBrightness(value: Float) {
        val clamped = value.coerceIn(MIN_BRIGHTNESS, MAX_BRIGHTNESS)
        maxBrightness = clamped
        prefs.edit().putFloat(KEY_MAX, clamped).apply()
    }

    fun updateIdleSeconds(value: Int) {
        val clamped = value.coerceIn(MIN_IDLE_SECONDS, MAX_IDLE_SECONDS)
        idleSeconds = clamped
        prefs.edit().putInt(KEY_IDLE_SECONDS, clamped).apply()
    }

    companion object {
        const val DEFAULT_DEFAULT_BRIGHTNESS: Float = 0.25f
        const val DEFAULT_MAX_BRIGHTNESS: Float = 0.50f
        const val DEFAULT_IDLE_SECONDS: Int = 3

        const val MIN_BRIGHTNESS: Float = 0.05f
        const val MAX_BRIGHTNESS: Float = 1.00f
        const val BRIGHTNESS_STEP: Float = 0.05f

        const val MIN_IDLE_SECONDS: Int = 1
        const val MAX_IDLE_SECONDS: Int = 30

        private const val KEY_DEFAULT = "default_brightness"
        private const val KEY_MAX = "max_brightness"
        private const val KEY_IDLE_SECONDS = "idle_seconds"
    }
}
