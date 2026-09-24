package com.example.hevycompanion.trends

import com.example.hevycompanion.data.ExerciseTemplate
import com.example.hevycompanion.data.ExerciseTemplatesResponse
import com.example.hevycompanion.data.ExerciseHistoryResponse
import com.example.hevycompanion.data.HevyPublicApi
import com.example.hevycompanion.data.PostRoutinesRequestBody
import com.example.hevycompanion.data.RoutineDetail
import com.example.hevycompanion.data.RoutineDetailWrapper
import com.example.hevycompanion.data.RoutineFoldersResponse
import com.example.hevycompanion.data.WorkoutDetail
import com.example.hevycompanion.data.WorkoutDetailExercise
import com.example.hevycompanion.data.WorkoutDetailSet
import com.example.hevycompanion.data.WorkoutExerciseRef
import com.example.hevycompanion.data.WorkoutPutRequest
import com.example.hevycompanion.data.WorkoutSummary
import com.example.hevycompanion.data.WorkoutsResponse
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins how the trends data set is assembled: the 12-month window, the
 * PO-folder scope, per-routine grouping in chronological order, and the
 * detail-fetch fallback for a list payload that arrives without sets.
 */
class RoutineTrendRepoTest {

    private val poFolder = 2525049L
    private val otherFolder = 999L

    private val nowMs = 1_800_000_000_000L
    private val dayMs = 24L * 60 * 60 * 1000

    /** ISO timestamp [daysAgo] days before [nowMs]. */
    private fun iso(daysAgo: Long): String =
        java.time.Instant.ofEpochMilli(nowMs - daysAgo * dayMs).toString()

    private fun summary(
        id: String,
        routineId: String?,
        daysAgo: Long,
        withSets: Boolean = true,
    ) = WorkoutSummary(
        id = id,
        title = "Session $id",
        routineId = routineId,
        startTime = iso(daysAgo),
        exercises = listOf(
            WorkoutExerciseRef(
                exerciseTemplateId = "EX1",
                title = "Squat",
                sets = if (withSets) {
                    listOf(WorkoutDetailSet(type = "normal", weightKg = 100f, reps = 10))
                } else {
                    emptyList()
                },
            )
        ),
    )

    /** A fake public API serving [pages] of workouts and a fixed routine table. */
    private class FakeApi(
        private val pages: List<List<WorkoutSummary>>,
        private val routines: Map<String, RoutineDetail>,
        private val details: Map<String, WorkoutDetail> = emptyMap(),
    ) : HevyPublicApi {
        var workoutPagesRequested = 0; private set
        var detailsRequested = 0; private set

        override suspend fun getWorkouts(apiKey: String, page: Int, pageSize: Int): WorkoutsResponse {
            workoutPagesRequested++
            return WorkoutsResponse(
                workouts = pages.getOrElse(page - 1) { emptyList() },
                page = page,
                pageCount = pages.size,
            )
        }

        override suspend fun getRoutine(apiKey: String, routineId: String): RoutineDetailWrapper {
            val routine = routines[routineId] ?: throw IllegalStateException("no such routine")
            return RoutineDetailWrapper(routine)
        }

        override suspend fun getWorkout(apiKey: String, workoutId: String): WorkoutDetail {
            detailsRequested++
            return details[workoutId] ?: throw IllegalStateException("no such workout")
        }

        // ── Unused by the trends repo ────────────────────────────────────────
        override suspend fun getExerciseTemplates(apiKey: String, page: Int, pageSize: Int): ExerciseTemplatesResponse =
            error("unused")

        override suspend fun getRoutineFolders(apiKey: String, page: Int, pageSize: Int): RoutineFoldersResponse =
            error("unused")

        override suspend fun postRoutine(
            apiKey: String,
            body: PostRoutinesRequestBody,
        ): retrofit2.Response<Unit> = error("unused")

        override suspend fun getExerciseHistory(apiKey: String, exerciseTemplateId: String, page: Int): ExerciseHistoryResponse =
            error("unused")

        override suspend fun updateWorkout(
            apiKey: String,
            workoutId: String,
            body: WorkoutPutRequest,
        ): retrofit2.Response<Unit> = error("unused")
    }

    private fun routine(id: String, title: String, folderId: Long) =
        RoutineDetail(id = id, title = title, folderId = folderId)

    private fun repoFor(
        api: HevyPublicApi,
        templates: List<ExerciseTemplate> = emptyList(),
    ) = RoutineTrendRepo(
        api = api,
        apiKey = "key",
        templates = { templates },
        poFolderIds = setOf(poFolder.toString()),
    )

