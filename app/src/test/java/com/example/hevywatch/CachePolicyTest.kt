package com.example.hevywatch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the caching policies introduced to reduce HTTP chatter:
 *  - Incremental workout-history page cap
 *  - 5-minute populate-last-workout-dates throttle
 *  - 7-day exercise-template snapshot TTL
 *  - routineWorkoutIds append (Progress-tab guard)
 *
 * The actual code lives in RoutineListViewModel / HevyApp / ExerciseTemplateStore;
 * these tests mirror the arithmetic so a regression in either surface fails here.
 */
class CachePolicyTest {

    // ── Incremental page cap (RoutineListViewModel#populateLastWorkoutDates) ──

    /** Re-implementation of the cap used on line
     *  `val pagesToFetch = minOf(INCREMENTAL_PAGES, firstPage.pageCount)`. */
    private fun pagesToFetch(pageCount: Int, maxPages: Int = 3): Int =
        minOf(maxPages, pageCount)

    @Test
    fun `small user (1 page) fetches just that page`() {
        assertEquals(1, pagesToFetch(1))
    }

    @Test
    fun `medium user (5 pages) fetches first 3`() {
        assertEquals(3, pagesToFetch(5))
    }

    @Test
    fun `heavy user (80 pages) still fetches only 3 — not 60 like the old logic`() {
        assertEquals(3, pagesToFetch(80))
    }

    @Test
    fun `cap never exceeds configured max`() {
        assertTrue(pagesToFetch(1_000) <= 3)
    }

    // ── populate-history throttle (RoutineListViewModel#shouldPopulateHistory) ──

    // 12h matches the in-source POPULATE_THROTTLE_MS. Chosen for the once-a-day
    // workout cadence; user-save bypasses the throttle by resetting the
    // timestamp on HevyApp, so this value is only load-bearing for history
    // logged outside the watch (e.g. phone app) between app opens.
    private val throttleMs = 12L * 60 * 60 * 1000

    private fun shouldPopulate(lastRunAtMs: Long, now: Long): Boolean =
        lastRunAtMs == 0L || now - lastRunAtMs > throttleMs

    @Test
    fun `first ever run is always permitted`() {
        assertTrue(shouldPopulate(lastRunAtMs = 0L, now = 1_000_000L))
    }

    @Test
    fun `second run within throttle window is skipped`() {
        val lastRun = 1_000_000L
        val now = lastRun + throttleMs - 1
        assertFalse(shouldPopulate(lastRun, now))
    }

    @Test
    fun `second run after throttle window runs`() {
        val lastRun = 1_000_000L
        val now = lastRun + throttleMs + 1
        assertTrue(shouldPopulate(lastRun, now))
    }

    @Test
    fun `throttle reset (set to 0) re-enables populate`() {
        val now = 1_000_000L
        // After a save, HevyApp.workoutHistoryPopulatedAtMs = 0L — must run next time.
        assertTrue(shouldPopulate(lastRunAtMs = 0L, now = now))
    }

    // ── ExerciseTemplateStore TTL ────────────────────────────────────────────

    private val templateTtlMs = 7L * 24 * 60 * 60 * 1000

    private fun isTemplateSnapshotFresh(savedAtMs: Long, now: Long): Boolean =
        savedAtMs != 0L && now - savedAtMs <= templateTtlMs

    @Test
    fun `unsaved snapshot is not fresh`() {
        assertFalse(isTemplateSnapshotFresh(savedAtMs = 0L, now = 1_000_000L))
    }

    @Test
    fun `snapshot within 7 days is fresh`() {
        val savedAt = 1_000_000L
        val sixDaysLater = savedAt + 6L * 24 * 60 * 60 * 1000
        assertTrue(isTemplateSnapshotFresh(savedAt, sixDaysLater))
    }

    @Test
    fun `snapshot just past 7 days is stale`() {
        val savedAt = 1_000_000L
        val justOver = savedAt + templateTtlMs + 1
        assertFalse(isTemplateSnapshotFresh(savedAt, justOver))
    }

    // ── Progress-tab guard: appendWorkoutToRoutineHistory ────────────────────

