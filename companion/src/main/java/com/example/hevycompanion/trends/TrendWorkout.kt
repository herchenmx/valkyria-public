package com.example.hevycompanion.trends

import com.example.hevycompanion.data.WorkoutDetail
import com.example.hevycompanion.data.WorkoutSummary
import com.example.hevycompanion.recents.RecentsFormat
import com.example.hevycore.exercise.AssistedBodyweight
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Neutral, API-free shapes the Routine Trends math runs on. The two Hevy
 * payloads that carry logged sets — `GET /v1/workouts` (list) and
 * `GET /v1/workouts/{id}` (detail) — both map onto [TrendWorkout], so the
 * comparison engine never has to care which one the repo could get.
 */
data class TrendSet(
    /** Hevy's set type: "normal" / "warmup" / "dropset" / "failure". */
    val type: String,
    val weightKg: Float,
    val reps: Int,
)

data class TrendExercise(
    val templateId: String,
    val title: String,
    val sets: List<TrendSet>,
)

data class TrendWorkout(
    val id: String,
    val title: String,
    val startTimeIso: String?,
    /** Parsed [startTimeIso]; the chart's X coordinate. */
    val startEpochMs: Long,
    val exercises: List<TrendExercise>,
) {
    /** False when the payload this was built from carried exercises but no
     *  sets — the repo then re-reads the workout from the detail endpoint. */
    val hasSets: Boolean get() = exercises.any { it.sets.isNotEmpty() }
}

/** Which logged sets count towards the totals. */
enum class SetScope {
    /** Everything logged, warmups included — matches the totals Hevy shows. */
    ALL,

    /** Warmups excluded; dropsets and failure sets still count as work. */
    WORKING;

    fun includes(setType: String): Boolean = when (this) {
        ALL -> true
        WORKING -> setType != "warmup"
    }
}

/** Volume / set / rep totals for one exercise or one whole workout. */
data class Totals(
    val volumeKg: Double,
    val sets: Int,
    val reps: Int,
) {
    /** Volume per rep — the "average load" that moves when only the weight
     *  changed. Null when nothing was repped (cardio / duration-only work). */
    val avgLoadPerRepKg: Double? get() = if (reps > 0) volumeKg / reps else null

    operator fun plus(other: Totals) =
        Totals(volumeKg + other.volumeKg, sets + other.sets, reps + other.reps)

    companion object {
        val ZERO = Totals(0.0, 0, 0)
    }
}

/** Which total the chart's Y axis plots. The panel always shows all three. */
enum class TrendMetric(val label: String) {
    VOLUME("Volume"),
    SETS("Sets"),
    REPS("Reps");

    /** Not named `valueOf` — that name belongs to the enum's own static. */
    fun valueIn(totals: Totals): Double = when (this) {
        VOLUME -> totals.volumeKg
        SETS -> totals.sets.toDouble()
        REPS -> totals.reps.toDouble()
    }
}

/** One workout as a chart datapoint. */
data class TrendPoint(
    val workoutId: String,
    val epochMs: Long,
    val totals: Totals,
)

/**
 * How a logged kg turns into volume. Mirrors the watch's
 * `RoutineProgressComputer.workoutVolume`: on the assisted-bodyweight routine
 * the logged number is the stack *assistance*, so the work actually done is
 * `bodyweight − logged` (see [AssistedBodyweight]). Every other exercise —
 * and every other routine — uses the logged weight as-is.
 */
data class VolumeRule(
    val applyAssisted: Boolean = false,
    val bodyweightKg: Float = 0f,
) {
    fun effectiveKg(templateId: String, loggedKg: Float): Double =
        if (applyAssisted && AssistedBodyweight.isAssisted(templateId)) {
            AssistedBodyweight.toEffective(loggedKg, bodyweightKg).toDouble()
        } else {
            loggedKg.toDouble()
        }

    companion object {
        fun forRoutine(routineId: String?, bodyweightKg: Float): VolumeRule =
            VolumeRule(
                applyAssisted = routineId == AssistedBodyweight.ROUTINE_ID,
                bodyweightKg = bodyweightKg,
            )
    }
}

/**
 * Volume / sets / reps for a logged workout, and the set-by-set lines that
 * explain where those numbers came from. Pure — unit-tested by
 * `WorkoutTotalsTest`.
 */
object WorkoutTotals {

    fun of(exercise: TrendExercise, scope: SetScope, rule: VolumeRule): Totals {
        var volume = 0.0
        var sets = 0
        var reps = 0
        exercise.sets.forEach { s ->
            if (!scope.includes(s.type)) return@forEach
            sets++
            reps += s.reps
            volume += rule.effectiveKg(exercise.templateId, s.weightKg) * s.reps
        }
        return Totals(volume, sets, reps)
    }

    fun of(workout: TrendWorkout, scope: SetScope, rule: VolumeRule): Totals =
        workout.exercises.fold(Totals.ZERO) { acc, e -> acc + of(e, scope, rule) }

