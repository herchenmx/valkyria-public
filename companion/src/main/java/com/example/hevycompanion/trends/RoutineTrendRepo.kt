package com.example.hevycompanion.trends

import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.data.HevyPublicApi
import com.example.hevycompanion.recents.ProgressiveOverloadFolders
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * One routine and every workout logged from it inside the trend window.
 *
 * @param workouts oldest → newest, so index `i-1` is always the chronological
 *   predecessor the deltas are computed against.
 */
data class RoutineTrend(
    val routineId: String,
    val title: String,
    val workouts: List<TrendWorkout>,
) {
    /** True once every workout carries its sets — see [RoutineTrendRepo.hydrate]. */
    val isHydrated: Boolean get() = workouts.all { it.hasSets }
}

/**
 * Builds the Routine Trends data set off the public api-key.
 *
 * Scoped to Progressive-Overload routines (the **POP** folder), the same scope
 * the Recents list uses: the public API has no list-routines endpoint, so the
 * routines are discovered from the workouts themselves and each distinct
 * `routine_id` is resolved via `GET /v1/routines/{id}` to check its
 * `folder_id` against [ProgressiveOverloadFolders].
 *
 * @param templates supplies the exercise catalog — wired to
 *   `ExerciseTemplateRepo::getOrFetch` in production, so it rides the existing
 *   7-day disk cache. Used only to fill in exercise titles the workout payload
 *   omits.
 */
class RoutineTrendRepo(
    private val api: HevyPublicApi,
    private val apiKey: String,
    private val templates: suspend () -> List<ExerciseTemplate>,
    private val poFolderIds: Set<String> = ProgressiveOverloadFolders.IDS,
) {

    /**
     * Page `/v1/workouts` back to the window's cutoff, keep the PO-routine
     * ones, and group them per routine.
     *
     * Routines with a single workout in the window are kept — the chart still
     * plots the point and the panel reads as a baseline.
     *
     * @param nowMs injected so the window is testable.
     */
    suspend fun load(
        nowMs: Long = System.currentTimeMillis(),
        windowDays: Long = TrendTime.WINDOW_DAYS,
    ): List<RoutineTrend> = coroutineScope {
        val cutoff = TrendTime.cutoffMs(nowMs, windowDays)
        val titles = runCatching { templates().associate { it.id to it.title } }.getOrDefault(emptyMap())

        val inWindow = fetchWorkoutsSince(cutoff, titles)
        val routineIds = inWindow.mapNotNull { it.routineId }.toSet()
        val poRoutines = resolvePoRoutines(routineIds)

        inWindow
            .filter { it.routineId != null && it.routineId in poRoutines }
            .groupBy { it.routineId!! }
            .map { (routineId, rows) ->
                RoutineTrend(
                    routineId = routineId,
                    title = poRoutines.getValue(routineId),
                    workouts = rows.map { it.workout }.sortedBy { it.startEpochMs },
                )
            }
            // Most-recently-trained routine first — that's the one being asked about.
            .sortedByDescending { it.workouts.lastOrNull()?.startEpochMs ?: 0L }
    }

    /**
     * Fill in sets for any workout the list payload delivered without them, by
     * re-reading it from `GET /v1/workouts/{id}`.
     *
     * The list endpoint returns full workout objects (sets included), so this
     * is normally a no-op and [load] alone is enough for the whole year. It
     * exists so the feature degrades to "slower" rather than "all zeroes" if
     * that ever stops being true, and it runs per selected routine so the cost
     * is bounded by one routine's workouts rather than the account's.
     *
     * A workout whose re-read fails keeps whatever the list gave us.
     */
    suspend fun hydrate(trend: RoutineTrend): RoutineTrend {
        if (trend.isHydrated) return trend
        val titles = runCatching { templates().associate { it.id to it.title } }.getOrDefault(emptyMap())
        val semaphore = Semaphore(CONCURRENCY)
        val filled = coroutineScope {
            trend.workouts.map { w ->
                async(Dispatchers.IO) {
                    if (w.hasSets) return@async w
                    semaphore.withPermit {
                        runCatching {
                            api.getWorkout(apiKey = apiKey, workoutId = w.id).toTrendWorkout(titles)
                        }.getOrNull() ?: w
                    }
                }
            }.awaitAll()
        }
        return trend.copy(workouts = filled.sortedBy { it.startEpochMs })
    }

    /** A workout kept from the list walk, with its routine id alongside. */
    private data class Row(val routineId: String?, val workout: TrendWorkout)

    /**
     * Walk `/v1/workouts` newest-first and stop as soon as a page's oldest
     * workout falls outside the window — the list is chronological, so nothing
     * past that point can qualify. [MAX_PAGES] is a backstop against a
     * pathological `page_count`.
     */
    private suspend fun fetchWorkoutsSince(cutoffMs: Long, titles: Map<String, String>): List<Row> {
        val out = mutableListOf<Row>()
        var page = 1
        while (page <= MAX_PAGES) {
            val resp = api.getWorkouts(apiKey = apiKey, page = page, pageSize = PAGE_SIZE)
            var reachedCutoff = false
            resp.workouts.forEach { w ->
                val trend = w.toTrendWorkout(titles) ?: return@forEach
                if (trend.startEpochMs < cutoffMs) {
                    reachedCutoff = true
                    return@forEach
                }
                out += Row(routineId = w.routineId, workout = trend)
            }
            if (reachedCutoff || page >= resp.pageCount) break
            page++
        }
        return out
    }

    /**
     * Resolve [routineIds] concurrently to `id → title` for the ones that live
     * in a PO folder. A routine whose lookup fails is treated as non-PO and
     * its workouts drop out — same rule as the Recents list.
     */
    private suspend fun resolvePoRoutines(routineIds: Set<String>): Map<String, String> = coroutineScope {
        val semaphore = Semaphore(CONCURRENCY)
        routineIds.map { id ->
            async(Dispatchers.IO) {
                semaphore.withPermit {
                    val routine = runCatching {
                        api.getRoutine(apiKey = apiKey, routineId = id).routine
                    }.getOrNull() ?: return@withPermit null
                    val inPoFolder = routine.folderId?.toString() in poFolderIds
                    if (inPoFolder) id to (routine.title ?: id) else null
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    companion object {
        private const val PAGE_SIZE = 10

        /** 12 months of training is ~25 pages; the cap only bites on a bad
         *  `page_count` and still covers ~1,200 workouts. */
        private const val MAX_PAGES = 120

        /** Phone data, not the watch's BT link — same cap the Strength
         *  Overview uses for its history fan-out. */
        private const val CONCURRENCY = 6
    }
}
