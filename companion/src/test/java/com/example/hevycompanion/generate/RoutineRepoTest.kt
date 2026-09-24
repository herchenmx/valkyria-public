package com.example.hevycompanion.generate

import com.example.hevycompanion.data.PostRoutinesRequestBody
import com.example.hevycompanion.data.PostRoutinesRequestExercise
import com.example.hevycompanion.data.PostRoutinesRequestRoutine
import com.example.hevycompanion.data.PostRoutinesRequestSet
import com.example.hevycompanion.data.RepRange
import com.example.hevycompanion.data.buildHevyPublicApi
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RoutineRepoTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: RoutineRepo

    @Before fun setUp() {
        server = MockWebServer()
        server.start()
        val api = buildHevyPublicApi(server.url("/").toString())
        repo = RoutineRepo(apiKey = "fake-key", api = api)
    }

    @After fun tearDown() { server.shutdown() }

    // ---- fetchAllFolders -----------------------------------------------

    @Test fun `fetchAllFolders walks every page until page equals pageCount`() = runTest {
        // 2 pages, 2 folders per page, 3 total after concat.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"page":1,"page_count":2,"routine_folders":[
                  {"id":10,"index":0,"title":"Push"},
                  {"id":11,"index":1,"title":"Pull"}
                ]}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"page":2,"page_count":2,"routine_folders":[
                  {"id":12,"index":2,"title":"Legs"}
                ]}"""
            )
        )

        val folders = repo.fetchAllFolders()
        assertEquals(listOf(10L, 11L, 12L), folders.map { it.id })
        assertEquals(listOf("Push", "Pull", "Legs"), folders.map { it.title })

        // Both requests carry the api-key header and the pageSize=10 query (the API caps at 10).
        val first = server.takeRequest()
        assertEquals("fake-key", first.getHeader("api-key"))
        assertTrue(first.path!!.contains("pageSize=10"))
        assertTrue(first.path!!.contains("page=1"))
        val second = server.takeRequest()
        assertTrue(second.path!!.contains("page=2"))
    }

    @Test fun `fetchAllFolders stops early when a page comes back empty`() = runTest {
        // Defensive: if the API claims page_count=5 but the 2nd page is empty
        // we shouldn't loop forever appending empty lists.
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"page":1,"page_count":5,"routine_folders":[
                  {"id":1,"index":0,"title":"Only"}
                ]}"""
            )
        )
        server.enqueue(
            MockResponse().setResponseCode(200).setBody(
                """{"page":2,"page_count":5,"routine_folders":[]}"""
            )
        )

        val folders = repo.fetchAllFolders()
        assertEquals(listOf(1L), folders.map { it.id })
    }

    // ---- createRoutine -------------------------------------------------

    @Test fun `createRoutine on 201 returns Success`() = runTest {
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{}"""))
        val body = minimalBody()
        val result = repo.createRoutine(body)
        assertTrue(result is CreateRoutineResult.Success)
    }

    @Test fun `createRoutine maps 400 403 and generic HTTP errors to distinct results`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"bad"}"""))
        server.enqueue(MockResponse().setResponseCode(403).setBody(""))
        server.enqueue(MockResponse().setResponseCode(500).setBody("oops"))

        val bad = repo.createRoutine(minimalBody())
        val limit = repo.createRoutine(minimalBody())
        val server500 = repo.createRoutine(minimalBody())

        assertTrue("Expected InvalidBody, got $bad", bad is CreateRoutineResult.InvalidBody)
        assertTrue("Expected RoutineLimitReached, got $limit", limit is CreateRoutineResult.RoutineLimitReached)
        assertTrue("Expected HttpError, got $server500", server500 is CreateRoutineResult.HttpError)
        assertEquals(500, (server500 as CreateRoutineResult.HttpError).code)
    }

    @Test fun `createRoutine serialises folder_id null explicitly in the JSON body`() = runTest {
        // Critical: Gson by default DROPS null fields, but the Hevy API treats
        // the presence of a null folder_id as the "default folder" signal. If
        // the key goes missing the API 400s. This test pins the buildHevyPublicApi
        // Gson-with-serializeNulls config.
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{}"""))
        repo.createRoutine(
            PostRoutinesRequestBody(
                routine = PostRoutinesRequestRoutine(
                    title = "My Routine",
                    folderId = null,
                    notes = "",
                    exercises = emptyList(),
                )
            )
        )
        val req = server.takeRequest()
        val sentJson = req.body.readUtf8()
        val routine = JSONObject(sentJson).getJSONObject("routine")
        assertTrue(
            "folder_id key must be present in POST body even when null — got $sentJson",
            routine.has("folder_id"),
        )
        assertTrue(
            "folder_id must serialise as JSON null — got $sentJson",
            routine.isNull("folder_id"),
        )
    }

    @Test fun `createRoutine posts the full body shape documented in the Hevy API`() = runTest {
        // End-to-end: construct a body mirroring the API's own curl example
        // (slightly trimmed — one exercise, one set) and assert every field
        // the docs call out survives the serialiser.
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{}"""))
        repo.createRoutine(
            PostRoutinesRequestBody(
                routine = PostRoutinesRequestRoutine(
                    title = "April Leg Day",
                    folderId = 42L,
                    notes = "Focus on form over weight.",
                    exercises = listOf(
                        PostRoutinesRequestExercise(
                            exerciseTemplateId = "D04AC939",
                            supersetId = null,
                            restSeconds = 90,
                            notes = "Stay slow and controlled.",
                            sets = listOf(
                                PostRoutinesRequestSet(
                                    type = "normal",
                                    weightKg = 100f,
                                    reps = 10,
                                    repRange = RepRange(start = 8, end = 12),
                                )
                            )
                        )
                    ),
                )
            )
        )

        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertEquals("/v1/routines", req.path)
        assertEquals("fake-key", req.getHeader("api-key"))

        val routine = JSONObject(req.body.readUtf8()).getJSONObject("routine")
        assertEquals("April Leg Day", routine.getString("title"))
        assertEquals(42L, routine.getLong("folder_id"))
        assertEquals("Focus on form over weight.", routine.getString("notes"))

        val ex = routine.getJSONArray("exercises").getJSONObject(0)
        assertEquals("D04AC939", ex.getString("exercise_template_id"))
        assertTrue(ex.isNull("superset_id"))
        assertEquals(90, ex.getInt("rest_seconds"))

        val set = ex.getJSONArray("sets").getJSONObject(0)
        assertEquals("normal", set.getString("type"))
        assertEquals(100.0, set.getDouble("weight_kg"), 0.001)
        assertEquals(10, set.getInt("reps"))
        assertTrue(set.isNull("distance_meters"))
        val range = set.getJSONObject("rep_range")
        assertEquals(8, range.getInt("start"))
        assertEquals(12, range.getInt("end"))
    }

    @Test fun `createRoutine bodyweight exercise sends weight_kg null not zero`() = runTest {
        // Push-ups etc. come out of the generator with weightKg = 0f, but the
        // user's spec is "leave blank for bodyweight" → we translate blank to
        // null so Hevy doesn't record a literal 0 kg (which would mess with
        // the 1RM / volume calculations in the Hevy app). Pinned here so a
        // future refactor can't accidentally coerce the null back to 0.
        server.enqueue(MockResponse().setResponseCode(201).setBody("""{}"""))
        repo.createRoutine(
            PostRoutinesRequestBody(
                routine = PostRoutinesRequestRoutine(
                    title = "Calisthenics",
                    folderId = null,
                    notes = "",
                    exercises = listOf(
                        PostRoutinesRequestExercise(
                            exerciseTemplateId = "PUSHUP",
                            restSeconds = 60,
                            sets = listOf(PostRoutinesRequestSet(weightKg = null, reps = 10))
                        )
                    ),
                )
            )
        )
        val set = JSONObject(server.takeRequest().body.readUtf8())
            .getJSONObject("routine")
            .getJSONArray("exercises").getJSONObject(0)
            .getJSONArray("sets").getJSONObject(0)
        assertTrue("weight_kg should serialise as null", set.isNull("weight_kg"))
    }

    // ---- helpers ----------------------------------------------------

    private fun minimalBody(): PostRoutinesRequestBody = PostRoutinesRequestBody(
        routine = PostRoutinesRequestRoutine(
            title = "X",
            folderId = null,
            notes = "",
            exercises = emptyList(),
        )
    )

    @Suppress("unused")
    private fun assertUnused() { assertNull(null) }  // keeps the import set stable
}
