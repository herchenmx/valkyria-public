package com.example.hevywatch.presentation.routine

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.hevywatch.HevyApp
import com.example.hevywatch.data.api.model.RoutineFolderResponse
import com.example.hevywatch.data.api.model.WorkoutSummaryResponse
import com.example.hevywatch.data.model.toDomain
import com.example.hevywatch.data.withNetworkRetry
import com.example.hevywatch.presentation.navigation.Screen
import kotlinx.coroutines.launch

class RoutineFolderListViewModel(app: Application) : AndroidViewModel(app) {

    private val hevyApp get() = getApplication<HevyApp>()

    /** True only on the *initial* load when nothing is cached yet — i.e. fresh
     *  install or process death. Drives the full-screen spinner that takes
     *  over the whole screen before any data exists. User-triggered explicit
     *  refresh uses [isRefreshingFolders] / [isRefreshingRecent] instead. */
    var isLoading by mutableStateOf(false)
        private set
    var folders by mutableStateOf<List<RoutineFolderResponse>>(emptyList())
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var navigateTo by mutableStateOf<String?>(null)
        private set
    /** Last 5 workouts from any routine, newest first. The Recents page shows
     *  only the PO-folder subset of these — see [visibleRecentWorkouts]. */
    var recentWorkouts by mutableStateOf<List<WorkoutSummaryResponse>>(emptyList())
        private set

    /** Ids of routines that live in a Progressive-Overload folder. Derived from
     *  the routine cache whenever it loads; used to filter the Recents page. */
    var poRoutineIds by mutableStateOf<Set<String>>(emptySet())
        private set

    /** The Recents page content: [recentWorkouts] filtered to workouts logged
     *  from a PO-folder routine. Reactive — recomputes when either the fetched
     *  recents or the PO routine-id set changes. */
    val visibleRecentWorkouts: List<WorkoutSummaryResponse>
        get() = poRecents(recentWorkouts, poRoutineIds)

    /** True while the user-tapped Page 0 Refresh chip is in flight. Hides the
     *  page content behind a full-screen spinner until at least one of the
     *  underlying endpoints (folders / routines) returns 2XX. */
    var isRefreshingFolders by mutableStateOf(false)
        private set
    /** True while the user-tapped Page 1 Refresh chip is in flight. */
    var isRefreshingRecent by mutableStateOf(false)
        private set

    /** folderId → number of routines in that folder. Empty until the routine
     *  list has been fetched at least once; used for the Folder-chip subtitle. */
    var routineCountsByFolder by mutableStateOf<Map<String, Int>>(emptyMap())
        private set
    /** True once we've observed a populated routine list (either from cache or a
     *  fresh fetch). Lets the screen distinguish "0 routines" from "unknown yet". */
    var routineCountsLoaded by mutableStateOf(false)
        private set

    /** Synthetic folder for routines without a folder_id. Hevy itself doesn't
     *  return one, but routines created without a folder would otherwise be
     *  invisible. The id is a sentinel that doesn't collide with any real
     *  folder id from the API (which are stringified Longs). */
    val uncategorizedFolderId: String = UNCATEGORIZED_ID

    init {
        val cached = hevyApp.cachedFolders
        if (cached.isNotEmpty()) {
            folders = cached
            // Background top-up so a deleted folder etc. eventually disappears.
            // Silent — failure shows as the inline error banner, not a spinner.
            silentLoadFolders()
        } else {
            loadFoldersInitial()
        }
        loadRecentWorkouts()
        primeRoutineCounts()
    }

    /** First-time folders load — shows the full-screen [isLoading] spinner
     *  because there's nothing cached to display yet. */
    private fun loadFoldersInitial() {
        viewModelScope.launch {
            isLoading = true
            error = null
            try {
                val service = hevyApp.requireApiService()
                val response = withNetworkRetry { service.getRoutineFolders() }
                folders = response.routineFolders.sortedBy { it.index }
                hevyApp.cachedFolders = folders
                hevyApp.foldersRefreshedAtMs = System.currentTimeMillis()
                hevyApp.pushSnapshotToCompanion()
            } catch (e: Exception) {
                error = e.message ?: "Failed to load folders"
            } finally {
                isLoading = false
            }
        }
    }