    @Test
    fun `keeps only PO-folder routines and groups by routine`() = runTest {
        val api = FakeApi(
            pages = listOf(
                listOf(
                    summary("w1", "upper", daysAgo = 2),
                    summary("w2", "lower", daysAgo = 5),
                    summary("w3", "upper", daysAgo = 9),
                    summary("w4", "freestyle", daysAgo = 11),
                    summary("w5", routineId = null, daysAgo = 12),
                )
            ),
            routines = mapOf(
                "upper" to routine("upper", "POP 1: Upper", poFolder),
                "lower" to routine("lower", "POP 2: Lower", poFolder),
                "freestyle" to routine("freestyle", "Random", otherFolder),
            ),
        )

        val trends = repoFor(api).load(nowMs = nowMs)

        assertEquals(listOf("POP 1: Upper", "POP 2: Lower"), trends.map { it.title })
        assertEquals(listOf("w3", "w1"), trends.first().workouts.map { it.id })
    }

    @Test
    fun `workouts are ordered oldest first so deltas face backwards`() = runTest {
        val api = FakeApi(
            pages = listOf(
                listOf(
                    summary("newest", "upper", daysAgo = 1),
                    summary("middle", "upper", daysAgo = 20),
                    summary("oldest", "upper", daysAgo = 100),
                )
            ),
            routines = mapOf("upper" to routine("upper", "POP 1: Upper", poFolder)),
        )

        val workouts = repoFor(api).load(nowMs = nowMs).single().workouts
        assertEquals(listOf("oldest", "middle", "newest"), workouts.map { it.id })
    }

    @Test
    fun `paging stops at the window and drops older workouts`() = runTest {
        val api = FakeApi(
            pages = listOf(
                listOf(summary("recent", "upper", daysAgo = 10)),
                listOf(summary("edge", "upper", daysAgo = 364)),
                // Crossing the cutoff ends the walk: page 4 is never requested.
                listOf(summary("stale", "upper", daysAgo = 400)),
                listOf(summary("ancient", "upper", daysAgo = 500)),
            ),
            routines = mapOf("upper" to routine("upper", "POP 1: Upper", poFolder)),
        )

        val repo = repoFor(api)
        val workouts = repo.load(nowMs = nowMs).single().workouts

        assertEquals(listOf("edge", "recent"), workouts.map { it.id })
        assertEquals(3, api.workoutPagesRequested)
    }

    @Test
    fun `a routine that cannot be resolved is treated as non-PO`() = runTest {
        val api = FakeApi(
            pages = listOf(listOf(summary("w1", "gone", daysAgo = 1))),
            routines = emptyMap(),
        )
        assertTrue(repoFor(api).load(nowMs = nowMs).isEmpty())
    }

    @Test
    fun `exercise titles fall back to the catalog when the payload omits them`() = runTest {
        val api = FakeApi(
            pages = listOf(
                listOf(
                    WorkoutSummary(
                        id = "w1",
                        title = "Session",
                        routineId = "upper",
                        startTime = iso(1),
                        exercises = listOf(WorkoutExerciseRef(exerciseTemplateId = "EX1")),
                    )
                )
            ),
            routines = mapOf("upper" to routine("upper", "POP 1: Upper", poFolder)),
        )
        val catalog = listOf(ExerciseTemplate(id = "EX1", title = "Squat (Machine)"))

        val trend = repoFor(api, templates = catalog).load(nowMs = nowMs).single()
        assertEquals("Squat (Machine)", trend.workouts.single().exercises.single().title)
    }

    // ── Hydration fallback ───────────────────────────────────────────────────

    @Test
    fun `a list payload without sets is topped up from the detail endpoint`() = runTest {
        val api = FakeApi(
            pages = listOf(listOf(summary("w1", "upper", daysAgo = 1, withSets = false))),
            routines = mapOf("upper" to routine("upper", "POP 1: Upper", poFolder)),
            details = mapOf(
                "w1" to WorkoutDetail(
                    id = "w1",
                    title = "Session",
                    routineId = "upper",
                    startTime = iso(1),
                    exercises = listOf(
                        WorkoutDetailExercise(
                            title = "Squat",
                            exerciseTemplateId = "EX1",
                            sets = listOf(WorkoutDetailSet(type = "normal", weightKg = 100f, reps = 10)),
                        )
                    ),
                )
            ),
        )

        val repo = repoFor(api)
        val trend = repo.load(nowMs = nowMs).single()
        assertTrue("the list gave us no sets", !trend.isHydrated)

        val hydrated = repo.hydrate(trend)
        assertTrue(hydrated.isHydrated)
        assertEquals(
            1000.0,
            WorkoutTotals.of(hydrated.workouts.single(), SetScope.ALL, VolumeRule()).volumeKg,
            0.001,
        )
    }

    @Test
    fun `hydration is skipped when the list already carried sets`() = runTest {
        val api = FakeApi(
            pages = listOf(listOf(summary("w1", "upper", daysAgo = 1))),
            routines = mapOf("upper" to routine("upper", "POP 1: Upper", poFolder)),
        )

        val repo = repoFor(api)
        val trend = repo.load(nowMs = nowMs).single()
        repo.hydrate(trend)
        assertEquals(0, api.detailsRequested)
    }
}
