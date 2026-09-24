package com.example.hevywatch.util

import com.example.hevycore.exercise.AssistedBodyweight
import com.example.hevywatch.data.model.ActiveExercise

object FormatUtils {

    fun formatDuration(startTimeMs: Long, asOfMs: Long = System.currentTimeMillis()): String {
        val elapsed = ((asOfMs - startTimeMs) / 1000L).coerceAtLeast(0)
        val h = elapsed / 3600
        val m = (elapsed % 3600) / 60
        val s = elapsed % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s)
        else "%d:%02d".format(m, s)
    }

    /**
     * Total volume across the workout's completed sets. When [routineId] matches the
     * assisted-bodyweight routine, sets of the assisted-machine exercises contribute
     * `(bodyweightKg − weight_kg) × reps` instead of the raw product, since their logged
     * kg represents stack assistance rather than load moved.
     */
    fun formatVolume(
        exercises: List<ActiveExercise>,
        routineId: String? = null,
        bodyweightKg: Float = 0f
    ): String {
        val applyAssisted = routineId == AssistedBodyweight.ROUTINE_ID
        val totalKg = exercises.sumOf { ex ->
            val isAssistedHere = applyAssisted &&
                AssistedBodyweight.isAssisted(ex.exerciseTemplateId)
            ex.sets.filter { it.completed }.sumOf { set ->
                val w = (set.weightKg ?: 0f).toDouble()
                val r = (set.reps ?: 0).toDouble()
                val effective = if (isAssistedHere) {
                    (bodyweightKg.toDouble() - w).coerceAtLeast(0.0)
                } else w
                effective * r
            }
        }
        return "%.0f kg".format(totalKg)
    }

    fun setCoordinates(setIndex: Int, totalSets: Int) = "${setIndex + 1}/$totalSets"

    /**
     * Single source of truth for displaying a kg weight on the watch UI.
     *
     * - [decimals] = 1 by default, the rest of the app's convention.
     * - [compact] = true joins the unit without a space ("12.5kg") for tight
     *   chip subtitles; default false yields "12.5 kg" with a separator.
     * - Negative or zero values are formatted normally; callers that want to
     *   omit a zero weight should branch before calling.
     *
     * Locale-default (e.g. comma decimal in some EU locales). Kept that way
     * intentionally because this is for display only — request bodies and
     * share text use Locale.US in their own formatters.
     */
    fun formatKg(kg: Float, decimals: Int = 1, compact: Boolean = false): String {
        val num = "%.${decimals}f".format(kg)
        return if (compact) "${num}kg" else "$num kg"
    }

    /** Drops decimals when the weight is integer-valued; used by the tile and
     *  the chip-subtitle "suggested" hints where two characters of horizontal
     *  space matter. */
    fun formatKgSmart(kg: Float, compact: Boolean = false): String {
        val intLike = kg == kg.toLong().toFloat()
        return formatKg(kg, decimals = if (intLike) 0 else 1, compact = compact)
    }

    /** Coarse human-readable relative time. Drives the freshness caption on
     *  screens that paint from cache before a background refresh. Format:
     *  `just now` / `N min[s] ago` / `N hr[s] ago` / `yesterday` / `N days
     *  ago`. Granularity intentionally low — no seconds, no two-digit
     *  precision past the bucket boundary. */
    fun relativeTimeAgo(epochMs: Long, nowMs: Long = System.currentTimeMillis()): String {
        val diffSec = ((nowMs - epochMs) / 1000L).coerceAtLeast(0)
        return when {
            diffSec < 60 -> "just now"
            diffSec < 3_600 -> {
                val mins = diffSec / 60
                "$mins min${if (mins == 1L) "" else "s"} ago"
            }
            diffSec < 86_400 -> {
                val hrs = diffSec / 3_600
                "$hrs hr${if (hrs == 1L) "" else "s"} ago"
            }
            diffSec < 172_800 -> "yesterday"
            else -> "${diffSec / 86_400} days ago"
        }
    }
}
