package com.example.hevycompanion.data

import android.content.Context
import com.example.hevycompanion.util.GsonHolder
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Fetches every page of `/v1/exercise_templates` and caches the result to
 * SharedPreferences with a 7-day TTL. The full catalog is ~500 items (tiny,
 * a few hundred KB of JSON) so we keep it all in memory — no streaming / no
 * pagination at the UI layer.
 *
 * The Hevy API caps pageSize at 10, so a full fetch is ~50 HTTP calls. The
 * cache makes this a once-a-week cost.
 */
class ExerciseTemplateRepo(
    context: Context,
    private val api: HevyPublicApi = buildHevyPublicApi(),
    private val apiKey: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val gson = GsonHolder.gson

    /** Returns the cached catalog if it's still fresh, else null. */
    fun cached(): List<ExerciseTemplate>? {
        val savedAt = prefs.getLong(KEY_SAVED_AT, 0L)
        if (savedAt == 0L || clock() - savedAt > TTL_MS) return null
        val json = prefs.getString(KEY_JSON, null) ?: return null
        return try {
            val type = object : TypeToken<List<ExerciseTemplate>>() {}.type
            gson.fromJson<List<ExerciseTemplate>>(json, type)
        } catch (_: Exception) {
            null
        }
    }

    /** Fetches all pages and overwrites the cache. Bucket-D item 22 —
     *  page 1 discovers the total page count; pages 2..pageCount fan out
     *  in parallel up to [MAX_PARALLEL_PAGES] concurrent requests. On a
     *  ~500-item catalog (pageSize=10 → ~50 pages) this cuts the cold-fetch
     *  wall clock by 4-5× on a warm BT-tether without hammering the API.
     *
     *  Per-page protection comes from the OkHttp client's connect/read/write
     *  timeouts (10 s/15 s/15 s, see [buildHevyPublicApi]) — those bound any
     *  single hung response, so a Kotlin-level wrapper is redundant. The
     *  null-coalesce on [exerciseTemplates] guards against a malformed
     *  response shape that would otherwise crash the loop.
     *
     *  Page ordering is preserved (pages sorted by index before concatenation)
     *  so the resulting catalog matches the sequential-fetch behaviour a
     *  caller relies on for stable cache ordering. */
    suspend fun refresh(): List<ExerciseTemplate> = coroutineScope {
        // Page 1 gives us pageCount before we can fan out.
        val page1 = api.getExerciseTemplates(apiKey = apiKey, page = 1, pageSize = 10)
        val totalPages = page1.pageCount
        val remainingPages = if (totalPages > 1) (2..totalPages).toList() else emptyList()

        val semaphore = Semaphore(MAX_PARALLEL_PAGES)
        val remaining: List<Pair<Int, List<ExerciseTemplate>>> = remainingPages
            .map { pageIndex ->
                async {
                    semaphore.withPermit {
                        val resp = api.getExerciseTemplates(
                            apiKey = apiKey, page = pageIndex, pageSize = 10
                        )
                        pageIndex to (resp.exerciseTemplates ?: emptyList())
                    }
                }
            }
            .awaitAll()

        // Re-assemble in page order so callers see a stable, deterministic
        // catalog (parallel awaitAll preserves list order, but the pageIndex
        // sort here also survives future refactors that don't).
        val all = buildList {
            addAll(page1.exerciseTemplates ?: emptyList())
            remaining
                .sortedBy { it.first }
                .forEach { (_, items) -> addAll(items) }
        }
        prefs.edit()
            .putString(KEY_JSON, gson.toJson(all))
            .putLong(KEY_SAVED_AT, clock())
            .apply()
        all
    }

    /** Cache if fresh, otherwise fetch + cache. */
    suspend fun getOrFetch(): List<ExerciseTemplate> = cached() ?: refresh()

    companion object {
        private const val PREFS = "exercise_template_catalog"
        private const val KEY_JSON = "catalog_json"
        private const val KEY_SAVED_AT = "saved_at_ms"
        private const val TTL_MS = 7L * 24 * 60 * 60 * 1000
        /** Bucket-D item 22 — cap on concurrent /exercise_templates page
         *  fetches. Hevy's public API doesn't publish a rate limit; 5 is
         *  a conservative middle that gives a big speedup on the ~50-page
         *  fetch without saturating the phone's tether. */
        private const val MAX_PARALLEL_PAGES = 5
    }
}
