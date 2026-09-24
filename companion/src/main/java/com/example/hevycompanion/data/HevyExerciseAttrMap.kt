package com.example.hevycompanion.data

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.example.hevycompanion.util.GsonHolder
import com.google.gson.reflect.TypeToken

/**
 * `exercise_template_id` → exercise attributes bundled in
 * `assets/hevy_exercise_attrs.json`, extracted from Hevy's web-app JS bundle
 * by `scripts/hevy_scrape.py`.
 *
 * The public Hevy REST API (`/v1/exercise_templates`) does not return `level`,
 * `goal`, or `category` on each template, but the web app's static catalog
 * carries them on every entry — see the investigation in PRD-COMPANION-APP.md
 * for the image/video asset pair. We reuse the same mechanism: scrape at build
 * time, ship a trimmed side table, join by id at runtime.
 *
 * Consumed by the workout generator for its Level / Category filters.
 *
 * Lazy-loaded (parsed on first call) and memoized for the process lifetime.
 * The asset is ~53 KB / ~429 entries, so keeping it resident is trivial.
 */
object HevyExerciseAttrMap {
    private const val ASSET_PATH = "hevy_exercise_attrs.json"

    @Volatile private var cache: Map<String, HevyExerciseAttrs>? = null

    /** Returns attributes for a given exercise-template id, or null if unknown. */
    fun attrsFor(context: Context, exerciseTemplateId: String): HevyExerciseAttrs? =
        load(context)[exerciseTemplateId]

    /** Full snapshot of the bundled map — useful for tests / pre-filtering. */
    fun all(context: Context): Map<String, HevyExerciseAttrs> = load(context)

    private fun load(context: Context): Map<String, HevyExerciseAttrs> {
        cache?.let { return it }
        return synchronized(this) {
            cache ?: loadFromAssets(context).also { cache = it }
        }
    }

    private fun loadFromAssets(context: Context): Map<String, HevyExerciseAttrs> =
        runCatching {
            context.assets.open(ASSET_PATH).bufferedReader().use { it.readText() }
        }.mapCatching(::parseJson).getOrDefault(emptyMap())

    @VisibleForTesting
    internal fun parseJson(json: String): Map<String, HevyExerciseAttrs> {
        // Gson parses into the nullable-fields DTO (since scraper output
        // may omit keys or pass nulls), then we normalize into the strict
        // public data class with lowercased values + empty-list defaults.
        val type = object : TypeToken<Map<String, AttrsDto>>() {}.type
        val raw: Map<String, AttrsDto?> = GsonHolder.gson.fromJson(json, type)
        return raw.mapValues { (_, v) ->
            HevyExerciseAttrs(
                level = v?.level?.map { it.lowercase() } ?: emptyList(),
                goal = v?.goal?.map { it.lowercase() } ?: emptyList(),
                category = v?.category?.lowercase(),
            )
        }
    }

    // Lenient DTO used only for Gson deserialization — the raw JSON may have
    // null lists or missing keys, and we don't want that flexibility leaking
    // into the public [HevyExerciseAttrs] API.
    private data class AttrsDto(
        val level: List<String>? = null,
        val goal: List<String>? = null,
        val category: String? = null,
    )

    @VisibleForTesting
    internal fun resetCacheForTest() { cache = null }

    @VisibleForTesting
    internal fun primeCacheForTest(map: Map<String, HevyExerciseAttrs>) { cache = map }
}

/**
 * The three scraped fields we care about for generator filtering. All string
 * values are lowercased by [HevyExerciseAttrMap.parseJson] on load.
 *
 * Nullable lists in the raw JSON become empty lists here so callers don't
 * have to do `attrs.level ?: emptyList()` at every site.
 */
data class HevyExerciseAttrs(
    val level: List<String>,
    val goal: List<String>,
    val category: String?,
)
