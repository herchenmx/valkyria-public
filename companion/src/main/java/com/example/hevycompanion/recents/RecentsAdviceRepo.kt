package com.example.hevycompanion.recents

import com.example.hevycompanion.data.ExerciseHistoryEntry
import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.data.ExerciseTemplateRepo
import com.example.hevycompanion.data.HevyPublicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset

/**
 * Fetches per-exercise history (concurrency-capped, like the Strength Overview)
 * and computes the progressive-overload target + advised warmups for a set of
 * exercises. Shared by the Workout Detail screen (read-only display) and the
 * in-companion Resume flow (which pre-fills remaining sets with the PO target).
 *
 * All reads are on the account-scoped public api-key.
 */
class RecentsAdviceRepo(
    private val api: HevyPublicApi,
    private val apiKey: String,
    private val templateRepo: ExerciseTemplateRepo,
) {

    /** One exercise to advise on, with the normal-set count driving the warmup
     *  advisor's unilateral doubling. */
    data class Target(val templateId: String, val normalSetCount: Int)

    /**
     * @param targets           distinct exercises to advise on.
     * @param excludeWorkoutId  the workout whose history entries to drop, so PO
     *   resolves against prior *completed* sessions (the watch's resume filter).
     * @param bodyweightKg      for assisted-exercise effort-space math.
     * @param beforeStartTimeIso  start time (ISO) of the workout being viewed.
     *   History sessions logged AT OR AFTER this instant are excluded so the PO
     *   target reconstructs the weight the live advisor used *when this workout
     *   was logged*, not a forward-looking target inflated by LATER sessions
     *   (Leg-Press-advised-3-warmups-then-later-sessions-push-it-to-4 bug).
     *   Null → time filter skipped (id-only exclusion).
     */
    suspend fun compute(
        targets: List<Target>,
        excludeWorkoutId: String,
        bodyweightKg: Float,
        beforeStartTimeIso: String? = null,
    ): Map<String, ExerciseAdvice> = coroutineScope {
        if (targets.isEmpty()) return@coroutineScope emptyMap()

        val meta: Map<String, ExerciseTemplate> =
            runCatching { templateRepo.getOrFetch().associateBy { it.id } }.getOrDefault(emptyMap())
        val cutoff = beforeStartTimeIso?.let { parseInstant(it) }
        val semaphore = Semaphore(HISTORY_CONCURRENCY)

        targets.map { target ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val history = runCatching {
                        historyBefore(
                            api.getExerciseHistory(apiKey = apiKey, exerciseTemplateId = target.templateId)
                                .exerciseHistory,
                            excludeWorkoutId,
                            cutoff,
                        )
                    }.getOrDefault(emptyList())
                    val m = meta[target.templateId]
                    target.templateId to ExerciseAdvisor.adviseFor(
                        history = history,
                        equipment = m?.equipment,
                        primaryMuscleGroup = m?.primaryMuscleGroup,
                        exerciseTemplateId = target.templateId,
                        normalSetCount = target.normalSetCount,
                        bodyweightKg = bodyweightKg,
                    )
                }
            }
        }.awaitAll().toMap()
    }

    companion object {
        /** Phone data, not the watch's BT link — same cap the Strength Overview uses. */
        private const val HISTORY_CONCURRENCY = 6

        /**
         * History entries kept when reconstructing a viewed workout's PO target:
         * drop the viewed workout ([excludeWorkoutId]) and every session logged AT
         * OR AFTER [cutoff] (the viewed workout's start). Entries with an
         * unparseable/absent stamp are kept. [cutoff] null → id-only exclusion.
         * Mirrors the watch's WorkoutDetailViewModel.historyBefore.
         */
        internal fun historyBefore(
            entries: List<ExerciseHistoryEntry>,
            excludeWorkoutId: String,
            cutoff: Instant?,
        ): List<ExerciseHistoryEntry> = entries.filter { entry ->
            if (entry.workoutId == excludeWorkoutId) return@filter false
            if (cutoff == null) return@filter true
            val t = entry.workoutStartTime?.let { parseInstant(it) } ?: return@filter true
            t.isBefore(cutoff)
        }

        /** Tolerant ISO parse (mirrors the watch's RoutineProgressComputer): "…Z",
         *  "…+00:00", or offset-less local. Null when nothing parses. */
        internal fun parseInstant(iso: String): Instant? {
            try { return Instant.parse(iso) } catch (_: Exception) {}
            try { return OffsetDateTime.parse(iso).toInstant() } catch (_: Exception) {}
            try { return LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC) } catch (_: Exception) {}
            val tVariant = iso.replace(' ', 'T')
            if (tVariant != iso) {
                try { return LocalDateTime.parse(tVariant).toInstant(ZoneOffset.UTC) } catch (_: Exception) {}
            }
            return null
        }
    }
}
