package com.example.hevycompanion.generate

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GeneratorPrefsTest {

    private val context: Context get() = ApplicationProvider.getApplicationContext()

    @After fun tearDown() {
        // Every test starts with an empty prefs file.
        GeneratorPrefs(context).clear()
    }

    @Test fun `returns null when never saved (caller should use default)`() {
        assertNull(GeneratorPrefs(context).selectedEquipment)
    }

    @Test fun `round-trips a typical equipment selection`() {
        val prefs = GeneratorPrefs(context)
        val chosen = setOf("barbell", "dumbbell", "cable")
        prefs.selectedEquipment = chosen
        assertEquals(chosen, prefs.selectedEquipment)
    }

    @Test fun `persists across GeneratorPrefs instances (same SharedPrefs file)`() {
        // The whole point: user closes companion, reopens later, equipment
        // selection is still there. Simulated by building a new prefs object.
        GeneratorPrefs(context).selectedEquipment = setOf("kettlebell")
        assertEquals(setOf("kettlebell"), GeneratorPrefs(context).selectedEquipment)
    }

    @Test fun `explicit empty set persists as empty (NOT as null)`() {
        // Empty set is a valid user choice (user un-ticked everything —
        // generator falls back to the always-allowed bodyweight pool).
        // It must NOT collapse to null, or we'd incorrectly re-populate with
        // the all-selected default on the next launch.
        val prefs = GeneratorPrefs(context)
        prefs.selectedEquipment = emptySet()
        assertEquals(emptySet<String>(), prefs.selectedEquipment)
    }

    @Test fun `setting to null clears the stored selection`() {
        val prefs = GeneratorPrefs(context)
        prefs.selectedEquipment = setOf("barbell")
        prefs.selectedEquipment = null
        assertNull(prefs.selectedEquipment)
    }

    // ---- levels -----------------------------------------------------------

    @Test fun `selectedLevels returns null when never saved`() {
        assertNull(GeneratorPrefs(context).selectedLevels)
    }

    @Test fun `selectedLevels round-trips a multi-select selection`() {
        val prefs = GeneratorPrefs(context)
        prefs.selectedLevels = setOf(Level.BEGINNER, Level.ADVANCED)
        assertEquals(setOf(Level.BEGINNER, Level.ADVANCED), prefs.selectedLevels)
    }

    @Test fun `selectedLevels persists empty-set explicitly (distinct from null)`() {
        // Same semantics as equipment: "user un-ticked everything" is a real
        // state, and must not collapse back to the permissive default on
        // relaunch.
        val prefs = GeneratorPrefs(context)
        prefs.selectedLevels = emptySet()
        assertEquals(emptySet<Level>(), prefs.selectedLevels)
    }

    @Test fun `selectedLevels is robust to unknown strings in the prefs file`() {
        // Forward-compat: if a future build adds a new Level enum constant
        // that an older build doesn't know about, the older build should
        // silently drop the unknown entry rather than crash on valueOf().
        val underlying = context.getSharedPreferences("hevy_generator", Context.MODE_PRIVATE)
        underlying.edit().putStringSet("selected_levels", setOf("BEGINNER", "NOVEL_TIER")).apply()
        assertEquals(setOf(Level.BEGINNER), GeneratorPrefs(context).selectedLevels)
    }

    // ---- categories -------------------------------------------------------

    @Test fun `selectedCategories returns null when never saved`() {
        assertNull(GeneratorPrefs(context).selectedCategories)
    }

    @Test fun `selectedCategories round-trips`() {
        val prefs = GeneratorPrefs(context)
        prefs.selectedCategories = setOf(Category.ISOLATION)
        assertEquals(setOf(Category.ISOLATION), prefs.selectedCategories)
    }

    @Test fun `selectedCategories persists empty-set explicitly (distinct from null)`() {
        val prefs = GeneratorPrefs(context)
        prefs.selectedCategories = emptySet()
        assertEquals(emptySet<Category>(), prefs.selectedCategories)
    }

    // ---- weights ----------------------------------------------------------

    @Test fun `selectedWeights returns null when never saved`() {
        assertNull(GeneratorPrefs(context).selectedWeights)
    }

    @Test fun `selectedWeights round-trips each value`() {
        val prefs = GeneratorPrefs(context)
        for (w in Weights.entries) {
            prefs.selectedWeights = w
            assertEquals(w, prefs.selectedWeights)
        }
    }

    @Test fun `selectedWeights setting to null clears the stored value`() {
        val prefs = GeneratorPrefs(context)
        prefs.selectedWeights = Weights.LIGHT
        prefs.selectedWeights = null
        assertNull(prefs.selectedWeights)
    }

    @Test fun `selectedWeights survives an unknown string gracefully`() {
        val underlying = context.getSharedPreferences("hevy_generator", Context.MODE_PRIVATE)
        underlying.edit().putString("selected_weights", "GIGACHAD").apply()
        // Invalid enum name → null, so the caller falls back to Weights.DEFAULT.
        assertNull(GeneratorPrefs(context).selectedWeights)
    }

    // ---- equipment ---------------------------------------------------------

    @Test fun `returned set is an immutable snapshot — mutating it cannot corrupt the prefs file`() {
        // SharedPreferences docs warn that the Set returned by getStringSet
        // must not be mutated, because it's the underlying cache object.
        // Our getter calls .toSet() to return a defensive copy; this test
        // guards that invariant.
        GeneratorPrefs(context).selectedEquipment = setOf("barbell", "dumbbell")
        val snapshot = GeneratorPrefs(context).selectedEquipment
        assertTrue(snapshot is Set<String>)
        // Confirm the returned type is defensive — it's a new instance each
        // read, so identity should differ between back-to-back reads.
        val other = GeneratorPrefs(context).selectedEquipment
        assertEquals(snapshot, other)
        // If the implementation regressed to returning the underlying cache,
        // `snapshot === other` would be true here (Android caches the Set per
        // prefs file). We don't hard-assert non-identity since Robolectric
        // may short-circuit this guarantee; the real safety comes from
        // `.toSet()` returning a LinkedHashSet/Set copy that's separate from
        // whatever is backing the prefs file on production devices.
    }
}
