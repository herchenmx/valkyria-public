package com.example.hevywatch.data.store

import android.content.Context
import java.time.Year

/**
 * Persists user demographics (birth year, biological sex) needed by HR-driven
 * energy-expenditure formulas (e.g. Keytel 2005). Stored separately from
 * [BodyweightStore] so bodyweight stays focused on assisted-bodyweight volume
 * math.
 *
 * `Sex` is two-option-plus-unspecified because the Keytel regression only fits
 * male/female equations — there is no neutral baseline to fall back to. We
 * keep `UNSPECIFIED` as the persisted default so a future formula caller can
 * short-circuit (return null calories) until the user has actually picked.
 */
class UserProfileStore(context: Context) {

    private val prefs = context.getSharedPreferences("user_profile_prefs", Context.MODE_PRIVATE)

    var birthYear: Int
        get() = prefs.getInt(KEY_BIRTH_YEAR, DEFAULT_BIRTH_YEAR)
        set(value) {
            val clamped = value.coerceIn(MIN_BIRTH_YEAR, currentMaxBirthYear())
            prefs.edit().putInt(KEY_BIRTH_YEAR, clamped).apply()
        }

    var sex: Sex
        get() = Sex.fromOrdinal(prefs.getInt(KEY_SEX_ORDINAL, DEFAULT_SEX.ordinal))
        set(value) {
            prefs.edit().putInt(KEY_SEX_ORDINAL, value.ordinal).apply()
        }

    /** Whether the user has opted into per-minute HR sampling during workouts.
     *  Default false — the watch must not register the PPG sensor (or prompt
     *  for BODY_SENSORS) unless the user has explicitly turned this on. */
    var heartRateEnabled: Boolean
        get() = prefs.getBoolean(KEY_HEART_RATE_ENABLED, DEFAULT_HEART_RATE_ENABLED)
        set(value) {
            prefs.edit().putBoolean(KEY_HEART_RATE_ENABLED, value).apply()
        }

    /** Age in completed years against the given reference year (default: now). */
    fun ageAt(referenceYear: Int = Year.now().value): Int = referenceYear - birthYear

    companion object {
        const val DEFAULT_BIRTH_YEAR = 1990
        const val MIN_BIRTH_YEAR = 1900
        /** Floor on age, enforced as a sanity check (not COPPA). */
        const val MIN_AGE_YEARS = 13
        val DEFAULT_SEX: Sex = Sex.UNSPECIFIED
        const val DEFAULT_HEART_RATE_ENABLED = false

        private const val KEY_BIRTH_YEAR = "birth_year"
        private const val KEY_SEX_ORDINAL = "sex_ordinal"
        private const val KEY_HEART_RATE_ENABLED = "heart_rate_enabled"

        fun currentMaxBirthYear(): Int = Year.now().value - MIN_AGE_YEARS
    }
}

enum class Sex {
    MALE, FEMALE, UNSPECIFIED;

    companion object {
        fun fromOrdinal(o: Int): Sex = entries.getOrNull(o) ?: UNSPECIFIED
    }
}
