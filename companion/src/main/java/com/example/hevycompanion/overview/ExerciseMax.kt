package com.example.hevycompanion.overview

import com.example.hevycompanion.data.ExerciseHistoryEntry
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Pure scoring for the Strength Overview feature.
 *
 * "Highest KG managed" for an exercise is defined (per the user's spec) as:
 *
 *  - within a single workout (a set of [ExerciseHistoryEntry] sharing a
 *    `workout_id`), look at the *normal* sets that hit at least [MIN_REPS] reps;
 *  - if there are at least [MIN_SETS] such sets, that workout *qualifies*, and
 *    its "managed weight" is the **average** of those sets' weights (so a
 *    20 / 22.5 / 25 kg session counts as 22.5 kg);
 *  - the exercise's headline number is the highest managed weight across all
 *    qualifying workouts, tagged with that workout's start time.
 *
 * This mirrors the watch's progressive-overload base (a rolling average across
 * qualifying sets) but collapsed to a single best-ever figure. Warmup / dropset
 * / failure sets and any set below the rep floor are ignored.
 */
object ExerciseMax {

    const val MIN_REPS = 15
    const val MIN_SETS = 3
    private const val NORMAL_SET = "normal"

    data class Best(
        /** Average kg of the qualifying sets in the best workout. */
        val weightKg: Float,
        /** ISO-8601 `workout_start_time` of that workout (may be null). */
        val workoutStartTime: String?,
        /**
         * Best Epley one-rep-max estimate across the qualifying sets of that
         * same workout, or null when no set carried usable weight+reps.
         * See [epley1rm] for the accuracy caveat.
         */
        val estimated1rmKg: Float?,
    )

    /**
     * Epley one-rep-max estimate: `w × (1 + reps/30)`.
     *
     * Caveat worth knowing: every 1RM formula is fitted on low-rep sets
     * (roughly 1–10) and over-estimates as reps climb. This app's whole
     * training model is 15-rep sets, which sits past that range — so treat
     * the number as a *normalising* figure for comparing exercises against
     * each other over time, not as a weight to actually attempt. Epley
     * rather than Brzycki because Brzycki's `36/(37−r)` diverges sharply
     * above ~12 reps (at 15 reps it reads ~9 % higher than Epley, and the
     * gap widens).
     *
     * A single rep short-circuits to the weight itself: the raw Epley
     * expression returns `1.033 × w` at `r = 1`, which is nonsense — a set of
     * one IS the one-rep max.
     *
     * Returns null for non-positive weight or reps.
     */
    fun epley1rm(weightKg: Float, reps: Int): Float? {
        if (weightKg <= 0f || reps <= 0) return null
        if (reps == 1) return weightKg
        return weightKg * (1f + reps / 30f)
    }

    /** Highest qualifying average across the exercise's whole history, or null
     *  if no single workout ever cleared the [MIN_SETS]×[MIN_REPS] bar. */
    fun highestQualifying(history: List<ExerciseHistoryEntry>): Best? {
        var best: Best? = null
        for ((_, entries) in history.groupBy { it.workoutId }) {
            val qualifying = entries.filter {
                it.setType == NORMAL_SET && (it.reps ?: 0) >= MIN_REPS && it.weightKg != null
            }
            if (qualifying.size < MIN_SETS) continue
            val weights = qualifying.mapNotNull { it.weightKg }
            val avg = weights.sum() / qualifying.size
            if (best == null || avg > best.weightKg) {
                // Estimate from the single best set of this workout rather
                // than from the average, so the figure reflects the hardest
                // thing actually lifted in the session.
                val best1rm = qualifying.mapNotNull { e ->
                    val w = e.weightKg ?: return@mapNotNull null
                    val r = e.reps ?: return@mapNotNull null
                    epley1rm(w, r)
                }.maxOrNull()
                best = Best(
                    weightKg = avg,
                    workoutStartTime = entries.firstOrNull { it.workoutStartTime != null }?.workoutStartTime,
                    estimated1rmKg = best1rm,
                )
            }
        }
        return best
    }
}

/** One row of the overview: an exercise the user has performed that has at
 *  least one qualifying workout. */
data class ExerciseMaxRow(
    val templateId: String,
    val title: String,
    /** Raw Hevy `primary_muscle_group` (e.g. "upper_back"); null → "other". */
    val muscleGroup: String,
    /** Raw Hevy `equipment` (e.g. "machine"); null → "none". */
    val equipment: String,
    val highestKg: Float,
    val workoutStartTime: String?,
    /** Epley estimate from the best set of the qualifying workout; null when
     *  the history lacked usable weight+reps. See [ExerciseMax.epley1rm]. */
    val estimated1rmKg: Float? = null,
)

/** Ordering applied to the exercises inside each equipment sub-group. */
enum class OverviewSort {
    /** Exercise title A→Z. Default. */
    NAME_ASC,
    /** Date the highest weight was achieved, most-recent first. */
    DATE_DESC;

    fun comparator(): Comparator<ExerciseMaxRow> = when (this) {
        NAME_ASC -> compareBy { it.title.lowercase(Locale.US) }
        // ISO-8601 timestamps share the same offset, so lexicographic order is
        // chronological; null start times sink to the bottom. Title breaks ties.
        DATE_DESC -> compareByDescending<ExerciseMaxRow> { it.workoutStartTime ?: "" }
            .thenBy { it.title.lowercase(Locale.US) }
    }
}

/** A muscle-group section: a flat list of exercises ordered by [OverviewSort].
 *  Equipment is shown per-row and offered as a filter, not as a sub-group. */
data class MuscleSection(
    val muscleGroup: String,
    val rows: List<ExerciseMaxRow>,
) {
    val exerciseCount: Int get() = rows.size
}

/**
 * Group rows by primary muscle group (sections sorted alphabetically by raw
 * group key), with the exercises inside each section ordered by [sort]. Empty
 * groups are naturally absent, so this also drops muscle headers once a
 * search / equipment filter removes all their exercises.
 */
fun groupByMuscle(
    rows: List<ExerciseMaxRow>,
    sort: OverviewSort = OverviewSort.NAME_ASC,
): List<MuscleSection> =
    rows.groupBy { it.muscleGroup }
        .toSortedMap()
        .map { (muscle, muscleRows) ->
            MuscleSection(muscle, muscleRows.sortedWith(sort.comparator()))
        }

/** Display formatting shared by the screen and the CSV export. */
object OverviewFormat {

    // "Mar 28, '26" — single-quote escaped, day without a leading zero.
    private val DATE_OUT = DateTimeFormatter.ofPattern("MMM d, ''yy", Locale.US)

    /** Trim trailing ".0": 40.0 → "40", 22.5 → "22.5". */
    fun kg(value: Float): String =
        if (value % 1f == 0f) value.toInt().toString()
        else "%.1f".format(Locale.US, value).trimEnd('0').trimEnd('.')

    /** ISO-8601 → "Mar 28, '26"; null / unparseable → "". */
    fun date(iso: String?): String {
        if (iso.isNullOrBlank()) return ""
        return try {
            OffsetDateTime.parse(iso).format(DATE_OUT)
        } catch (_: Exception) {
            ""
        }
    }
}
