package com.example.hevycompanion.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.example.hevycompanion.util.GsonHolder
import com.google.gson.reflect.TypeToken

/**
 * `exercise_template_id` → Hevy CDN demo-clip URL mapping.
 *
 * Bundled in `assets/hevy_video_urls.json`, extracted from Hevy's web-app
 * JS bundle by `scripts/hevy_scrape.py`. Most values are `.mp4` demo loops
 * (the same clip Hevy plays on its exercise-detail page); a handful of
 * cardio placeholders (Air Bike, Boxing, Jump Rope, …) ship `.jpg` stills
 * under the same `url` field — callers check the extension to pick the
 * right widget.
 *
 * Used by the long-press preview overlay — see `ExerciseAvatarPreview`.
 * Lazy-loaded and memoized for the process lifetime; ~412 entries / ~46 KB.
 */
object HevyVideoUrlMap {
    private const val ASSET_PATH = "hevy_video_urls.json"

    @Volatile private var cache: Map<String, String>? = null

    /** Returns the bundled CDN URL for the given exercise-template id, or null. */
    fun urlFor(context: Context, exerciseTemplateId: String): String? {
        val map = cache ?: synchronized(this) {
            cache ?: loadFromAssets(context).also { cache = it }
        }
        return map[exerciseTemplateId]
    }

    private fun loadFromAssets(context: Context): Map<String, String> =
        runCatching {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        }.mapCatching(::parseJson).getOrDefault(emptyMap())

    @VisibleForTesting
    internal fun parseJson(json: String): Map<String, String> =
        GsonHolder.gson.fromJson(json, object : TypeToken<Map<String, String>>() {}.type)

    @VisibleForTesting
    internal fun resetCacheForTest() { cache = null }

    @VisibleForTesting
    internal fun primeCacheForTest(map: Map<String, String>) { cache = map }
}
