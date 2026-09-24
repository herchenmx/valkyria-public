package com.example.hevycompanion.generate

import com.example.hevycompanion.data.HevyPublicApi
import com.example.hevycompanion.data.PostRoutinesRequestBody
import com.example.hevycompanion.data.RoutineFolder
import com.example.hevycompanion.data.buildHevyPublicApi

/**
 * Thin wrapper over [HevyPublicApi] for the Save-Routine flow.
 *
 * GETs are paginated (pageSize capped at 10 by the public API) so we walk the
 * pages until `page == page_count`. POST returns a raw [retrofit2.Response] so
 * the caller can distinguish 201 (success), 400 (validation) and 403 (routine
 * limit exceeded) — the docs call those out as distinct failure modes.
 */
class RoutineRepo(
    private val apiKey: String,
    private val api: HevyPublicApi = buildHevyPublicApi(),
) {
    /** Walks every page of `/v1/routine_folders` and returns the full list. */
    suspend fun fetchAllFolders(): List<RoutineFolder> {
        val collected = mutableListOf<RoutineFolder>()
        var page = 1
        while (true) {
            val resp = api.getRoutineFolders(apiKey = apiKey, page = page, pageSize = PAGE_SIZE)
            collected += resp.routineFolders
            if (page >= resp.pageCount || resp.routineFolders.isEmpty()) break
            page++
        }
        return collected
    }

    /**
     * POSTs a routine. Returns a [CreateRoutineResult] so the caller can show
     * a specific message per failure mode without caring about Retrofit types.
     */
    suspend fun createRoutine(body: PostRoutinesRequestBody): CreateRoutineResult {
        val resp = api.postRoutine(apiKey = apiKey, body = body)
        return when (resp.code()) {
            201, 200 -> CreateRoutineResult.Success
            400 -> CreateRoutineResult.InvalidBody(resp.errorBody()?.string().orEmpty())
            403 -> CreateRoutineResult.RoutineLimitReached
            else -> CreateRoutineResult.HttpError(resp.code(), resp.errorBody()?.string().orEmpty())
        }
    }

    companion object {
        // Hevy caps /v1/routine_folders at 10 per page (documented, enforced as 400).
        private const val PAGE_SIZE = 10
    }
}

sealed class CreateRoutineResult {
    data object Success : CreateRoutineResult()
    data class InvalidBody(val body: String) : CreateRoutineResult()
    data object RoutineLimitReached : CreateRoutineResult()
    data class HttpError(val code: Int, val body: String) : CreateRoutineResult()
}
