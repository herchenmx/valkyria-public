package com.example.hevywatch

import androidx.test.core.app.ApplicationProvider
import com.example.hevywatch.data.store.DisplayLimitsStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the user-tunable list caps on the Routine Folder List screen. Both
 * setters clamp into the allowed range so a corrupted prefs blob can't
 * surface an empty list (0) or a multi-page fetch storm (9999).
 */
@RunWith(RobolectricTestRunner::class)
class DisplayLimitsStoreTest {

    private lateinit var store: DisplayLimitsStore

    @Before
    fun setUp() {
        store = DisplayLimitsStore(ApplicationProvider.getApplicationContext())
        // Reset to defaults so prior tests can't leak state.
        store.folderListLimit = DisplayLimitsStore.DEFAULT_FOLDER_LIMIT
        store.recentWorkoutsLimit = DisplayLimitsStore.DEFAULT_RECENT_LIMIT
    }

    @Test
    fun `defaults match the documented values on a fresh store`() {
        val fresh = DisplayLimitsStore(ApplicationProvider.getApplicationContext())
        assertEquals(DisplayLimitsStore.DEFAULT_FOLDER_LIMIT, fresh.folderListLimit)
        assertEquals(DisplayLimitsStore.DEFAULT_RECENT_LIMIT, fresh.recentWorkoutsLimit)
        // Sanity-pin the current defaults so a change is intentional.
        assertEquals(5, fresh.folderListLimit)
        assertEquals(10, fresh.recentWorkoutsLimit)
    }

    @Test
    fun `folderListLimit clamps to MIN when set below`() {
        store.folderListLimit = 0
        assertEquals(DisplayLimitsStore.MIN_FOLDER_LIMIT, store.folderListLimit)
        store.folderListLimit = -7
        assertEquals(DisplayLimitsStore.MIN_FOLDER_LIMIT, store.folderListLimit)
    }

    @Test
    fun `folderListLimit clamps to MAX when set above`() {
        store.folderListLimit = 9999
        assertEquals(DisplayLimitsStore.MAX_FOLDER_LIMIT, store.folderListLimit)
    }

    @Test
    fun `folderListLimit round-trips and persists across new instances`() {
        store.folderListLimit = 8
        assertEquals(8, store.folderListLimit)
        val store2 = DisplayLimitsStore(ApplicationProvider.getApplicationContext())
        assertEquals(8, store2.folderListLimit)
    }

    @Test
    fun `recentWorkoutsLimit clamps to MIN when set below`() {
        store.recentWorkoutsLimit = 0
        assertEquals(DisplayLimitsStore.MIN_RECENT_LIMIT, store.recentWorkoutsLimit)
    }

    @Test
    fun `recentWorkoutsLimit clamps to MAX when set above`() {
        store.recentWorkoutsLimit = 9999
        assertEquals(DisplayLimitsStore.MAX_RECENT_LIMIT, store.recentWorkoutsLimit)
    }

    @Test
    fun `recentWorkoutsLimit round-trips and persists across new instances`() {
        store.recentWorkoutsLimit = 17
        assertEquals(17, store.recentWorkoutsLimit)
        val store2 = DisplayLimitsStore(ApplicationProvider.getApplicationContext())
        assertEquals(17, store2.recentWorkoutsLimit)
    }

    @Test
    fun `WORKOUTS_PAGE_SIZE matches the v1 workouts page size`() {
        // Sanity-pin: /v1/workouts returns exactly 10 per page, so the
        // pagesNeeded math in RoutineFolderListViewModel.fetchRecents
        // depends on this matching the API contract.
        assertEquals(10, DisplayLimitsStore.WORKOUTS_PAGE_SIZE)
    }
}
