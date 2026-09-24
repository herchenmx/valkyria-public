package com.example.hevywatch.data.store

import android.content.Context

/**
 * Remembers the last base resistance (kg) the user entered per exercise template,
 * used to pre-fill the base prompt on the log screen. The value only *seeds* the
 * prompt — it's re-confirmed every session because the same exercise sits on a
 * different machine at a different gym (a squat machine's sled is 20/25/30 kg
 * depending on the make), so this is a convenience default, never authoritative.
 *
 * Keyed by exercise template id → base kg. Absent key = never entered (prompt has
 * no seed and falls back to an equipment default).
 */
class BaseResistanceStore(context: Context) {

    private val prefs = context.getSharedPreferences("base_resistance_prefs", Context.MODE_PRIVATE)

    /** Last base entered for [templateId], or null if never entered. */
    fun lastBase(templateId: String): Float? =
        if (prefs.contains(templateId)) prefs.getFloat(templateId, 0f) else null

    /** Record [kg] as the last base for [templateId] (clamped to a sane range). */
    fun setLastBase(templateId: String, kg: Float) {
        prefs.edit().putFloat(templateId, kg.coerceIn(0f, MAX_BASE_KG)).apply()
    }

    companion object {
        const val MAX_BASE_KG = 500f
    }
}
