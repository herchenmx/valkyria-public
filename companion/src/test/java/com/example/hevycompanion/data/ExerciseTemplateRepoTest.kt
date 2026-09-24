package com.example.hevycompanion.data

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class ExerciseTemplateRepoTest {

    private lateinit var server: MockWebServer
    private lateinit var repo: ExerciseTemplateRepo
    private var fakeNow = 1_700_000_000_000L

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        repo = ExerciseTemplateRepo(
            context = ApplicationProvider.getApplicationContext(),
            api = buildHevyPublicApi(server.url("/").toString()),
            apiKey = "test-key",
            clock = { fakeNow },
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
        // Wipe SharedPrefs so the next test starts clean.
        ApplicationProvider.getApplicationContext<android.content.Context>()
            .getSharedPreferences("exercise_template_catalog", 0)
            .edit().clear().commit()
    }

    @Test
    fun `refresh walks every page and concatenates templates`() = runTest {
        // 2 pages, 2 items on page 1, 1 on page 2.
        server.enqueue(MockResponse().setBody("""
            {"page":1,"page_count":2,"exercise_templates":[
              {"id":"A","title":"Bench Press","primary_muscle_group":"chest"},
              {"id":"B","title":"Squat","primary_muscle_group":"quadriceps"}
            ]}
        """.trimIndent()))
        server.enqueue(MockResponse().setBody("""
            {"page":2,"page_count":2,"exercise_templates":[
              {"id":"C","title":"Deadlift","primary_muscle_group":"hamstrings"}
            ]}
        """.trimIndent()))

        val all = repo.refresh()

        assertEquals(3, all.size)
        assertEquals(listOf("A", "B", "C"), all.map { it.id })
    }

    @Test
    fun `cached returns the refreshed list before the TTL lapses`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"page":1,"page_count":1,"exercise_templates":[
              {"id":"A","title":"Bench Press","primary_muscle_group":"chest"}
            ]}
        """.trimIndent()))
        repo.refresh()

        // 6 days later — still within 7-day TTL.
        fakeNow += 6L * 24 * 60 * 60 * 1000
        val cached = repo.cached()
        assertNotNull(cached)
        assertEquals(1, cached!!.size)
        assertEquals("A", cached[0].id)
    }

    @Test
    fun `cached returns null after the TTL lapses`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"page":1,"page_count":1,"exercise_templates":[
              {"id":"A","title":"Bench Press","primary_muscle_group":"chest"}
            ]}
        """.trimIndent()))
        repo.refresh()

        // 8 days later — past the 7-day TTL.
        fakeNow += 8L * 24 * 60 * 60 * 1000
        assertNull(repo.cached())
    }

    @Test
    fun `cached returns null when nothing has been fetched`() {
        assertNull(repo.cached())
    }

    @Test
    fun `request includes api-key header`() = runTest {
        server.enqueue(MockResponse().setBody("""
            {"page":1,"page_count":1,"exercise_templates":[]}
        """.trimIndent()))
        repo.refresh()
        val req = server.takeRequest()
        assertEquals("test-key", req.getHeader("api-key"))
    }
}