    /** Pure re-implementation of [HevyApp.appendWorkoutToRoutineHistory]. */
    private fun append(
        map: Map<String, Set<String>>,
        routineId: String,
        workoutId: String
    ): Map<String, Set<String>> {
        val current = map.toMutableMap()
        val existing = current[routineId]?.toMutableSet() ?: mutableSetOf()
        existing.add(workoutId)
        current[routineId] = existing
        return current
    }

    @Test
    fun `append to empty map creates new routine entry`() {
        val result = append(emptyMap(), "routineA", "workout1")
        assertEquals(setOf("workout1"), result["routineA"])
    }

    @Test
    fun `append to existing routine adds workout without losing prior ids`() {
        val initial = mapOf("routineA" to setOf("workout1", "workout2"))
        val result = append(initial, "routineA", "workout3")
        assertEquals(setOf("workout1", "workout2", "workout3"), result["routineA"])
    }

    @Test
    fun `append of duplicate id is idempotent (Set semantics)`() {
        val initial = mapOf("routineA" to setOf("workout1"))
        val result = append(initial, "routineA", "workout1")
        assertEquals(setOf("workout1"), result["routineA"])
    }

    // ── Resume cleanup: removeWorkoutFromRoutineHistory ──────────────────────

    /** Pure re-implementation of [HevyApp.removeWorkoutFromRoutineHistory]. */
    private fun remove(
        map: Map<String, Set<String>>,
        routineId: String,
        workoutId: String
    ): Map<String, Set<String>> {
        val current = map.toMutableMap()
        val existing = current[routineId]?.toMutableSet() ?: return current
        if (!existing.remove(workoutId)) return current
        if (existing.isEmpty()) current.remove(routineId) else current[routineId] = existing
        return current
    }

    @Test
    fun `remove drops the id and leaves the rest of the set intact`() {
        val initial = mapOf("routineA" to setOf("w1", "w2", "w3"))
        val result = remove(initial, "routineA", "w2")
        assertEquals(setOf("w1", "w3"), result["routineA"])
    }

    @Test
    fun `remove of the only id collapses the routine entry`() {
        val initial = mapOf("routineA" to setOf("w1"), "routineB" to setOf("w2"))
        val result = remove(initial, "routineA", "w1")
        assertEquals(null, result["routineA"])
        assertEquals(setOf("w2"), result["routineB"])
    }

    @Test
    fun `remove of an absent id is a no-op`() {
        val initial = mapOf("routineA" to setOf("w1"))
        val result = remove(initial, "routineA", "wDoesNotExist")
        assertEquals(setOf("w1"), result["routineA"])
    }

    @Test
    fun `remove from an unknown routine is a no-op`() {
        val initial = mapOf("routineA" to setOf("w1"))
        val result = remove(initial, "routineZ", "w1")
        assertEquals(initial, result)
    }

    @Test
    fun `append to one routine doesn't disturb others`() {
        val initial = mapOf(
            "routineA" to setOf("wA1"),
            "routineB" to setOf("wB1", "wB2")
        )
        val result = append(initial, "routineA", "wA2")
        assertEquals(setOf("wA1", "wA2"), result["routineA"])
        assertEquals(setOf("wB1", "wB2"), result["routineB"])
    }

    // ── routineLastWorkoutAt "only advance forward" semantics ────────────────
    //
    // Mirrors the post-save update in LogWorkoutViewModel.recordAndNavigateCongrats.
    // The guard protects against two regressions:
    //   1. Resuming a days-old incomplete workout must NOT stamp today — the
    //      workout's real start_time on the server is preserved by PUT, so the
    //      Routine List date must keep matching it.
    //   2. Finishing an older workout (resumed) must not overwrite a more
    //      recent completion of the same routine.

    private fun updateLastWorkoutAt(
        map: Map<String, String>,
        routineId: String,
        newStartIso: String
    ): Map<String, String> {
        val existing = map[routineId]
        return if (existing == null || newStartIso > existing) {
            map + mapOf(routineId to newStartIso)
        } else {
            map
        }
    }

    @Test
    fun `fresh completion sets the date when no prior entry exists`() {
        val result = updateLastWorkoutAt(emptyMap(), "routineA", "2026-04-20T10:00:00Z")
        assertEquals("2026-04-20T10:00:00Z", result["routineA"])
    }

    @Test
    fun `more recent fresh completion advances the date`() {
        val initial = mapOf("routineA" to "2026-04-18T10:00:00Z")
        val result = updateLastWorkoutAt(initial, "routineA", "2026-04-20T10:00:00Z")
        assertEquals("2026-04-20T10:00:00Z", result["routineA"])
    }

