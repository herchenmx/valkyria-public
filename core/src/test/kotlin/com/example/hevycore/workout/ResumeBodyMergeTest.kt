package com.example.hevycore.workout

import com.google.gson.JsonParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResumeBodyMergeTest {

    private fun obj(json: String) = JsonParser.parseString(json).asJsonObject

    private fun body(vararg pairs: Pair<String, String>): com.google.gson.JsonObject {
        val inner = pairs.joinToString(",") { "\"${it.first}\":${it.second}" }
        return obj("""{"workout":{$inner}}""")
    }

    @Test
    fun `a field we do not model is carried across`() {
        // The case this exists for: Hevy adds something to the workout, our DTO
        // does not know it, and the typed round trip silently dropped it.
        val merged = ResumeBodyMerge.carryUnmodelledFields(
            body("title" to "\"Leg Day\""),
            obj("""{"title":"Leg Day","location":{"name":"Gym"}}"""),
        )
        val workout = merged.getAsJsonObject("workout")
        assertTrue(workout.has("location"))
        assertEquals("Gym", workout.getAsJsonObject("location").get("name").asString)
    }

    @Test
    fun `our value wins over the originals`() {
        // The typed body is the shape Hevy accepts on every normal POST, so it
        // stays authoritative — a resume must not revert the merged exercises
        // or the freshly generated workout_id to the original's values.
        val merged = ResumeBodyMerge.carryUnmodelledFields(
            body("title" to "\"New title\"", "workout_id" to "\"new-uuid\""),
            obj("""{"title":"Old title","workout_id":"old-uuid"}"""),
        )
        val workout = merged.getAsJsonObject("workout")
        assertEquals("New title", workout.get("title").asString)
        assertEquals("new-uuid", workout.get("workout_id").asString)
    }

    @Test
    fun `server-owned fields are never carried`() {
        val merged = ResumeBodyMerge.carryUnmodelledFields(
            body("title" to "\"Leg Day\""),
            obj(
                """{"id":"abc","short_id":"s1","user_id":"u1","username":"maria",
                    "created_at":"2026-01-01","updated_at":"2026-01-02","index":3,
                    "like_count":5,"comment_count":2,"average_heart_rate":132}"""
            ),
        )
        val workout = merged.getAsJsonObject("workout")
        for (k in ResumeBodyMerge.SERVER_OWNED) {
            assertFalse("$k must not be echoed into the replacement", workout.has(k))
        }
    }

    @Test
    fun `a null original leaves the body untouched`() {
        // Raw capture is best-effort; without it the merge must be a no-op so it
        // can never make the existing behaviour worse.
        val original = body("title" to "\"Leg Day\"")
        val merged = ResumeBodyMerge.carryUnmodelledFields(original, null)
        assertEquals(1, merged.getAsJsonObject("workout").entrySet().size)
        assertEquals("Leg Day", merged.getAsJsonObject("workout").get("title").asString)
    }

    @Test
    fun `carriedKeys reports exactly what the typed round trip was dropping`() {
        val keys = ResumeBodyMerge.carriedKeys(
            body("title" to "\"Leg Day\""),
            obj("""{"title":"Leg Day","id":"abc","location":{},"venue_id":"v1"}"""),
        )
        assertEquals(listOf("location", "venue_id"), keys)
    }

    @Test
    fun `withheldKeys names what the exclusion list held back`() {
        val withheld = ResumeBodyMerge.withheldKeys(
            obj("""{"id":"abc","location":{},"created_at":"x","title":"t"}""")
        )
        assertEquals(listOf("created_at", "id"), withheld)
    }

    @Test
    fun `a missing envelope is tolerated`() {
        val weird = obj("""{"not_workout":{}}""")
        val merged = ResumeBodyMerge.carryUnmodelledFields(weird, obj("""{"location":{}}"""))
        assertFalse(merged.has("workout"))
    }
}
