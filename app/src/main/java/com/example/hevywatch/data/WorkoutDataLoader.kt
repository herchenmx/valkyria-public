package com.example.hevywatch.data

import android.util.Log
import com.example.hevywatch.HevyApp
import com.example.hevywatch.data.api.model.ExerciseHistoryResponse
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Shared data-loading logic used by both RoutineDetailViewModel and LogWorkoutViewModel.
 * Eliminates duplication of exercise history fetching and equipment template loading.
 *
 * The bodyweight-equipment set lives in [BODYWEIGHT_EQUIPMENT] (shared with
 * [SimilarExerciseSuggestion]) so the prefetch skip and the suggestion skip
 * can't drift apart.
 */

/**
 * P4 — cap on parallel /exercise_history fetches. The watch's BT-tethered link
 * tolerates a small burst (3 in flight) much better than long serial pipelines
 * of 10+ requests; bigger fan-outs risk Hevy rate-limit responses and BT
 * congestion. 3 is the sweet spot from the v2/workout probe sessions.
 */
private const val HISTORY_FETCH_CONCURRENCY = 3

/**
 * Cap on parallel /exercise_templates page fetches. Same BT-congestion
 * reasoning as [HISTORY_FETCH_CONCURRENCY], but template pages are small
 * fixed-size responses fetched exactly once per cache warm, so a slightly
 * wider fan-out is worth it — this is the single slowest step of a cold
 * first launch (~50 pages).
 */
private const val TEMPLATE_FETCH_CONCURRENCY = 5

object WorkoutDataLoader {

    private const val TAG = "WorkoutDataLoader"

    /**
     * Fetch exercise history for any template IDs not already in cache,
     * up to [HISTORY_FETCH_CONCURRENCY] requests in flight at once.
     * Populates [HevyApp.exerciseHistoryCache] and [HevyApp.exerciseBestWeights].
     */
    suspend fun fetchExerciseHistory(
        templateIds: List<String>,
        hevyApp: HevyApp
    ): Map<String, ExerciseHistoryResponse> = coroutineScope {
        val historyMap = java.util.concurrent.ConcurrentHashMap<String, ExerciseHistoryResponse>()
        val service = hevyApp.requireApiService()
        val semaphore = Semaphore(HISTORY_FETCH_CONCURRENCY)

        // Seed with already-cached entries; only fan out for the rest.
        // Bucket-C item 16 — treat cache entries older than [HevyApp.EXERCISE_HISTORY_TTL_MS]
        // as missing so today's PRs surface on tomorrow's first workout without
        // a manual refresh. Direct readers (long-press expand, similar-exercise
        // hints) still see the stale value until the fetch below refreshes it.
        val nowMs = System.currentTimeMillis()
        val (cachedIds, missingIds) = templateIds.partition { id ->
            val fetchedAt = hevyApp.exerciseHistoryFetchedAtMs[id] ?: 0L
            hevyApp.exerciseHistoryCache.containsKey(id) &&
                nowMs - fetchedAt < HevyApp.EXERCISE_HISTORY_TTL_MS
        }
        cachedIds.forEach { id ->
            hevyApp.exerciseHistoryCache[id]?.let { historyMap[id] = it }
        }

        missingIds.map { id ->
            async {
                semaphore.withPermit {
                    try {
                        val response = withNetworkRetry { service.getExerciseHistory(id, page = 1) }
                        hevyApp.exerciseHistoryCache[id] = response
                        hevyApp.exerciseHistoryFetchedAtMs[id] = System.currentTimeMillis()
                        historyMap[id] = response

                        val best = response.exerciseHistory.orEmpty()
                            .filter {
                                it.setType.equals("normal", ignoreCase = true) &&
                                    (it.weightKg ?: 0f) > 0f &&
                                    (it.reps ?: 0) >= 10
                            }
                            .maxOfOrNull { it.weightKg ?: 0f }
                        hevyApp.exerciseBestWeights[id] = best
                    } catch (e: Exception) {
                        // History is non-essential (used for "previous" hints + best-weight badges);
                        // a single failure shouldn't block the rest.
                        Log.w(TAG, "fetchExerciseHistory($id) failed", e)
                    }
                }
            }
        }.awaitAll()

        historyMap.toMap()
    }

    /**
     * Fetch exercise templates (equipment + muscle group) for any IDs not already cached.
     * Persists the resulting snapshot to disk so the next cold start can skip the
     * paginated fetch entirely (see [HevyApp.persistExerciseTemplates]).
     */
    suspend fun fetchExerciseTemplates(
        templateIds: List<String>,
        hevyApp: HevyApp
    ) {
        val needsData = templateIds.any { id ->
            !hevyApp.exerciseEquipment.containsKey(id) ||
                !hevyApp.exerciseMuscleGroup.containsKey(id)
        }
        if (!needsData) return

        try {
            val service = hevyApp.requireApiService()
            // Page 1 tells us the page count; the rest go out concurrently
            // (bounded) instead of one round-trip at a time. The catalog is
            // ~50 pages, so serial pagination meant ~50× the BT-tether
            // latency on a cold cache — the slowest part of first launch.
            val first = withNetworkRetry { service.getExerciseTemplates(1) }
            fun absorb(templates: List<com.example.hevywatch.data.api.model.ExerciseTemplateResponse>) {
                templates.forEach { t ->
                    hevyApp.exerciseEquipment[t.id] = t.equipment
                    hevyApp.exerciseMuscleGroup[t.id] = t.primaryMuscleGroup
                }
            }
            absorb(first.exerciseTemplates)

            if (first.pageCount > 1) {
                val semaphore = Semaphore(TEMPLATE_FETCH_CONCURRENCY)
                coroutineScope {
                    (2..first.pageCount).map { page ->
                        async {
                            semaphore.withPermit {
                                runCatching {
                                    withNetworkRetry { service.getExerciseTemplates(page) }
                                }.getOrNull()
                            }
                        }
                    }.awaitAll()
                }.filterNotNull().forEach { absorb(it.exerciseTemplates) }
            }
            hevyApp.persistExerciseTemplates()
        } catch (e: Exception) {
            Log.w(TAG, "fetchExerciseTemplates failed (will retry on next demand)", e)
        }
    }

