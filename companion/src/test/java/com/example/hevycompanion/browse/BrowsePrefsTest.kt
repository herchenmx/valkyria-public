package com.example.hevycompanion.browse

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the cross-session contract for the two browse filters: round-trips
 * cleanly and a corrupt prefs blob falls back to a fresh empty filter
 * rather than crashing the screen.
 */
@RunWith(RobolectricTestRunner::class)
class BrowsePrefsTest {

    private lateinit var prefs: BrowsePrefs

    @Before
    fun setUp() {
        prefs = BrowsePrefs(ApplicationProvider.getApplicationContext())
        prefs.clear()
    }

    @Test fun `default Hevy filter is empty`() {
        assertTrue(prefs.hevyFilter.isEmpty)
    }

    @Test fun `default M&M filter is empty`() {
        assertTrue(prefs.mmFilter.isEmpty)
    }

    @Test fun `Hevy filter round-trips through prefs`() {
        val saved = HevyExerciseListFilter(
            query = "bench",
            muscleGroups = setOf("chest"),
            equipment = setOf("barbell"),
            exerciseTypes = setOf("weight_and_reps"),
            levels = setOf("intermediate"),
            categories = setOf("compound"),
        )
        prefs.hevyFilter = saved

        // New BrowsePrefs reads from the same backing prefs.
        val reloaded = BrowsePrefs(ApplicationProvider.getApplicationContext()).hevyFilter
        assertEquals(saved, reloaded)
    }

    @Test fun `M&M filter round-trips through prefs`() {
        val saved = MmExerciseListFilter(
            query = "squat",
            areas = setOf("Legs"),
            equipment = setOf("Barbell"),
            categories = setOf("Compound"),
            types = setOf("strength"),
            movementPatterns = setOf("Squat"),
        )
        prefs.mmFilter = saved

        val reloaded = BrowsePrefs(ApplicationProvider.getApplicationContext()).mmFilter
        assertEquals(saved, reloaded)
    }

    @Test fun `corrupt Hevy prefs JSON falls back to empty filter`() {
        val raw = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("hevy_browse_prefs", android.content.Context.MODE_PRIVATE)
        raw.edit().putString("hevy_filter_json", "{not valid").commit()
        // Should not throw; should return a fresh empty filter.
        assertTrue(BrowsePrefs(ApplicationProvider.getApplicationContext()).hevyFilter.isEmpty)
    }

    @Test fun `corrupt M&M prefs JSON falls back to empty filter`() {
        val raw = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("hevy_browse_prefs", android.content.Context.MODE_PRIVATE)
        raw.edit().putString("mm_filter_json", "[broken").commit()
        assertTrue(BrowsePrefs(ApplicationProvider.getApplicationContext()).mmFilter.isEmpty)
    }

    @Test fun `clear wipes both filters`() {
        prefs.hevyFilter = HevyExerciseListFilter(query = "x")
        prefs.mmFilter = MmExerciseListFilter(query = "y")
        prefs.clear()
        assertTrue(prefs.hevyFilter.isEmpty)
        assertTrue(prefs.mmFilter.isEmpty)
    }

    @Test fun `default lastSource is HEVY`() {
        // Fresh-install users land on Hevy first because it's the saveable
        // catalog. Changing this default would silently re-route every new
        // installation through M&M, which is display-only.
        assertEquals(Source.HEVY, prefs.lastSource)
    }

    @Test fun `lastSource round-trips through prefs`() {
        prefs.lastSource = Source.MM
        assertEquals(Source.MM, BrowsePrefs(ApplicationProvider.getApplicationContext()).lastSource)
    }

    @Test fun `unknown lastSource enum string falls back to default`() {
        // Forward-compat: a future build with a new enum value should not
        // brick the Browser when it lands on an older binary.
        val raw = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("hevy_browse_prefs", android.content.Context.MODE_PRIVATE)
        raw.edit().putString("last_source", "FUTURE_SOURCE").commit()
        assertEquals(Source.DEFAULT, BrowsePrefs(ApplicationProvider.getApplicationContext()).lastSource)
    }

    @Test fun `default lastViewMode is LIST`() {
        assertEquals(ViewMode.LIST, prefs.lastViewMode)
    }

    @Test fun `lastViewMode round-trips through prefs`() {
        prefs.lastViewMode = ViewMode.MUSCLE_GRID
        assertEquals(
            ViewMode.MUSCLE_GRID,
            BrowsePrefs(ApplicationProvider.getApplicationContext()).lastViewMode,
        )
    }

    @Test fun `unknown lastViewMode enum string falls back to default`() {
        val raw = ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("hevy_browse_prefs", android.content.Context.MODE_PRIVATE)
        raw.edit().putString("last_view_mode", "UNKNOWN_MODE").commit()
        assertEquals(ViewMode.DEFAULT, BrowsePrefs(ApplicationProvider.getApplicationContext()).lastViewMode)
    }
}
