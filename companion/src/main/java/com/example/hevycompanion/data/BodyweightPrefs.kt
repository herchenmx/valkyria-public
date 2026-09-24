package com.example.hevycompanion.data

import android.content.Context

/**
 * SharedPreferences-backed bodyweight, used by the Recents progressive-overload
 * and warmup advisors for counter-weight *assisted* exercises (dips, pull-ups),
 * where effective work is `bodyweight − logged_kg`.
 *
 * The watch reads the same value from its Settings screen and defaults to
 * [DEFAULT_KG]; the companion has no settings screen, so the value is edited
 * inline on the home screen ([com.example.hevycompanion.MainActivity]). Only the
 * four assisted-machine exercises depend on it — for every other exercise the
 * advisors ignore bodyweight entirely.
 */
class BodyweightPrefs(context: Context) {
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** The user's bodyweight in kg. Defaults to [DEFAULT_KG] (the watch default). */
    var bodyweightKg: Float
        get() = prefs.getFloat(KEY_BODYWEIGHT, DEFAULT_KG)
        set(value) = prefs.edit().putFloat(KEY_BODYWEIGHT, value).apply()

    companion object {
        private const val PREFS_NAME = "hevy_bodyweight"
        private const val KEY_BODYWEIGHT = "bodyweight_kg"
        /** Matches the watch's default bodyweight (UserProfileStore). */
        const val DEFAULT_KG = 57f
    }
}
