package com.example.hevywatch

import androidx.test.core.app.ApplicationProvider
import com.example.hevywatch.data.api.model.RoutineFolderResponse
import com.example.hevywatch.data.model.Routine
import com.example.hevywatch.data.store.ExerciseTemplateStore
import com.example.hevywatch.data.store.FolderCacheStore
import com.example.hevywatch.data.store.RoutineCacheStore
import com.example.hevywatch.data.store.WorkoutHistoryStore
import com.example.hevywatch.util.GsonHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Cache stores are silent on JSON corruption (each falls back to an empty
 * collection so a single malformed write doesn't brick the relevant screen),
 * which is the right default but also exactly the behavior most likely to
 * regress unnoticed. These tests pin both the round-trip and the corrupt-
 * fallback contracts in one place.
 */
@RunWith(RobolectricTestRunner::class)
class CacheStoresTest {

    private val ctx get() = ApplicationProvider.getApplicationContext<android.content.Context>()

    // ── FolderCacheStore ─────────────────────────────────────────────────────

    @Test fun `FolderCacheStore round-trips a non-empty list`() {
        val store = FolderCacheStore(ctx)
        store.clear()
        val folders = listOf(
            RoutineFolderResponse(id = "1", title = "Push", index = 0),
            RoutineFolderResponse(id = "2", title = "Pull", index = 1),
        )
        store.save(folders)

        val loaded = FolderCacheStore(ctx).load()
        assertEquals(2, loaded.size)
        assertEquals("Push", loaded[0].title)
    }

    @Test fun `FolderCacheStore returns empty list when never saved`() {
        FolderCacheStore(ctx).clear()
        assertTrue(FolderCacheStore(ctx).load().isEmpty())
    }

    @Test fun `FolderCacheStore tolerates corrupt JSON without throwing`() {
        // Hand-write garbage into the underlying prefs to simulate a broken disk write.
        val prefs = ctx.getSharedPreferences("folder_cache", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("folders_json", "{not json[").commit()
        // Should fall back to empty rather than crash.
        assertTrue(FolderCacheStore(ctx).load().isEmpty())
    }

    // ── RoutineCacheStore ────────────────────────────────────────────────────

    @Test fun `RoutineCacheStore round-trips a list and clears`() {
        val store = RoutineCacheStore(ctx)
        store.clear()
        val routines = listOf(
            Routine(
                id = "r1",
                title = "Day A",
                notes = null,
                folderId = "f1",
                exercises = emptyList(),
                updatedAt = "2025-01-01T00:00:00Z",
            ),
        )
        store.save(routines)
        assertEquals(1, RoutineCacheStore(ctx).load().size)

        store.clear()
        assertTrue(RoutineCacheStore(ctx).load().isEmpty())
    }

    @Test fun `RoutineCacheStore corrupt JSON falls back to empty`() {
        val prefs = ctx.getSharedPreferences("routine_cache", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("routines_json", "[{missing").commit()
        assertTrue(RoutineCacheStore(ctx).load().isEmpty())
    }

    // ── WorkoutHistoryStore ──────────────────────────────────────────────────

    @Test fun `WorkoutHistoryStore round-trips routineLastWorkoutAt`() {
        val store = WorkoutHistoryStore(ctx)
        store.routineLastWorkoutAt = mapOf("r1" to "2025-01-01T00:00:00Z")
        assertEquals(
            mapOf("r1" to "2025-01-01T00:00:00Z"),
            WorkoutHistoryStore(ctx).routineLastWorkoutAt,
        )
    }

    @Test fun `WorkoutHistoryStore needsFullFetch is true when never run`() {
        val store = WorkoutHistoryStore(ctx)
        store.lastFullFetchAtMs = 0L
        assertTrue(store.needsFullFetch())
    }

    @Test fun `WorkoutHistoryStore needsFullFetch is false within 30 days`() {
        val store = WorkoutHistoryStore(ctx)
        store.lastFullFetchAtMs = System.currentTimeMillis() - 1_000L
        assertEquals(false, store.needsFullFetch())
    }

    @Test fun `WorkoutHistoryStore corrupt routineLastWorkoutAt JSON yields empty map`() {
        val prefs = ctx.getSharedPreferences("workout_history", android.content.Context.MODE_PRIVATE)
        prefs.edit().putString("routine_last_workout_at", "definitely_not_json").commit()
        assertTrue(WorkoutHistoryStore(ctx).routineLastWorkoutAt.isEmpty())
    }

    // ── ExerciseTemplateStore ────────────────────────────────────────────────

    @Test fun `ExerciseTemplateStore round-trips equipment + muscle group`() {
        val store = ExerciseTemplateStore(ctx)
        store.save(
            equipment = mapOf("e1" to "barbell", "e2" to null),
            muscleGroup = mapOf("e1" to "chest", "e2" to "back"),
        )
        val snapshot = ExerciseTemplateStore(ctx).loadFresh()
        assertNotNull(snapshot)
        assertEquals("barbell", snapshot!!.equipment["e1"])
        assertNull(snapshot.equipment["e2"])
        assertEquals("back", snapshot.muscleGroup["e2"])
    }

    @Test fun `ExerciseTemplateStore loadFresh returns null when nothing saved`() {
        // Use a fresh prefs file path by clearing the shared one.
        ctx.getSharedPreferences("exercise_templates", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        assertNull(ExerciseTemplateStore(ctx).loadFresh())
    }

    @Test fun `ExerciseTemplateStore loadFresh tolerates corrupt JSON`() {
        val prefs = ctx.getSharedPreferences("exercise_templates", android.content.Context.MODE_PRIVATE)
        prefs.edit()
            .putString("equipment_json", "{")
            .putString("muscle_group_json", "{")
            .putLong("saved_at_ms", System.currentTimeMillis())
            .commit()
        assertNull(ExerciseTemplateStore(ctx).loadFresh())
    }

    // Sanity: shared Gson is still wired (regression check for the singleton refactor).
    @Test fun `GsonHolder produces a non-null shared instance`() {
        assertNotNull(GsonHolder.gson)
        // Any subsequent reference returns the same instance.
        val a = GsonHolder.gson
        val b = GsonHolder.gson
        assertEquals(a, b)
    }
}