    /**
     * Pre-warm the similar-exercise history cache for any exercise in [templateIds] that
     * doesn't yet have cached history (new-to-user exercises). For each such exercise,
     * identifies other templates that (a) share its primary muscle group + equipment AND
     * (b) appear in at least one of the user's cached routines (i.e. exercises they
     * actually train), then fetches their history. This is what lets
     * [SimilarExerciseSuggestion] find a reference weight for a never-worked exercise
     * when the reference exercise lives in a different routine that hasn't been opened
     * this session.
     */
    suspend fun prefetchSimilarExerciseHistory(
        templateIds: List<String>,
        hevyApp: HevyApp
    ) {
        val templatesInUserRoutines: Set<String> = hevyApp.cachedRoutines
            .flatMap { it.exercises.map { ex -> ex.exerciseTemplateId } }
            .toSet()
        if (templatesInUserRoutines.isEmpty()) return

        val candidates = mutableSetOf<String>()
        for (newId in templateIds) {
            // Only chase similar history for exercises the user has never worked —
            // ones already in cache don't need a reference weight.
            val hasOwnHistory = hevyApp.exerciseHistoryCache[newId]
                ?.exerciseHistory?.any { (it.weightKg ?: 0f) > 0f } == true
            if (hasOwnHistory) continue

            val equipment = hevyApp.exerciseEquipment[newId] ?: continue
            // Skip bodyweight / no-weight exercises — SimilarExerciseSuggestion.find
            // short-circuits on these, so prefetching their "siblings" wastes API calls.
            if (equipment.lowercase() in BODYWEIGHT_EQUIPMENT) continue
            val muscle = hevyApp.exerciseMuscleGroup[newId] ?: continue

            for ((otherId, otherEq) in hevyApp.exerciseEquipment) {
                if (otherId == newId) continue
                if (otherId in templateIds) continue
                if (otherId !in templatesInUserRoutines) continue
                if (!otherEq.equals(equipment, ignoreCase = true)) continue
                val otherMuscle = hevyApp.exerciseMuscleGroup[otherId] ?: continue
                if (!otherMuscle.equals(muscle, ignoreCase = true)) continue
                if (hevyApp.exerciseHistoryCache.containsKey(otherId)) continue
                candidates.add(otherId)
            }
        }
        if (candidates.isEmpty()) return
        fetchExerciseHistory(candidates.toList(), hevyApp)
    }

    /**
     * Paginate through every exercise template so equipment/muscle-group lookups are
     * instant the first time the user opens a routine. No-op when the cache is already
     * populated (from disk snapshot, a previous warm-up, or a routine fetch).
     * Persists the resulting snapshot to disk on success.
     *
     * Bucket-C item 10 — the previous call site fired straight from
     * [HevyApp.onCreate], competing with the first-frame paint on the
     * Snapdragon Wear 2100 for CPU + BT-tether bandwidth. A short delay defers
     * the fetch past the first paint so the launcher animation isn't starved.
     * The delay is only paid when the cache is genuinely cold; a warm cache
     * still returns immediately.
     */
    suspend fun warmExerciseTemplateCache(hevyApp: HevyApp) {
        if (hevyApp.exerciseEquipment.isNotEmpty()) return
        // Defer past the first paint frame — the fetch itself is background,
        // but the JSON parse + Gson allocations still compete for CPU.
        kotlinx.coroutines.delay(WARM_CACHE_STARTUP_DELAY_MS)
        if (hevyApp.exerciseEquipment.isNotEmpty()) return   // re-check after the sleep
        try {
            val service = hevyApp.requireApiService()
            var page = 1; var pageCount: Int
            do {
                val resp = withNetworkRetry { service.getExerciseTemplates(page) }
                resp.exerciseTemplates.forEach { t ->
                    hevyApp.exerciseEquipment[t.id] = t.equipment
                    hevyApp.exerciseMuscleGroup[t.id] = t.primaryMuscleGroup
                }
                pageCount = resp.pageCount; page++
            } while (page <= pageCount)
            hevyApp.persistExerciseTemplates()
        } catch (e: Exception) {
            Log.w(TAG, "warmExerciseTemplateCache failed", e)
        }
    }

    private const val WARM_CACHE_STARTUP_DELAY_MS = 3_000L
}
