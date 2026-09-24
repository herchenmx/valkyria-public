package com.example.hevywatch

import com.example.hevywatch.data.api.HevyApiClient
import com.example.hevywatch.data.model.ActiveExercise
import com.example.hevywatch.data.model.ActiveSet
import com.example.hevywatch.data.model.ActiveWorkout
import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.workout.buildWorkoutPostRequestV2
import com.google.gson.JsonParser
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import com.example.hevywatch.data.api.HevyApiService

class ApiContractTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    // ── Private client headers ────────────────────────────────────────────────

    @Test
    fun `private client sends Authorization Bearer header`() = runTest {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))

        val service = buildPrivateService("test-access-token")
        runCatching { service.getWorkoutPrivate("w-1") }   // request fires even if response parsing fails

        val request = server.takeRequest()
        assertEquals("Bearer test-access-token", request.getHeader("Authorization"))
    }

    @Test
    fun `private client sends X-Api-Key header`() = runTest {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))

        val service = buildPrivateService("token")
        runCatching { service.getWorkoutPrivate("w-1") }

        val request = server.takeRequest()
        // Asserted against BuildConfig, not a literal: the key is a secret
        // (secrets.properties -> BuildConfig.HEVY_PRIVATE_API_KEY, written by
        // CI from the Actions secret of the same name), so a copy pinned here
        // would be both a leak and a second source of truth to forget to
        // update when Hevy rotates their client key.
        assertEquals(BuildConfig.HEVY_PRIVATE_API_KEY, request.getHeader("X-Api-Key"))
    }

    @Test
    fun `private client sends Hevy-Platform wearos header`() = runTest {
        server.enqueue(MockResponse().setBody("{}").setResponseCode(200))

        val service = buildPrivateService("token")
        runCatching { service.getWorkoutPrivate("w-1") }

        val request = server.takeRequest()
        assertEquals("wearos", request.getHeader("Hevy-Platform"))
    }

    // ── Workout POST body shape ───────────────────────────────────────────────

    @Test
    fun `POST v2 workout sends timestamps as integers not strings`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val service = buildPrivateService("token")
        val workout = sampleWorkout(startTimeMs = 1_000_000L)
        val request = buildWorkoutPostRequestV2(workout, endTimeMs = 2_000_000L)
        runCatching { service.postWorkoutPrivate(request) }

        val body = server.takeRequest().body.readUtf8()
        val workoutJson = JsonParser.parseString(body).asJsonObject.getAsJsonObject("workout")

        assertTrue(
            "start_time should be a number (integer), not a string",
            workoutJson.get("start_time").asJsonPrimitive.isNumber
        )
        assertTrue(
            "end_time should be a number (integer), not a string",
            workoutJson.get("end_time").asJsonPrimitive.isNumber
        )
        assertEquals(1_000L, workoutJson.get("start_time").asLong)
        assertEquals(2_000L, workoutJson.get("end_time").asLong)
    }

    @Test
    fun `POST v2 workout sends wearos_watch true`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val service = buildPrivateService("token")
        val request = buildWorkoutPostRequestV2(sampleWorkout(), 2_000_000L)
        runCatching { service.postWorkoutPrivate(request) }

        val body = server.takeRequest().body.readUtf8()
        val workoutJson = JsonParser.parseString(body).asJsonObject.getAsJsonObject("workout")

        assertTrue(workoutJson.get("wearos_watch").asBoolean)
        assertFalse(workoutJson.get("apple_watch").asBoolean)
    }

    @Test
    fun `POST v2 workout sends routine_id`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val service = buildPrivateService("token")
        val request = buildWorkoutPostRequestV2(sampleWorkout(routineId = "r-xyz"), 2_000_000L)
        runCatching { service.postWorkoutPrivate(request) }

        val body = server.takeRequest().body.readUtf8()
        val workoutJson = JsonParser.parseString(body).asJsonObject.getAsJsonObject("workout")

        assertEquals("r-xyz", workoutJson.get("routine_id").asString)
    }

    @Test
    fun `POST v2 workout sends null routine_id as null not absent`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val service = buildPrivateService("token")
        val request = buildWorkoutPostRequestV2(sampleWorkout(routineId = null), 2_000_000L)
        runCatching { service.postWorkoutPrivate(request) }

        val body = server.takeRequest().body.readUtf8()
        val workoutJson = JsonParser.parseString(body).asJsonObject.getAsJsonObject("workout")

        assertTrue("routine_id should be present even when null", workoutJson.has("routine_id"))
        assertTrue(workoutJson.get("routine_id").isJsonNull)
    }

    @Test
    fun `POST v2 workout sends workout_id as a UUID string`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val service = buildPrivateService("token")
        val request = buildWorkoutPostRequestV2(sampleWorkout(), 2_000_000L)
        runCatching { service.postWorkoutPrivate(request) }

        val body = server.takeRequest().body.readUtf8()
        val workoutJson = JsonParser.parseString(body).asJsonObject.getAsJsonObject("workout")
        val workoutId = workoutJson.get("workout_id").asString

        assertTrue("workout_id should look like a UUID", workoutId.matches(Regex(
            "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        )))
    }

    @Test
    fun `POST v2 workout includes exercise title`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200))

        val service = buildPrivateService("token")
        val request = buildWorkoutPostRequestV2(sampleWorkout(), 2_000_000L)
        runCatching { service.postWorkoutPrivate(request) }

        val body = server.takeRequest().body.readUtf8()
        val exercises = JsonParser.parseString(body)
            .asJsonObject.getAsJsonObject("workout")
            .getAsJsonArray("exercises")

        assertEquals("Squat", exercises[0].asJsonObject.get("title").asString)
    }

    // ── Refresh token request body ────────────────────────────────────────────

    @Test
    fun `refresh token sends correct body`() = runTest {
        server.enqueue(MockResponse()
            .setBody("""{"access_token":"new","refresh_token":"new_rt","expires_at":"2026-12-01T00:00:00Z"}""")
            .setResponseCode(200))

        val service = buildPrivateService("old-token")
        runCatching {
            service.refreshToken(
                "Bearer old-token",
                com.example.hevywatch.data.api.model.RefreshTokenRequest("my-refresh-token")
            )
        }

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        val json = JsonParser.parseString(body).asJsonObject

        assertEquals("my-refresh-token", json.get("refresh_token").asString)
        assertEquals("POST", request.method)
        assertTrue(request.path?.contains("auth/refresh_token") == true)
    }

    // ── Helpers ────────────────────────────────────────────────────────────────

    /**
     * Builds a private Retrofit service pointed at the local MockWebServer,
     * driven by the **real** [HevyApiClient.buildPrivateClient] interceptor
     * chain (same seam [HevyApiClientTest] uses; the factory is `internal`, so
     * same-module tests can reach it).
     *
     * This used to hand-roll a second OkHttp client that re-declared the
     * headers itself, which made every header assertion circular — the test
     * compared the test's own literals to the test's own literals and could
     * never have caught a change in [HevyApiClient]. `createPrivate` was
     * called and its result discarded, so the production chain was never
     * exercised at all.
     *
     * Only the base URL and the converter differ from `createPrivate`, and the
     * converter is deliberately kept on `serializeNulls()` to match it — the
     * "null routine_id is present, not absent" test depends on that.
     */
    private fun buildPrivateService(accessToken: String): HevyApiService =
        Retrofit.Builder()
            .baseUrl(server.url("/"))
            .client(HevyApiClient.buildPrivateClient(accessToken))
            .addConverterFactory(GsonConverterFactory.create(
                com.google.gson.GsonBuilder().serializeNulls().create()
            ))
            .build()
            .create(HevyApiService::class.java)

    private fun sampleWorkout(
        startTimeMs: Long = 1_000_000L,
        routineId: String? = "r-1"
    ) = ActiveWorkout(
        name = "Test Workout",
        startTimeMs = startTimeMs,
        routineId = routineId,
        exercises = listOf(
            ActiveExercise(
                exerciseTemplateId = "t1",
                title = "Squat",
                sets = listOf(
                    ActiveSet(setType = SetType.NORMAL, weightKg = 100f, reps = 10, completed = true)
                )
            )
        )
    )
}