    /** Background top-up of the cached folder list. Failure surfaces as the
     *  small inline banner above the list, not as a spinner replacing the
     *  cached content. */
    private fun silentLoadFolders() {
        viewModelScope.launch {
            error = null
            try {
                val service = hevyApp.requireApiService()
                val response = withNetworkRetry { service.getRoutineFolders() }
                folders = response.routineFolders.sortedBy { it.index }
                hevyApp.cachedFolders = folders
                hevyApp.foldersRefreshedAtMs = System.currentTimeMillis()
                hevyApp.pushSnapshotToCompanion()
            } catch (e: Exception) {
                error = e.message ?: "Failed to load folders"
            }
        }
    }

    /**
     * User-triggered refresh of the Folders page. Clears the on-disk caches
     * for folders and routines, then re-fetches both — folders for the chip
     * list, routines for the per-folder counts. The full-screen spinner stays
     * visible until at least one of the two endpoints returns 2XX (the
     * "primary" being folders); any subsequent fresh data updates the screen
     * reactively as it arrives.
     */
    fun refreshFolders() {
        viewModelScope.launch {
            isRefreshingFolders = true
            error = null
            // The screen overlays a full-screen spinner over the (still-mounted)
            // ScalingLazyColumn while isRefreshingFolders is true, so the user
            // never sees the stale chips. Therefore we *don't* blank `folders`
            // / `cachedFolders` here — keeping them around means the column's
            // listState stays attached to real items, so the post-refresh
            // animateScrollToItem(0) reliably lands at the top, and we avoid
            // the "No folders found" empty-state flash that fired when the
            // outer when-clause briefly saw an empty cache mid-refresh.
            // The cache is replaced atomically when the fetch returns.

            val service = hevyApp.requireApiService()
            try {
                // Primary endpoint — folders list. Hide the spinner the moment
                // this returns so the user sees the fresh chip list ASAP.
                val response = withNetworkRetry { service.getRoutineFolders() }
                folders = response.routineFolders.sortedBy { it.index }
                hevyApp.cachedFolders = folders
                hevyApp.foldersRefreshedAtMs = System.currentTimeMillis()
            } catch (e: Exception) {
                error = e.message ?: "Failed to refresh folders"
            } finally {
                isRefreshingFolders = false
            }

            // Secondary endpoint — routines, for chip subtitle counts. Runs
            // after the spinner is gone; the chips just go from "no count" to
            // a count once the response lands.
            try {
                val all = mutableListOf<com.example.hevywatch.data.model.Routine>()
                var page = 1; var pageCount: Int
                do {
                    val response = withNetworkRetry { service.getRoutines(page) }
                    all += response.routines
                        .map { it.toDomain(hevyApp.progressiveOverloadStore.enabledFolderIds) }
                    pageCount = response.pageCount; page++
                } while (page <= pageCount)
                val sorted = all.sortedBy { it.title.lowercase() }
                hevyApp.cachedRoutines = sorted
                applyRoutines(sorted)
                hevyApp.pushSnapshotToCompanion()
            } catch (_: Exception) {
                /* best-effort — counts subtitle stays unset */
            }
        }
    }

    /**
     * User-triggered refresh of the Recent workouts page. Drops the memoised
     * page-1 cache and re-fetches; the spinner hides as soon as the request
     * returns 2XX.
     */
    fun refreshRecent() {
        viewModelScope.launch {
            isRefreshingRecent = true
            // Drop the memoised page-1 cache so the fetch actually hits the
            // network. We *don't* blank `recentWorkouts` here — the screen
            // overlays a spinner over the still-mounted column, so listState
            // stays attached and the post-refresh animateScrollToItem(0)
            // reliably scrolls to the top.
            hevyApp.invalidateWorkoutsPage1()
            try {
                val limit = hevyApp.displayLimitsStore.recentWorkoutsLimit
                recentWorkouts = sortedRecents(fetchRecents(limit), limit)
                hevyApp.recentRefreshedAtMs = System.currentTimeMillis()
            } catch (_: Exception) {
                /* best-effort */
            } finally {
                isRefreshingRecent = false
            }
        }
    }

