package com.example.hevycompanion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HevyVideoUrlMapTest {

    @After fun tearDown() {
        HevyVideoUrlMap.resetCacheForTest()
    }

    @Test fun `parses the compact JSON shape scripts_hevy_scrape_py writes`() {
        val json = """
            {
            "052938CA":"https://d2l9nsnmtah87f.cloudfront.net/exercise-assets/00251201-Barbell-Bench-Press_Chest.mp4",
            "D04AC939":"https://d2l9nsnmtah87f.cloudfront.net/exercise-assets/00431201-Barbell-Full-Squat_Thighs.mp4"
            }
        """.trimIndent()
        val map = HevyVideoUrlMap.parseJson(json)
        assertEquals(2, map.size)
        assertTrue(map["052938CA"]!!.contains("Barbell-Bench-Press"))
        assertTrue(map["D04AC939"]!!.endsWith(".mp4"))
    }

    @Test fun `unknown id returns null, not empty string`() {
        val primed = mapOf("ABC" to "https://example.com/a.mp4")
        HevyVideoUrlMap.primeCacheForTest(primed)
        assertEquals("https://example.com/a.mp4", primed["ABC"])
        assertNull(primed["XYZ"])
    }

    @Test fun `real bundled asset parses cleanly and covers a reasonable chunk of the catalog`() {
        val assetFile = File("src/main/assets/hevy_video_urls.json")
        assertTrue(
            "bundled asset missing — did scripts/hevy_scrape.py get run? path: ${assetFile.absolutePath}",
            assetFile.exists(),
        )
        val map = HevyVideoUrlMap.parseJson(assetFile.readText())
        // As of the 2026-04-22 scrape, 412 entries had a url. Allow wiggle
        // room for Hevy adding / retiring exercises over time.
        assertTrue(
            "expected 300+ entries in bundled map, got ${map.size}",
            map.size >= 300,
        )
        // Overwhelmingly mp4 (~98%); a handful of .jpg stills for cardio
        // placeholders (Air Bike, Jump Rope, Boxing, Dead Hang, Clean,
        // Standing Calf Raise). Anything else would be a surprise.
        val cdnRe = Regex("""https://.*(cloudfront\.net|amazonaws\.com).*\.(mp4|jpg|png)$""")
        for ((id, url) in map) {
            assertTrue("bad url for id=$id: $url", cdnRe.matches(url))
        }
        val mp4 = map.values.count { it.endsWith(".mp4") }
        assertTrue(
            "expected the vast majority of entries to be mp4, got $mp4 / ${map.size}",
            mp4 >= map.size * 9 / 10,
        )
    }

    @Test fun `malformed JSON throws so loadFromAssets can swallow it`() {
        val ex = runCatching { HevyVideoUrlMap.parseJson("not json at all {[") }.exceptionOrNull()
        assertTrue("expected parseJson to throw on garbage input, got: $ex", ex != null)
    }
}