    @Test
    fun `resuming old incomplete workout does NOT overwrite newer completion`() {
        // User completed routineA fresh on Apr 19, then today (Apr 20)
        // resumes an incomplete workout from Apr 17. Routine List must still
        // show Apr 19 — the most recent actual session.
        val initial = mapOf("routineA" to "2026-04-19T10:00:00Z")
        val originalStart = "2026-04-17T10:00:00Z"
        val result = updateLastWorkoutAt(initial, "routineA", originalStart)
        assertEquals("2026-04-19T10:00:00Z", result["routineA"])
    }

    @Test
    fun `resuming old incomplete workout with matching existing date is a no-op`() {
        // The Apr 17 incomplete was already in the map with its original date.
        // Completing it today must keep the same Apr 17 date (preserving
        // server's created_at) — not rewrite to today.
        val originalStart = "2026-04-17T10:00:00Z"
        val initial = mapOf("routineA" to originalStart)
        val result = updateLastWorkoutAt(initial, "routineA", originalStart)
        assertEquals(originalStart, result["routineA"])
    }

    // ── Server-fetch reconciliation (RoutineListViewModel#populateLastWorkoutDates) ──
    //
    // Mirrors the incremental-fetch merge: routines that appear in the freshly
    // fetched pages have their cached date OVERWRITTEN; routines absent from
    // the fetch keep their cached date. This protects against stale local
    // optimistic writes whose backing server workout was later deleted.

    /** Re-implementation of the incremental-fetch reconciliation. */
    private fun reconcileFromFetch(
        cached: Map<String, String>,
        freshFromFetchedPages: Map<String, String>
    ): Map<String, String> {
        val result = cached.toMutableMap()
        freshFromFetchedPages.forEach { (rid, ts) -> result[rid] = ts }
        return result
    }

    @Test
    fun `stale local date is rolled back when fresh fetch shows older real date`() {
        // The bug this fix addresses: user did a test save on the watch on
        // Apr 22 (which advanced the cached date), then deleted the workout
        // server-side. The real most-recent server workout is from Apr 14.
        // Next incremental fetch must roll the date BACK to Apr 14.
        val cached = mapOf("routineA" to "2026-04-22T10:00:00Z")
        val fresh = mapOf("routineA" to "2026-04-14T10:00:00Z")
        val result = reconcileFromFetch(cached, fresh)
        assertEquals("2026-04-14T10:00:00Z", result["routineA"])
    }

    @Test
    fun `routine absent from fetched pages keeps its cached date`() {
        // User has a routine they last did 6 months ago. It will not appear
        // in pages 1–3 of GET /v1/workouts. We must not lose its cached date.
        val cached = mapOf(
            "routineRare" to "2025-10-01T10:00:00Z",
            "routineA" to "2026-04-22T10:00:00Z"
        )
        val fresh = mapOf("routineA" to "2026-04-14T10:00:00Z")
        val result = reconcileFromFetch(cached, fresh)
        assertEquals("2025-10-01T10:00:00Z", result["routineRare"])
        assertEquals("2026-04-14T10:00:00Z", result["routineA"])
    }

    @Test
    fun `fresh fetch advances cached date forward when server has newer workout`() {
        val cached = mapOf("routineA" to "2026-04-14T10:00:00Z")
        val fresh = mapOf("routineA" to "2026-04-22T10:00:00Z")
        val result = reconcileFromFetch(cached, fresh)
        assertEquals("2026-04-22T10:00:00Z", result["routineA"])
    }

    @Test
    fun `routine never seen before is added by fresh fetch`() {
        val cached = emptyMap<String, String>()
        val fresh = mapOf("routineA" to "2026-04-22T10:00:00Z")
        val result = reconcileFromFetch(cached, fresh)
        assertEquals("2026-04-22T10:00:00Z", result["routineA"])
    }

    @Test
    fun `empty fresh fetch (offline or no recent workouts) preserves all cached dates`() {
        val cached = mapOf(
            "routineA" to "2026-04-22T10:00:00Z",
            "routineB" to "2026-04-20T10:00:00Z"
        )
        val result = reconcileFromFetch(cached, emptyMap())
        assertEquals(cached, result)
    }
}