    /** Recompute the routine-derived screen state — per-folder counts and the
     *  PO routine-id set used to filter the Recents page — from a routine
     *  list. Called from every path that (re)loads routines. */
    private fun applyRoutines(routines: List<com.example.hevywatch.data.model.Routine>) {
        routineCountsByFolder = routineCountsByFolder(routines)
        routineCountsLoaded = true
        poRoutineIds = poRoutineIds(routines)
    }

    /** Init-time prime of [routineCountsByFolder] from the cached routine
     *  list, plus a background fetch when the cache is empty. Not user-
     *  visible as a refresh — runs silently. */
    private fun primeRoutineCounts() {
        if (hevyApp.cachedRoutines.isNotEmpty()) {
            applyRoutines(hevyApp.cachedRoutines)
            return
        }
        viewModelScope.launch {
            try {
                val service = hevyApp.requireApiService()
                val all = mutableListOf<com.example.hevywatch.data.model.Routine>()
                var page = 1; var pageCount: Int
                do {
                    val response = withNetworkRetry { service.getRoutines(page) }
                    all += response.routines
                        .map { it.toDomain(hevyApp.progressiveOverloadStore.enabledFolderIds) }
                    pageCount = response.pageCount; page++
                } while (page <= pageCount)
                val sorted = all.sortedBy { it.title.lowercase() }
                hevyApp.cachedRoutines = sorted
                applyRoutines(sorted)
                hevyApp.pushSnapshotToCompanion()
            } catch (_: Exception) {
                /* best-effort — subtitle stays hidden until user hits Refresh */
            }
        }
    }

    /** Initial / silent load of the recent-workouts list. Reuses the shared
     *  page-1 cache so RoutineListViewModel and this VM don't both hit the
     *  network when the user lands on the folder screen. Multi-page when
     *  the configured limit exceeds one page — see [fetchRecents]. */
    private fun loadRecentWorkouts() {
        viewModelScope.launch {
            try {
                val limit = hevyApp.displayLimitsStore.recentWorkoutsLimit
                recentWorkouts = sortedRecents(fetchRecents(limit), limit)
                if (hevyApp.recentRefreshedAtMs == 0L) {
                    hevyApp.recentRefreshedAtMs = System.currentTimeMillis()
                }
            } catch (_: Exception) { /* best-effort */ }
        }
    }

    /**
     * Fetch enough workouts to satisfy [limit]. Page 1 (10 workouts) hits the
     * shared cache; pages 2+ are fresh fetches (uncached — they'd need their
     * own per-page TTL machinery to be worth caching, and the user has to
     * explicitly opt into >10 via Settings, so the API cost is intentional).
     *
     * Stops early when the API reports it ran out of pages (`pageCount`), so
     * a user with only 7 workouts and a limit of 20 still only triggers one
     * network call.
     */
    private suspend fun fetchRecents(limit: Int): List<com.example.hevywatch.data.api.model.WorkoutSummaryResponse> {
        val pageSize = com.example.hevywatch.data.store.DisplayLimitsStore.WORKOUTS_PAGE_SIZE
        val pagesNeeded = ((limit + pageSize - 1) / pageSize).coerceAtLeast(1)
        val service = hevyApp.requireApiService()
        val out = mutableListOf<com.example.hevywatch.data.api.model.WorkoutSummaryResponse>()
        for (page in 1..pagesNeeded) {
            val response = if (page == 1) {
                hevyApp.cachedWorkoutsPage1OrFetch {
                    withNetworkRetry { service.getWorkouts(page = 1) }
                }
            } else {
                withNetworkRetry { service.getWorkouts(page = page) }
            }
            out += response.workouts
            if (page >= response.pageCount) break
        }
        return out
    }

    fun onFolderClick(folderId: String) {
        navigateTo = Screen.routineList(folderId)
    }

    fun onWorkoutClick(workoutId: String) {
        navigateTo = Screen.workoutDetail(workoutId)
    }

    fun onNavigated() {
        navigateTo = null
    }

