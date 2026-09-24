package com.example.hevycompanion.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.example.hevycompanion.util.GsonHolder
import com.google.gson.reflect.TypeToken

/**
 * `exercise_template_id` → Hevy CDN thumbnail-URL mapping.
 *
 * Bundled in `assets/hevy_image_urls.json`, extracted from Hevy's web-app
 * JS bundle by `scripts/hevy_scrape.py`. The public Hevy REST API itself
 * exposes no image fields on `ExerciseTemplate`, but hevy.com's frontend
 * carries the entire catalog (including `thumbnail_url`) as a static JSON
 * import — see `state/stores/exerciseTemplates.ts` in their web source and
 * the investigation in PRD-COMPANION-APP.md.
 *
 * Used as a fallback when `LiftoffSlug.resolveAvatarResId` returns 0 —
 * typically exercises with equipment-qualified titles like
 * `"Bench Press (Cable)"` that Liftoff doesn't ship a variant for.
 *
 * Lazy-loaded (the JSON parses on first [urlFor] call) and memoized for the
 * process lifetime; the map is ~412 entries (~50 KB) so keeping it resident
 * is cheap.
 */
object HevyImageUrlMap {
    private const val ASSET_PATH = "hevy_image_urls.json"

    @Volatile private var cache: Map<String, String>? = null

    /** Returns the bundled CDN URL for the given exercise-template id, or null. */
    fun urlFor(context: Context, exerciseTemplateId: String): String? {
        val map = cache ?: synchronized(this) {
            cache ?: loadFromAssets(context).also { cache = it }
        }
        return map[exerciseTemplateId]
    }

    /** Exposed so downstream screens can check "do we have ANY image?" without
     *  actually loading the image. */
    fun size(context: Context): Int = (cache ?: run {
        val m = loadFromAssets(context)
        cache = m
        m
    }).size

    private fun loadFromAssets(context: Context): Map<String, String> =
        runCatching {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        }.mapCatching(::parseJson).getOrDefault(emptyMap())

    // Internal hooks so unit tests can exercise parsing without an Android Context.
    @VisibleForTesting
    internal fun parseJson(json: String): Map<String, String> =
        GsonHolder.gson.fromJson(json, object : TypeToken<Map<String, String>>() {}.type)

    @VisibleForTesting
    internal fun resetCacheForTest() { cache = null }

    @VisibleForTesting
    internal fun primeCacheForTest(map: Map<String, String>) { cache = map }
}
