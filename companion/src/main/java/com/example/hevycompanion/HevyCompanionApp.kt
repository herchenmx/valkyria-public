package com.example.hevycompanion

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.os.Build
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.example.hevycompanion.data.AuthPrefs
import com.example.hevycompanion.wear.HevyApiVersionSync
import com.example.hevycompanion.wear.TrustWatchReceiver
import com.google.android.gms.tasks.Tasks
import com.google.android.gms.wearable.Wearable
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/**
 * P6 — explicit Coil [ImageLoaderFactory] so the browser's ~600 exercise
 * thumbnails don't push Coil's defaults out of bounds on a phone shared with
 * other apps. Default behavior gives Coil ~25 % of the process heap as a
 * memory cache and 256 MB of disk; both are oversized for a fitness-companion
 * app that only renders one screen of thumbnails at a time.
 *
 * 32 MB memory + 64 MB disk holds the working set for the entire Hevy + MM
 * catalog with room to spare, and frees the headroom for other apps. The OS
 * trims the memory cache under pressure either way; the disk cap just bounds
 * worst-case storage.
 */
class HevyCompanionApp : Application(), ImageLoaderFactory {

    /** Process-lifetime IO scope. Anything that must outlive an Activity
     *  (catalog preloads, version sync) belongs here rather than in an
     *  ad-hoc `CoroutineScope(...)` that leaks past onDestroy. */
    val applicationScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        // Inert unless a webhook URL was built in. Tagged "phone" so one
        // webhook feed shows both devices' attempts at the same resume. The
        // companion has no CommitInfoReceiver, hence no commit to report —
        // pair it with the watch's tag or the release it was installed from.
        val installedAt = runCatching {
            packageManager.getPackageInfo(packageName, 0).lastUpdateTime
        }.getOrDefault(0L)
        com.example.hevycore.debug.DebugWebhook.configure(
            webhookUrl = BuildConfig.HEVY_DEBUG_WEBHOOK_URL,
            deviceTag = "phone",
            commitHash = "unstamped",
            expiresAtMillis = if (installedAt > 0L)
                installedAt + com.example.hevycore.debug.DebugWebhook.TTL_MILLIS else 0L,
        )
        // Create the token-refresh notification channel up-front so it exists
        // before any WorkManager job tries to call setForeground() — without
        // the channel, the OS silently drops the FGS promotion on API 26+
        // and the worker stays in cached priority (the exact failure mode the
        // freezer fix exists to avoid).
        TokenRefreshWorker.createNotificationChannel(this)

        // TOFU-approval channel — high importance because the notification
        // must be seen quickly (user just installed a new watch and expects
        // the login to happen), and it must survive Do-Not-Disturb since the
        // watch is otherwise dead-in-the-water until they respond.
        createTrustWatchChannel()

        // Best-effort: pull the latest promoted Hevy app version/build from
        // the repo's api-versions/active.json and push to the watch if the
        // values changed. Failures here are silent — the watch already has a
        // working baked-in default and the SET_API_VERSION ADB broadcast is
        // the manual escape hatch.
        applicationScope.launch { seedTrustedWatchesOnce() }

        applicationScope.launch {
            when (val result = HevyApiVersionSync.syncAndPush(this@HevyCompanionApp)) {
                is HevyApiVersionSync.Result.Pushed ->
                    Log.d(TAG, "API version pushed to ${result.nodeCount} node(s): ${result.versionName} (${result.versionCode})")
                is HevyApiVersionSync.Result.FetchedNoWatch ->
                    Log.d(TAG, "API version fetched but no watch reached (offline?): ${result.versionName}")
                is HevyApiVersionSync.Result.Failed ->
                    Log.w(TAG, "API version sync failed: ${result.message}")
            }
        }
    }

    /**
     * One-time upgrade migration: adopt every watch that is currently paired
     * into [AuthPrefs.trustedWatchNodeIds].
     *
     * The trust model used to be a single pin, so exactly one of the user's
     * two watches (ray / shiner) was ever trusted — the other silently fell
     * back to whatever the phone happened to push, and swapping would have
     * needed a logout. Widening to a set fixes that going forward, but the
     * already-paired watches would still have had to be approved one at a
     * time. Seeding from `connectedNodes` on the first launch after the
     * upgrade means the user approves nothing: the watches sitting on their
     * wrist right now are, by definition, theirs.
     *
     * Runs once per install (guarded by [AuthPrefs.trustedWatchesSeeded]).
     * After that, a genuinely new watch still goes through explicit approval.
     */
    private suspend fun seedTrustedWatchesOnce() {
        val prefs = AuthPrefs(this)
        if (prefs.trustedWatchesSeeded) return
        try {
            val nodes = Tasks.await(
                Wearable.getNodeClient(this).connectedNodes,
                SEED_NODE_TIMEOUT_S, TimeUnit.SECONDS,
            )
            val ids = nodes.map { it.id }
            if (ids.isNotEmpty()) {
                prefs.addTrustedWatches(ids)
                Log.i(TAG, "Seeded trusted watches from connected nodes: $ids")
            } else {
                // No watch reachable right now — leave the flag unset so the
                // migration retries on a later launch rather than locking in
                // an empty allowlist and forcing a manual approval.
                Log.d(TAG, "No connected nodes to seed; will retry next launch")
                return
            }
            prefs.trustedWatchesSeeded = true
        } catch (e: Exception) {
            Log.w(TAG, "Trusted-watch seeding failed (will retry next launch): $e")
        }
    }

    private fun createTrustWatchChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val nm = getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            TrustWatchReceiver.CHANNEL_ID,
            "New watch approval",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Prompts you to trust a Wear OS device that just asked to sign in."
        }
        nm.createNotificationChannel(channel)
    }

    override fun onTerminate() {
        // onTerminate fires only on the emulator / tests; the production OS
        // just kills the process. Cancelling the scope here is defensive and
        // keeps Robolectric clean.
        applicationScope.cancel()
        super.onTerminate()
    }

    companion object {
        private const val TAG = "HevyCompanionApp"
        private const val SEED_NODE_TIMEOUT_S = 10L
    }

    override fun newImageLoader(): ImageLoader = ImageLoader.Builder(this)
        .memoryCache {
            MemoryCache.Builder(this)
                .maxSizeBytes(32 * 1024 * 1024)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(cacheDir.resolve("coil_image_cache"))
                .maxSizeBytes(64L * 1024 * 1024)
                .build()
        }
        // OkHttp client with tight timeouts — Hevy CDN is fast on healthy
        // networks; we'd rather fall back to a placeholder than spin for 30 s
        // on a stalled handover.
        .okHttpClient {
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(15, TimeUnit.SECONDS)
                .build()
        }
        .build()
}