    companion object {
        /** Sentinel folder id for routines whose `folder_id` is null. */
        const val UNCATEGORIZED_ID = "__uncategorized__"

        /**
         * Returns the visible folder list, plus a synthetic "Uncategorized"
         * folder when the routine cache contains any folder-less routine.
         * Pure for testing: callers pass cached folders + cached routines.
         */
        fun foldersWithUncategorized(
            folders: List<RoutineFolderResponse>,
            routines: List<com.example.hevywatch.data.model.Routine>,
        ): List<RoutineFolderResponse> {
            val hasOrphans = routines.any { it.folderId == null }
            if (!hasOrphans) return folders
            // Append at the end with an index past any real one — the API
            // sorts by index ascending, so this keeps Uncategorized at the
            // bottom of the list where it's least disruptive.
            val maxIndex = folders.maxOfOrNull { it.index } ?: 0
            return folders + RoutineFolderResponse(
                id = UNCATEGORIZED_ID,
                title = "Uncategorized",
                index = maxIndex + 1,
            )
        }

        /** How many folders to display on the Folders page. Real folders +
         *  any synthetic Uncategorized are appended via [foldersWithUncategorized],
         *  then truncated to this many. Folders beyond the cap stay
         *  reachable through the source app — the watch is a focused
         *  surface, not a folder browser. */
        const val FOLDERS_DISPLAYED_LIMIT = 5

        /** Compose helper that joins [foldersWithUncategorized] with the
         *  [FOLDERS_DISPLAYED_LIMIT] cap. Companion-fn so unit tests can
         *  exercise both behaviors in one call without instantiating the VM. */
        fun displayedFolders(
            folders: List<RoutineFolderResponse>,
            routines: List<com.example.hevywatch.data.model.Routine>,
            limit: Int = FOLDERS_DISPLAYED_LIMIT,
        ): List<RoutineFolderResponse> =
            foldersWithUncategorized(folders, routines).take(limit)

        /**
         * Recents-page ordering. Sort by `start_time` desc — the chronological
         * "when this workout happened" key, which is what the user expects.
         * The Hevy API typically returns page 1 in this order already, but
         * we sort explicitly so the page can never depend on server-side
         * ordering quirks. `start_time` is an ISO-8601 string so a
         * lexicographic compare orders them correctly.
         */
        /** How many recent workouts to surface on Page 2. Page 1 of the
         *  /v1/workouts endpoint returns 10 per page, so a default of 10
         *  needs no additional API calls — see [loadRecentWorkouts] /
         *  [refreshRecent], both fetch page 1 only. */
        const val RECENT_WORKOUTS_LIMIT = 10

        fun sortedRecents(
            workouts: List<WorkoutSummaryResponse>,
            limit: Int = RECENT_WORKOUTS_LIMIT,
        ): List<WorkoutSummaryResponse> =
            workouts.sortedByDescending { it.startTime }
                .take(limit)

        /** Ids of routines that live in a Progressive-Overload folder. A routine
         *  carries [Routine.progressiveOverload] = true when its `folder_id` is
         *  in the user's configured PO folder set. */
        fun poRoutineIds(
            routines: List<com.example.hevywatch.data.model.Routine>,
        ): Set<String> =
            routines.filter { it.progressiveOverload }.map { it.id }.toSet()

        /**
         * Recents-page visibility filter: keep only workouts logged from a
         * PO-folder routine. A workout with no `routine_id`, or one whose
         * routine isn't in a PO folder, is hidden. Order is preserved, so the
         * caller's [sortedRecents] ordering still holds.
         */
        fun poRecents(
            recents: List<WorkoutSummaryResponse>,
            poRoutineIds: Set<String>,
        ): List<WorkoutSummaryResponse> =
            recents.filter { it.routineId != null && it.routineId in poRoutineIds }

        /** Routine-count map that includes the synthetic Uncategorized bucket. */
        fun routineCountsByFolder(
            routines: List<com.example.hevywatch.data.model.Routine>,
        ): Map<String, Int> {
            val real = routines.mapNotNull { it.folderId }.groupingBy { it }.eachCount()
            val orphans = routines.count { it.folderId == null }
            return if (orphans > 0) real + (UNCATEGORIZED_ID to orphans) else real
        }
    }
}
