package com.example.hevycompanion.wear

import android.content.Context
import android.util.Log
import com.example.hevycompanion.BuildConfig
import com.example.hevycompanion.util.GsonHolder
import com.example.hevycore.wear.WearMessagePaths
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import com.google.gson.annotations.SerializedName
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Fetches the latest promoted Hevy-App-Version / Hevy-App-Build pair from the
 * repo's `api-versions/active.json`, persists it locally via
 * [HevyApiVersionPrefs], and pushes the new pair to the watch via the
 * `/api_version` MessageAPI path if the values changed since the last sync.
 *
 * The companion runs this once per cold start ([HevyCompanionApp.onCreate]);
 * failures are silent because the watch already has a working pair baked in
 * at build time and can fall back to the [SetApiVersionReceiver] ADB override
 * as a manual escape hatch.
 */
object HevyApiVersionSync {

    private const val TAG = "HevyApiVersionSync"

    private const val ACTIVE_JSON_URL =
        "https://raw.githubusercontent.com/herchenmx/hevy-for-wearos-2.45/main/api-versions/active.json"

    private const val FETCH_TIMEOUT_S = 10L
    private const val SEND_TIMEOUT_S = 10L

    sealed class Result {
        /** Fetched values were forwarded to at least one watch node. */
        data class Pushed(val versionName: String, val versionCode: String, val nodeCount: Int) : Result()

        /** Fetched values were received but no watch node is currently
         *  connected. The local cache was still updated; the next cold start
         *  will retry the push so offline watches eventually self-heal. */
        data class FetchedNoWatch(val versionName: String, val versionCode: String) : Result()

        /** Network or parse error. Caller should ignore (next launch retries). */
        data class Failed(val message: String) : Result()
    }

    /** Wire format for api-versions/active.json. */
    private data class ActiveJson(
        @SerializedName("version_name") val versionName: String?,
        @SerializedName("version_code") val versionCode: String?,
    )

    /**
     * Runs the fetch → diff → push pipeline. Caller dispatches to a background
     * scope; this function dispatches to IO internally anyway and is safe to
     * call from anywhere.
     */
    suspend fun syncAndPush(
        context: Context,
        httpClient: OkHttpClient = defaultHttpClient(),
    ): Result = withContext(Dispatchers.IO) {
        val prefs = HevyApiVersionPrefs(context)
        // Every fetch outcome is recorded, not just the good ones. Previously a
        // failure returned Result.Failed and nothing persisted it, so the UI
        // kept displaying the last successful date — indistinguishable from a
        // healthy sync. An unauthenticated read of this private repo answers
        // 404 rather than 401, which is exactly the kind of failure that hid
        // here for months.
        val fetched = try {
            fetchActive(httpClient)
        } catch (e: Exception) {
            Log.w(TAG, "fetch failed: $e")
            val reason = e.message ?: "fetch failed"
            prefs.markSyncFailed(reason, System.currentTimeMillis())
            return@withContext Result.Failed(reason)
        }
        if (fetched.versionName.isNullOrBlank() || fetched.versionCode.isNullOrBlank()) {
            val reason = "active.json missing version_name or version_code"
            prefs.markSyncFailed(reason, System.currentTimeMillis())
            return@withContext Result.Failed(reason)
        }
        val now = System.currentTimeMillis()
        prefs.saveSync(fetched.versionName, fetched.versionCode, now)
        // Always push on cold start, regardless of whether the fetched values
        // matched the local cache. A watch that was asleep / off-network
        // during a prior push wouldn't have received the message — Wearable
        // MessageClient is best-effort, not queued. Re-sending every cold
        // start lets offline watches self-heal as soon as they're back in
        // the Wearable network; the cost is one ~50-byte MessageClient send
        // per launch, which is negligible and idempotent on the watch
        // (PhoneAuthDispatcher.handleApiVersion just rewrites sharedPrefs
        // with the same values).
        val pushResult = pushToWatch(context, fetched.versionName, fetched.versionCode)
        when (pushResult) {
            is PushResult.Pushed -> {
                prefs.markPushed(now)
                Result.Pushed(fetched.versionName, fetched.versionCode, pushResult.nodeCount)
            }
            PushResult.NoWatch -> Result.FetchedNoWatch(fetched.versionName, fetched.versionCode)
            is PushResult.Failed -> Result.Failed("push failed: ${pushResult.message}")
        }
    }

    private fun fetchActive(httpClient: OkHttpClient): ActiveJson {
        // The repo is private — raw.githubusercontent.com returns 404 (not
        // 401) for unauthenticated reads. A fine-grained PAT with
        // Contents:Read scope on this repo only is baked into BuildConfig
        // from secrets.properties; empty string means the user opted out of
        // the auto-sync and the request is sent unauthenticated (will 404,
        // surfaced as Result.Failed by the caller — the SET_API_VERSION ADB
        // broadcast remains the manual fallback).
        val token = BuildConfig.HEVY_API_VERSION_GITHUB_TOKEN
        val builder = Request.Builder().url(ACTIVE_JSON_URL).get()
        if (token.isNotBlank()) {
            builder.addHeader("Authorization", "Bearer $token")
        }
        httpClient.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code}")
            }
            val body = response.body?.string()
                ?: throw IllegalStateException("Empty response body")
            return GsonHolder.gson.fromJson(body, ActiveJson::class.java)
                ?: throw IllegalStateException("Could not parse active.json")
        }
    }

    private sealed class PushResult {
        data class Pushed(val nodeCount: Int) : PushResult()
        object NoWatch : PushResult()
        data class Failed(val message: String) : PushResult()
    }

    private fun pushToWatch(context: Context, versionName: String, versionCode: String): PushResult {
        val nodes = try {
            Tasks.await(
                Wearable.getNodeClient(context).connectedNodes,
                SEND_TIMEOUT_S, TimeUnit.SECONDS
            )
        } catch (e: Exception) {
            Log.w(TAG, "connectedNodes lookup failed: $e")
            return PushResult.Failed(e.message ?: "connectedNodes failed")
        }
        if (nodes.isEmpty()) return PushResult.NoWatch
        val json = GsonHolder.gson.toJson(
            mapOf("version_name" to versionName, "version_code" to versionCode)
        ).toByteArray(Charsets.UTF_8)
        val msgClient = Wearable.getMessageClient(context)
        // Per-node try/catch so a single asleep/unreachable watch doesn't
        // abort the send loop and starve its siblings. Each successful send
        // increments the count; if at least one node was reached we report
        // Pushed, otherwise NoWatch so the next cold start retries.
        var reached = 0
        nodes.forEach { node ->
            try {
                Tasks.await(
                    msgClient.sendMessage(node.id, WearMessagePaths.API_VERSION, json),
                    SEND_TIMEOUT_S, TimeUnit.SECONDS
                )
                reached++
                Log.d(TAG, "pushed /api_version to node ${node.id} (${node.displayName})")
            } catch (e: Exception) {
                Log.w(TAG, "push to node ${node.id} (${node.displayName}) failed: $e")
            }
        }
        return if (reached > 0) PushResult.Pushed(reached) else PushResult.NoWatch
    }

    private fun defaultHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(FETCH_TIMEOUT_S, TimeUnit.SECONDS)
        .readTimeout(FETCH_TIMEOUT_S, TimeUnit.SECONDS)
        .build()
}
