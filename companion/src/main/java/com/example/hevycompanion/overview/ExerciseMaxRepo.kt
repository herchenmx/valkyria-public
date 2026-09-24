package com.example.hevycompanion.overview

import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.data.HevyPublicApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Builds the Strength Overview by combining three public-API reads:
 *
 *  1. the full exercise-template catalog (cached weekly by [templates]) — for
 *     each exercise's title, primary muscle group and equipment;
 *  2. `/v1/workouts` — to find which templates the user has actually performed,
 *     so we don't fire ~300 history calls for catalog entries they've never
 *     touched;
 *  3. `/v1/exercise_history/{id}` for each performed *weight_reps* template —
 *     scored by [ExerciseMax.highestQualifying].
 *
 * Only exercises with at least one qualifying workout make it into the result
 * (the user asked to drop the rest), so rows that score null are filtered out.
 *
 * @param templates supplies the catalog — wired to `ExerciseTemplateRepo::getOrFetch`
 *   in production so it benefits from the existing 7-day disk cache.
 */
class ExerciseMaxRepo(
    private val api: HevyPublicApi,
    private val apiKey: String,
    private val templates: suspend () -> List<ExerciseTemplate>,
) {

    /** @param onProgress invoked (done, total) as each history fetch completes. */
    suspend fun load(onProgress: (Int, Int) -> Unit = { _, _ -> }): List<ExerciseMaxRow> =
        coroutineScope {
            val catalog = templates()
            val metaById = catalog.associateBy { it.id }
            val weightReps = catalog
                .filter { it.type == WEIGHT_REPS }
                .map { it.id }
                .toSet()

            val performed = fetchPerformedTemplateIds()
            val candidates = (performed intersect weightReps).toList()

            val total = candidates.size
            val done = AtomicInteger(0)
            onProgress(0, total)
            val semaphore = Semaphore(CONCURRENCY)

            candidates.map { id ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        val best = try {
                            ExerciseMax.highestQualifying(
                                api.getExerciseHistory(apiKey = apiKey, exerciseTemplateId = id).exerciseHistory
                            )
                        } catch (_: Exception) {
                            // A single failed history fetch shouldn't sink the
                            // whole overview — just omit that exercise.
                            null
                        }
                        onProgress(done.incrementAndGet(), total)
                        best?.let {
                            val meta = metaById[id]
                            ExerciseMaxRow(
                                templateId = id,
                                title = meta?.title ?: id,
                                muscleGroup = meta?.primaryMuscleGroup?.takeIf(String::isNotBlank) ?: "other",
                                equipment = meta?.equipment?.takeIf(String::isNotBlank) ?: "none",
                                highestKg = it.weightKg,
                                workoutStartTime = it.workoutStartTime,
                                estimated1rmKg = it.estimated1rmKg,
                            )
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

    /** Walk every page of `/v1/workouts` collecting the distinct template IDs
     *  the user has logged at least once. */
    private suspend fun fetchPerformedTemplateIds(): Set<String> {
        val ids = mutableSetOf<String>()
        var page = 1
        while (true) {
            val resp = api.getWorkouts(apiKey = apiKey, page = page, pageSize = WORKOUTS_PAGE_SIZE)
            resp.workouts.forEach { w -> w.exercises.forEach { ids += it.exerciseTemplateId } }
            if (page >= resp.pageCount) break
            page++
        }
        return ids
    }

    companion object {
        private const val WEIGHT_REPS = "weight_reps"
        private const val WORKOUTS_PAGE_SIZE = 10
        /** History fetches run on phone data, not the watch's BT link, so we
         *  can afford more parallelism than the watch's cap of 3. */
        private const val CONCURRENCY = 6
    }
}