    /**
     * The session's sets as human-readable lines, folding *runs* of identical
     * sets so `1×15 @ 16kg, 1×15 @ 15kg, 1×15 @ 15kg` reads as
     * `"1 × 15 @ 16 kg"` + `"2 × 15 @ 15 kg"` — the shape a delta's cause is
     * actually argued in. Logged order is preserved (a run, not a global
     * group-by), and warmups are labelled so an all-sets total can be read
     * against a working-sets one.
     */
    fun setLines(exercise: TrendExercise, scope: SetScope): List<String> {
        val kept = exercise.sets.filter { scope.includes(it.type) }
        if (kept.isEmpty()) return emptyList()
        val out = mutableListOf<String>()
        var runStart = 0
        for (i in 1..kept.size) {
            val sameAsRun = i < kept.size && isSameSet(kept[i], kept[runStart])
            if (sameAsRun) continue
            out += line(kept[runStart], count = i - runStart)
            runStart = i
        }
        return out
    }

    private fun isSameSet(a: TrendSet, b: TrendSet): Boolean =
        a.type == b.type && a.reps == b.reps && a.weightKg == b.weightKg

    private fun line(set: TrendSet, count: Int): String = buildString {
        append(count)
        append(" × ")
        append(set.reps)
        // Bodyweight / unloaded work reads as "3 × 12", not "3 × 12 @ 0 kg".
        if (set.weightKg != 0f) {
            append(" @ ")
            append(RecentsFormat.kg(set.weightKg))
        }
        if (set.type != "normal") {
            append("  (")
            append(set.type)
            append(")")
        }
    }
}

/** Timestamp parsing + the 12-month window, shared by the repo and its tests. */
object TrendTime {

    /** How far back the trend looks. */
    const val WINDOW_DAYS = 365L

    private const val DAY_MS = 24L * 60 * 60 * 1000

    fun cutoffMs(nowMs: Long, windowDays: Long = WINDOW_DAYS): Long = nowMs - windowDays * DAY_MS

    /**
     * Tolerant ISO parse, ported from the watch's
     * `RoutineProgressComputer.parseInstant` — the API has been seen emitting
     * `…Z`, `…+00:00` and offset-less variants.
     */
    fun epochMsOrNull(iso: String?): Long? {
        if (iso.isNullOrBlank()) return null
        try { return Instant.parse(iso).toEpochMilli() } catch (_: Exception) {}
        try { return OffsetDateTime.parse(iso).toInstant().toEpochMilli() } catch (_: Exception) {}
        try { return LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC).toEpochMilli() } catch (_: Exception) {}
        val tVariant = iso.replace(' ', 'T')
        if (tVariant != iso) {
            try { return LocalDateTime.parse(tVariant).toInstant(ZoneOffset.UTC).toEpochMilli() } catch (_: Exception) {}
        }
        return null
    }
}

// ---- API payload → TrendWorkout ---------------------------------------------
// A workout whose start_time won't parse is dropped rather than defaulted: it
// has no place on a time axis, and a bogus x-coordinate would silently reorder
// the deltas.

/** @param titles templateId → catalog title, used only where the payload's own
 *   `title` is missing (the list endpoint omits it for some records). */
fun WorkoutSummary.toTrendWorkout(titles: Map<String, String> = emptyMap()): TrendWorkout? {
    val epochMs = TrendTime.epochMsOrNull(startTime) ?: return null
    return TrendWorkout(
        id = id,
        title = title ?: "Workout",
        startTimeIso = startTime,
        startEpochMs = epochMs,
        exercises = exercises.map { e ->
            TrendExercise(
                templateId = e.exerciseTemplateId,
                title = e.title?.takeIf(String::isNotBlank)
                    ?: titles[e.exerciseTemplateId]
                    ?: e.exerciseTemplateId,
                sets = e.sets.map { s ->
                    TrendSet(type = s.type, weightKg = s.weightKg ?: 0f, reps = s.reps ?: 0)
                },
            )
        },
    )
}

fun WorkoutDetail.toTrendWorkout(titles: Map<String, String> = emptyMap()): TrendWorkout? {
    val epochMs = TrendTime.epochMsOrNull(startTime) ?: return null
    return TrendWorkout(
        id = id,
        title = title ?: "Workout",
        startTimeIso = startTime,
        startEpochMs = epochMs,
        exercises = exercises.map { e ->
            TrendExercise(
                templateId = e.exerciseTemplateId,
                title = e.title?.takeIf(String::isNotBlank)
                    ?: titles[e.exerciseTemplateId]
                    ?: e.exerciseTemplateId,
                sets = e.sets.map { s ->
                    TrendSet(type = s.type, weightKg = s.weightKg ?: 0f, reps = s.reps ?: 0)
                },
            )
        },
    )
}
