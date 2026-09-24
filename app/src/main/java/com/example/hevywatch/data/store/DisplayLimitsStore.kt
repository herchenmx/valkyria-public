package com.example.hevywatch.data.store

import android.content.Context

/**
 * User-tunable display caps for the Routine Folder List screen. Both pages
 * surface a fixed number of items; this store lets the user override either.
 *
 *  - [folderListLimit]: how many folders show on Page 1. Real folders + the
 *    synthetic Uncategorized are joined, then truncated. Folders beyond the
 *    cap stay reachable through the source app.
 *  - [recentWorkoutsLimit]: how many recent workouts show on Page 2. The
 *    `/v1/workouts?page=1` endpoint returns 10 per page, so limits beyond 10
 *    cost additional API calls (see RoutineFolderListViewModel.fetchRecents).
 *
 * Both values clamp to safe ranges on the setter so a corrupted prefs blob
 * can't surface a 0 (empty list) or a 9999 (multi-page fetch storm).
 */
class DisplayLimitsStore(context: Context) {

    private val prefs = context.getSharedPreferences("display_limits_prefs", Context.MODE_PRIVATE)

    var folderListLimit: Int
        get() = prefs.getInt(KEY_FOLDER_LIMIT, DEFAULT_FOLDER_LIMIT)
        set(value) {
            val clamped = value.coerceIn(MIN_FOLDER_LIMIT, MAX_FOLDER_LIMIT)
            prefs.edit().putInt(KEY_FOLDER_LIMIT, clamped).apply()
        }

    var recentWorkoutsLimit: Int
        get() = prefs.getInt(KEY_RECENT_LIMIT, DEFAULT_RECENT_LIMIT)
        set(value) {
            val clamped = value.coerceIn(MIN_RECENT_LIMIT, MAX_RECENT_LIMIT)
            prefs.edit().putInt(KEY_RECENT_LIMIT, clamped).apply()
        }

    companion object {
        const val DEFAULT_FOLDER_LIMIT = 5
        const val MIN_FOLDER_LIMIT = 1
        /** Generous upper bound — real users have at most ~10-15 folders. */
        const val MAX_FOLDER_LIMIT = 20

        const val DEFAULT_RECENT_LIMIT = 10
        const val MIN_RECENT_LIMIT = 1
        /** Page size from `/v1/workouts` is 10, so 25 = ≤3 fetches. */
        const val MAX_RECENT_LIMIT = 25

        /** The /v1/workouts page size — used to compute how many pages need
         *  to be fetched to satisfy a configured recent-workouts limit. */
        const val WORKOUTS_PAGE_SIZE = 10

        private const val KEY_FOLDER_LIMIT = "folder_list_limit"
        private const val KEY_RECENT_LIMIT = "recent_workouts_limit"
    }
}
