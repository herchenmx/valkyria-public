package com.example.hevywatch.presentation.routine

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hevywatch.HevyApp
import com.example.hevywatch.data.model.Routine
import com.example.hevywatch.data.model.toDomain
import com.example.hevywatch.data.withNetworkRetry
import kotlinx.coroutines.launch

private const val INCREMENTAL_PAGES = 3
// Once-a-day workout cadence: 12h comfortably covers "app opened multiple
// times in a day" without missing anything that matters. Saves bypass this
// throttle (see HevyApp.workoutHistoryPopulatedAtMs reset in
// LogWorkoutViewModel.recordAndNavigateCongrats) so just-finished sessions
// still appear in Progress / Recent immediately.
private const val POPULATE_THROTTLE_MS = 12L * 60 * 60 * 1000

class RoutineListViewModel(app: Application) : AndroidViewModel(app) {

    private val hevyApp get() = getApplication<HevyApp>()

    var isLoading by mutableStateOf(false)
        private set
    var routines by mutableStateOf<List<Routine>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var navigateTo by mutableStateOf<String?>(null)
        private set

    /** True while a user-tapped Refresh is in flight. Distinct from [isLoading]
     *  (initial load when no cache exists). Drives the page-level full-screen
     *  spinner that hides cached content during an explicit refresh. */
    var isRefreshing by mutableStateOf(false)
        private set

    init {
        val cached = hevyApp.cachedRoutines
        if (cached.isNotEmpty()) {
            routines = cached
            // Even when routines are cached, we still want fresh "last done" dates —
            // but skip if another VM instance populated within the last few minutes.
            if (shouldPopulateHistory()) {
                viewModelScope.launch { populateLastWorkoutDates(cached) }
            }
        } else {
            loadRoutines()
        }
    }

    private fun shouldPopulateHistory(): Boolean {
        val last = hevyApp.workoutHistoryPopulatedAtMs
        return last == 0L || System.currentTimeMillis() - last > POPULATE_THROTTLE_MS
    }

    /** First-time / cache-miss load. Sets [isLoading] so the screen shows the
     *  full-screen spinner. Use [refresh] for the user-tapped Refresh chip. */
    fun loadRoutines() {
        viewModelScope.launch {
            isLoading = true
            error = null
            try {
                val service = hevyApp.requireApiService()
                val all = mutableListOf<Routine>()
                var page = 1
                var pageCount: Int
                do {
                    val response = withNetworkRetry { service.getRoutines(page) }
                    all += response.routines.map { it.toDomain(hevyApp.progressiveOverloadStore.enabledFolderIds) }
                    pageCount = response.pageCount
                    page++
                } while (page <= pageCount)
                val sorted = all.sortedBy { it.title.lowercase() }
                routines = sorted
                hevyApp.cachedRoutines = sorted
                hevyApp.routinesRefreshedAtMs = System.currentTimeMillis()
                hevyApp.pushSnapshotToCompanion()
                populateLastWorkoutDates(sorted)
            } catch (e: Exception) {
                error = e.message ?: "Failed to load routines"
            } finally {
                isLoading = false
            }
        }
    }

    /**
     * User-triggered refresh. Clears the routines cache, blanks the on-screen
     * list, and re-fetches paginated `/v1/routines`. Spinner is driven by
     * [isRefreshing] and ends as soon as the *first* successful response lets
     * us paint the new list — populating workout-date subtitles continues in
     * the background after the spinner is gone.
     */
    fun refresh() {
        viewModelScope.launch {
            isRefreshing = true
            error = null
            // We don't blank `routines` / `cachedRoutines` here — the screen
            // overlays a full-screen spinner over the still-mounted column,
            // hiding the stale data while the fetch runs. Keeping the column
            // mounted means listState stays attached and animateScrollToItem(0)
            // works reliably when the spinner ends. The cache is replaced
            // atomically on success.
            try {
                val service = hevyApp.requireApiService()
                val all = mutableListOf<Routine>()
                var page = 1
                var pageCount: Int
                do {
                    val response = withNetworkRetry { service.getRoutines(page) }
                    all += response.routines.map { it.toDomain(hevyApp.progressiveOverloadStore.enabledFolderIds) }
                    pageCount = response.pageCount
                    page++
                } while (page <= pageCount)
                val sorted = all.sortedBy { it.title.lowercase() }
                routines = sorted
                hevyApp.cachedRoutines = sorted
                hevyApp.routinesRefreshedAtMs = System.currentTimeMillis()
                hevyApp.pushSnapshotToCompanion()
            } catch (e: Exception) {
                error = e.message ?: "Failed to refresh routines"
            } finally {
                isRefreshing = false
            }
            // Reset the populate-throttle so workout dates also re-fetch.
            hevyApp.workoutHistoryPopulatedAtMs = 0L
            populateLastWorkoutDates(routines)
        }
    }

