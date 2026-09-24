package com.example.hevycompanion.data

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HevyExerciseAttrMapTest {

    @After fun tearDown() {
        HevyExerciseAttrMap.resetCacheForTest()
    }

    @Test fun `parses the compact JSON shape scripts_hevy_scrape_py writes`() {
        // The scraper uses json.dump(indent=0, separators=(",",":"), sort_keys=True).
        val json = """
            {
            "4F5866F8":{"category":"assistance-compound","goal":["strength","build_muscle","fat_loss"],"level":["beginner","intermediate","advanced"]},
            "79D0BB3A":{"category":"compound","goal":["strength","build_muscle","fat_loss"],"level":["beginner","intermediate","advanced"]}
            }
        """.trimIndent()
        val map = HevyExerciseAttrMap.parseJson(json)
        assertEquals(2, map.size)
        val a = map["4F5866F8"]!!
        assertEquals(listOf("beginner", "intermediate", "advanced"), a.level)
        assertEquals(listOf("strength", "build_muscle", "fat_loss"), a.goal)
        assertEquals("assistance-compound", a.category)
        assertEquals("compound", map["79D0BB3A"]!!.category)
    }

    @Test fun `lowercases every string value on the way in (hot path can skip re-lowercasing)`() {
        // Defensive: if Hevy ever publishes "Beginner" or "Compound" in different
        // casing, the filter side of the generator still matches cleanly.
        val json = """{"ABC":{"category":"COMPOUND","goal":["Strength"],"level":["Beginner","INTERMEDIATE"]}}"""
        val attrs = HevyExerciseAttrMap.parseJson(json)["ABC"]!!
        assertEquals(listOf("beginner", "intermediate"), attrs.level)
        assertEquals(listOf("strength"), attrs.goal)
        assertEquals("compound", attrs.category)
    }

    @Test fun `null fields collapse to empty lists and null category`() {
        // Shouldn't happen in practice (the scraper fills both lists even for
        // sparse entries), but the Gson layer must not crash if it does.
        val json = """{"XYZ":{"category":null,"goal":null,"level":null}}"""
        val attrs = HevyExerciseAttrMap.parseJson(json)["XYZ"]!!
        assertEquals(emptyList<String>(), attrs.level)
        assertEquals(emptyList<String>(), attrs.goal)
        assertNull(attrs.category)
    }

    @Test fun `missing keys are tolerated (treated as empty)`() {
        // A future-compat scenario: the scraper drops one of the three keys.
        // Load must not throw; we fall back to "no info for that field".
        val json = """{"XYZ":{"category":"isolation"}}"""
        val attrs = HevyExerciseAttrMap.parseJson(json)["XYZ"]!!
        assertEquals(emptyList<String>(), attrs.level)
        assertEquals(emptyList<String>(), attrs.goal)
        assertEquals("isolation", attrs.category)
    }

    @Test fun `real bundled asset parses cleanly and covers the catalog`() {
        // Reads the actual file scripts/hevy_scrape.py produces, proving the
        // shape written is the shape read. If either side drifts this test
        // catches it.
        val assetFile = File("src/main/assets/hevy_exercise_attrs.json")
        assertTrue(
            "bundled asset missing — did scripts/hevy_scrape.py get run? path: ${assetFile.absolutePath}",
            assetFile.exists(),
        )
        val map = HevyExerciseAttrMap.parseJson(assetFile.readText())
        // As of the 2026-04-22 scrape, the attrs table had ~429 entries (every
        // non-archived exercise). Allow wiggle room for Hevy adding/removing.
        assertTrue(
            "expected 300+ entries in bundled attrs map, got ${map.size}",
            map.size >= 300,
        )
        // Every category string we see must be one of the three Hevy ships
        // (or null for the handful of entries without a category).
        val knownCategories = setOf("compound", "isolation", "assistance-compound", null)
        for ((id, attrs) in map) {
            assertTrue(
                "unexpected category '${attrs.category}' on id=$id",
                attrs.category in knownCategories,
            )
            // Level values must be one of three — forward-compat, but current
            // catalog uses only these three.
            val knownLevels = setOf("beginner", "intermediate", "advanced")
            for (lvl in attrs.level) {
                assertTrue(
                    "unexpected level '$lvl' on id=$id",
                    lvl in knownLevels,
                )
            }
        }
    }

    @Test fun `malformed JSON bubbles out of parseJson (loadFromAssets catches it)`() {
        // Mirror of HevyImageUrlMapTest's parser-throws check: the production
        // asset loader swallows exceptions to `emptyMap()` so a corrupt asset
        // never crashes the UI — we just want to prove the parser's contract
        // is "throw on garbage".
        val ex = runCatching { HevyExerciseAttrMap.parseJson("not json at all {[") }.exceptionOrNull()
        assertTrue("expected parseJson to throw on garbage input, got: $ex", ex != null)
    }
}