    /**
     * Fetches workout history to populate "last done" dates per routine.
     *
     * - Full fetch (all pages): on first run ever, or if last full fetch was >30 days ago.
     *   Overwrites the persisted map entirely.
     * - Incremental fetch: only the newest INCREMENTAL_PAGES pages. For any
     *   routine that appears in those pages, the cached date is OVERWRITTEN
     *   with the newest start_time found there (not max-merged). This way a
     *   workout that was saved locally and later deleted server-side stops
     *   showing as the "last performed" date — the real most-recent server
     *   workout wins. Routines absent from the fetched pages keep their
     *   cached date (their true latest may be older than ~200 workouts ago).
     *
     * Results are written to both [HevyApp.routineLastWorkoutAt] (Compose state)
     * and [WorkoutHistoryStore] (SharedPreferences, survives app restart).
     */
    private suspend fun populateLastWorkoutDates(routineList: List<Routine>) {
        val store = hevyApp.workoutHistoryStore
        val routineIds = routineList.map { it.id }.toSet()
        try {
            val service = hevyApp.requireApiService()

            if (store.needsFullFetch()) {
                // ── Full fetch: all pages, rebuild from scratch ──────────────────
                val latestByRoutine = mutableMapOf<String, String>()
                val idsByRoutine = mutableMapOf<String, MutableSet<String>>()
                var page = 1
                var pageCount: Int
                do {
                    val response = withNetworkRetry { service.getWorkouts(page) }
                    response.workouts.forEach { w ->
                        val rid = w.routineId ?: return@forEach
                        if (rid !in routineIds) return@forEach
                        val cur = latestByRoutine[rid]
                        if (cur == null || w.startTime > cur) latestByRoutine[rid] = w.startTime
                        idsByRoutine.getOrPut(rid) { mutableSetOf() }.add(w.id)
                    }
                    pageCount = response.pageCount
                    page++
                } while (page <= pageCount)

                store.lastFullFetchAtMs = System.currentTimeMillis()
                if (latestByRoutine.isNotEmpty()) {
                    hevyApp.routineLastWorkoutAt = latestByRoutine
                    store.routineLastWorkoutAt = latestByRoutine
                }
                hevyApp.routineWorkoutIds = idsByRoutine
                store.routineWorkoutIds = idsByRoutine
            } else {
                // ── Incremental fetch: only the newest few pages ─────────────────
                // Workouts are returned newest-first, so any workout logged since
                // the last sync will be on page 1 (or pages 2–3 for heavy users).
                // INCREMENTAL_PAGES covers a comfortable safety margin without
                // paying the cost of a full re-fetch.
                val latestByRoutine = hevyApp.routineLastWorkoutAt.toMutableMap()
                val idsByRoutine = hevyApp.routineWorkoutIds
                    .mapValues { it.value.toMutableSet() }.toMutableMap()

                // Fresh per-routine newest start_time built from JUST the fetched
                // pages. Used to overwrite the cached date so a stale local entry
                // (e.g. a watch-only test save whose server workout was later
                // deleted) cannot win against the real server state.
                val freshByRoutine = mutableMapOf<String, String>()

                val firstPage = hevyApp.cachedWorkoutsPage1OrFetch {
                    withNetworkRetry { service.getWorkouts(1) }
                }
                firstPage.workouts.forEach { w ->
                    val rid = w.routineId ?: return@forEach
                    if (rid !in routineIds) return@forEach
                    val cur = freshByRoutine[rid]
                    if (cur == null || w.startTime > cur) freshByRoutine[rid] = w.startTime
                    idsByRoutine.getOrPut(rid) { mutableSetOf() }.add(w.id)
                }

                val pagesToFetch = minOf(INCREMENTAL_PAGES, firstPage.pageCount)
                for (p in 2..pagesToFetch) {
                    val response = service.getWorkouts(p)
                    response.workouts.forEach { w ->
                        val rid = w.routineId ?: return@forEach
                        if (rid !in routineIds) return@forEach
                        val cur = freshByRoutine[rid]
                        if (cur == null || w.startTime > cur) freshByRoutine[rid] = w.startTime
                        idsByRoutine.getOrPut(rid) { mutableSetOf() }.add(w.id)
                    }
                }

                // Overwrite cached date for any routine present in the fetched
                // pages. Routines absent from those pages keep their cached
                // value — their true latest may be older than what we fetched.
                freshByRoutine.forEach { (rid, ts) -> latestByRoutine[rid] = ts }

                if (latestByRoutine.isNotEmpty()) {
                    hevyApp.routineLastWorkoutAt = latestByRoutine
                    store.routineLastWorkoutAt = latestByRoutine
                }
                hevyApp.routineWorkoutIds = idsByRoutine
                store.routineWorkoutIds = idsByRoutine
            }
            hevyApp.workoutHistoryPopulatedAtMs = System.currentTimeMillis()
            hevyApp.pushSnapshotToCompanion()
        } catch (_: Exception) {
            /* silently skip — dates are optional */
        }
    }

    fun onRoutineClick(routineId: String) {
        navigateTo = "routine_detail/$routineId"
    }

    fun onNavigated() {
        navigateTo = null
    }
}
