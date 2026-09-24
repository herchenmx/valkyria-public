package com.example.hevycompanion

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ForegroundInfo
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.example.hevycompanion.data.AuthPrefs
import com.example.hevycompanion.data.PushErrorCategory
import com.example.hevycompanion.data.RefreshResult
import com.example.hevycompanion.data.RefreshTokenInteractor
import com.example.hevycompanion.wear.WatchTokenSender
import java.util.concurrent.TimeUnit

/**
 * Runs every hour in the background.
 * 1. Refreshes the Hevy access token using the stored refresh token.
 * 2. Saves the new tokens to SharedPreferences.
 * 3. Pushes the new tokens to the paired watch via Wearable MessageAPI.
 *
 * The worker promotes itself to a foreground service for the duration of
 * doWork() via [setForeground] / [getForegroundInfo]. Without that promotion
 * the OS Cached App Freezer (Android 12+) SIGSTOPs every thread in our
 * process while it's cached, tearing down per-uid netd resolver state — and
 * the next OkHttp call surfaces it as `UnknownHostException: No address
 * associated with hostname` even though the network itself is fine. Running
 * at PROCESS_STATE_IMPORTANT_FOREGROUND (proc-state 6) takes the worker out
 * of the freezer cgroup entirely. One-shot invocations from [runOnce] also
 * use `setExpedited` so JobScheduler boots them at the same priority class
 * the moment they're enqueued, regardless of what state the caller's process
 * was in.
 */
class TokenRefreshWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val refreshInteractor = RefreshTokenInteractor()

    override suspend fun doWork(): Result {
        // Belt-and-braces: even when the request was built with setExpedited,
        // calling setForeground explicitly here ensures the periodic worker
        // (which can't be expedited) also escapes the freezer. Wrapped in
        // try/catch because Android 14+ rejects setForeground on apps that
        // were denied POST_NOTIFICATIONS at the wrong moment; the worker
        // should still try to run rather than crash.
        try {
            setForeground(getForegroundInfo())
        } catch (e: Exception) {
            Log.w(TAG, "setForeground failed (${e.message}) — continuing without FGS promotion")
        }

        val prefs = AuthPrefs(applicationContext)
        if (!prefs.isLoggedIn) {
            Log.d(TAG, "Not logged in — skipping refresh")
            return Result.success()
        }

        Log.d(TAG, "Refreshing token…")
        val outcome = refreshInteractor.refresh(prefs)
        TokenWidgetProvider.refreshAllWidgets(applicationContext)

        return when (outcome) {
            is RefreshResult.Success -> {
                Log.d(TAG, "Token refreshed OK — pushing to watch…")
                val push = WatchTokenSender.push(applicationContext, prefs)
                // Stamp the push outcome so the widget can render it on the
                // "Pushed:" line. The sender writes lastTokenPushedAt itself
                // on success; we mirror that into markPushSuccess so the
                // error fields are cleared atomically, and stamp the error
                // path explicitly.
                val now = System.currentTimeMillis()
                when (push) {
                    is WatchTokenSender.Result.Pushed ->
                        prefs.markPushSuccess(now)
                    WatchTokenSender.Result.NoWatchConnected ->
                        prefs.markPushError(
                            PushErrorCategory.NO_WATCH,
                            "no reachable watch",
                            now
                        )
                    is WatchTokenSender.Result.Failed ->
                        prefs.markPushError(
                            PushErrorCategory.FAILED,
                            push.message,
                            now
                        )
                }
                TokenWidgetProvider.refreshAllWidgets(applicationContext)
                // Reset the periodic timer so the next fire is 1h from now,
                // not on the inherited prior cadence. Covers both periodic
                // self-fire and one-shot (widget tap / 429 deferred / etc.)
                // invocations of this worker — WorkManager handles the
                // mid-execution reschedule of the periodic WorkSpec cleanly.
                rescheduleAfterSuccess(applicationContext)
                classifyOutcome(outcome, push)
            }
            RefreshResult.NotLoggedIn -> classifyOutcome(outcome, null)
            RefreshResult.AuthExpired -> {
                Log.w(TAG, "Refresh returned 401 — credentials cleared, user must sign in again")
                classifyOutcome(outcome, null)
            }
            is RefreshResult.ServerError -> {
                Log.w(TAG, "Refresh returned HTTP ${outcome.code} (Hevy 5XX) — will retry")
                classifyOutcome(outcome, null)
            }
            is RefreshResult.RateLimited -> {
                when (val action = classifyRateLimited(outcome.retryAfterSeconds)) {
                    is RateLimitedAction.ScheduleAfter -> {
                        Log.w(
                            TAG,
                            "Refresh rate-limited (HTTP 429, Retry-After=${outcome.retryAfterSeconds}s) — " +
                                "scheduling explicit retry in ${action.delaySeconds}s"
                        )
                        runAfterDelay(applicationContext, action.delaySeconds)
                        // Success so WorkManager doesn't apply its own backoff
                        // on top of our explicit deferred schedule.
                        Result.success()
                    }
                    RateLimitedAction.DefaultBackoff -> {
                        Log.w(
                            TAG,
                            "Refresh rate-limited (HTTP 429, no usable Retry-After) — " +
                                "falling back to WorkManager exponential backoff"
                        )
                        classifyOutcome(outcome, null)
                    }
                }
            }
            is RefreshResult.Forbidden -> {
                Log.e(TAG, "Refresh returned HTTP ${outcome.code} (forbidden) — not retrying")
                classifyOutcome(outcome, null)
            }
            is RefreshResult.OtherHttpError -> {
                Log.w(TAG, "Refresh returned HTTP ${outcome.code} (other 4XX) — will retry")
                classifyOutcome(outcome, null)
            }
            is RefreshResult.ContractError -> {
                Log.e(TAG, "Refresh contract error: ${outcome.detail} — will retry")
                classifyOutcome(outcome, null)
            }
            is RefreshResult.NetworkError -> {
                Log.e(TAG, "Refresh network error: ${outcome.message} — will retry")
                classifyOutcome(outcome, null)
            }
            is RefreshResult.PersistenceError -> {
                Log.e(TAG, "Refresh persistence error: ${outcome.message} — will retry")
                classifyOutcome(outcome, null)
            }
        }
    }

    override suspend fun getForegroundInfo(): ForegroundInfo =
        buildForegroundInfo(applicationContext)

    /**
     * What to do with a [RefreshResult.RateLimited] response. Extracted as a
     * top-level nested type (not inside the companion) so tests can name
     * `TokenRefreshWorker.RateLimitedAction.*` directly. The actual side-
     * effect (enqueue / retry) lives in [doWork]; this enum is just the
     * pure mapping decision from `Retry-After` value to behaviour.
     */
    sealed class RateLimitedAction {
        /** Schedule a one-shot deferred refresh in [delaySeconds]. doWork
         *  should return Result.success() so WorkManager doesn't apply
         *  its own backoff on top of our explicit deferred schedule. */
        data class ScheduleAfter(val delaySeconds: Long) : RateLimitedAction()

        /** No usable Retry-After header — fall back to Result.retry() and
         *  let WorkManager apply its default exponential backoff. */
        object DefaultBackoff : RateLimitedAction()
    }

    companion object {
        private const val TAG = "TokenRefreshWorker"
        private const val WORK_NAME = "hevy_token_refresh"
        const val CHANNEL_ID = "hevy_token_refresh"
        const val NOTIFICATION_ID = 1001

        /**
         * Cap on how long a server-provided `Retry-After` is honoured. Beyond
         * this the worker falls back to the periodic cadence — without a cap
         * a malicious or buggy server response could disable refresh
         * indefinitely (e.g. `Retry-After: 86400`).
         */
        internal const val RATE_LIMIT_CAP_SECONDS = 3600L  // 1 hour

        fun classifyRateLimited(retryAfterSeconds: Long?): RateLimitedAction = when {
            retryAfterSeconds == null -> RateLimitedAction.DefaultBackoff
            retryAfterSeconds <= 0L -> RateLimitedAction.DefaultBackoff
            retryAfterSeconds > RATE_LIMIT_CAP_SECONDS ->
                RateLimitedAction.ScheduleAfter(RATE_LIMIT_CAP_SECONDS)
            else -> RateLimitedAction.ScheduleAfter(retryAfterSeconds)
        }

        /**
         * Pure mapping from (refresh outcome, push outcome) to Worker.Result.
         * Extracted so unit tests can pin every branch without spinning up a
         * worker, AuthPrefs, network, or Wearable client. doWork()'s logging
         * lives next to the branch in the suspend function above; this is the
         * mathematical core only.
         */
        fun classifyOutcome(
            refresh: RefreshResult,
            push: WatchTokenSender.Result?,
        ): Result = when (refresh) {
            is RefreshResult.Success ->
                if (push is WatchTokenSender.Result.Pushed) Result.success() else Result.retry()
            RefreshResult.NotLoggedIn -> Result.success()
            // Critical: 401 must NOT retry. The interactor has already cleared
            // credentials; retrying would just hammer the API with a now-empty
            // refresh token (which would early-exit as NotLoggedIn anyway, but
            // burns a wakeup every 15 min). Treat it as terminal success so the
            // worker quietly idles until the user signs back in.
            RefreshResult.AuthExpired -> Result.success()
            // 403 is similarly terminal — account is blocked / suspended;
            // retrying will keep returning 403 indefinitely.
            is RefreshResult.Forbidden -> Result.success()
            // Everything else is transient and worth retrying:
            //  - 5XX clears when Hevy comes back up
            //  - 429 falls back to WorkManager's exponential backoff when the
            //    server didn't send a usable Retry-After (the header path is
            //    branched off earlier in doWork() via classifyRateLimited)
            //  - Other 4XX may resolve after a client update
            //  - Contract / network / persistence may be transient
            is RefreshResult.ServerError -> Result.retry()
            is RefreshResult.RateLimited -> Result.retry()
            is RefreshResult.OtherHttpError -> Result.retry()
            is RefreshResult.ContractError -> Result.retry()
            is RefreshResult.NetworkError -> Result.retry()
            is RefreshResult.PersistenceError -> Result.retry()
        }

        /**
         * Idempotent — safe to call from every entry point. The notification
         * channel must exist before any worker tries to call [setForeground]
         * or Android will silently drop the FGS promotion on API 26+.
         */
        fun createNotificationChannel(context: Context) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Token refresh",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description =
                    "Briefly shown while the companion refreshes Hevy tokens. " +
                    "Required so the OS keeps the process running through the network call."
                setShowBadge(false)
            }
            context.getSystemService(NotificationManager::class.java)
                ?.createNotificationChannel(channel)
        }

        fun buildForegroundInfo(context: Context): ForegroundInfo {
            createNotificationChannel(context)
            val notification: Notification = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_refresh)
                .setContentTitle("valkyria")
                .setContentText("Refreshing tokens…")
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build()
            // FOREGROUND_SERVICE_TYPE_DATA_SYNC is the documented type for
            // "periodic data sync over the network" — exactly what this worker
            // does. Required on Android 14+ (UPSIDE_DOWN_CAKE); the two-arg
            // ForegroundInfo constructor below is fine on older OS levels.
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ForegroundInfo(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                ForegroundInfo(NOTIFICATION_ID, notification)
            }
        }

        fun schedule(context: Context) {
            createNotificationChannel(context)
            // No network constraint — the worker runs on schedule regardless of connectivity
            // and handles network failures gracefully via Result.retry().  A CONNECTED constraint
            // caused runs to be skipped entirely when the phone had only a brief signal drop at
            // the moment the job was due to fire.
            //
            // KEEP policy: prevents resetting the periodic timer when the companion app reopens.
            // Flex interval: gives WorkManager a 15-minute window to schedule each run, which
            // avoids missed runs when the exact 60-min mark falls during a Doze maintenance gap.
            //
            // For *successful refreshes from any non-periodic path* (in-app button, widget tap
            // one-shot, watch→companion push), [rescheduleAfterSuccess] uses REPLACE policy
            // to push the next periodic fire out to ~1h from now instead of letting it land
            // mid-cycle and re-do work we just did.
            //
            // Periodic work can NOT be expedited (Android API constraint); instead doWork()
            // calls setForeground() on entry to escape the freezer cgroup for the run.
            val request = buildPeriodicRequest()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
            Log.d(TAG, "Hourly token refresh scheduled (KEEP)")
        }

        /**
         * Resets the periodic timer to fire ~1h from now. Called from every
         * non-periodic success path so the worker fires 1h after the *latest*
         * refresh from any source rather than continuing to tick on its
         * original KEEP-policy clock and waking up minutes after a manual
         * refresh just finished.
         *
         * Safe to call from any thread. Uses REPLACE policy — the previous
         * periodic schedule is cancelled and a fresh one is enqueued, so
         * the first fire of the new schedule lands within the flex window
         * (45-60min from now) rather than at the inherited prior tick.
         *
         * NOT called from the periodic worker's own success branch — when
         * the periodic itself succeeds, its native cadence already schedules
         * the next fire 1h-ish from now, and replacing the WorkSpec from
         * inside one of its instances is unnecessarily fiddly.
         */
        fun rescheduleAfterSuccess(context: Context) {
            createNotificationChannel(context)
            val request = buildPeriodicRequest()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.REPLACE,
                request
            )
            Log.d(TAG, "Periodic refresh timer reset (REPLACE) after success on non-periodic path")
        }

        /** Single source of truth for the periodic-work shape — used by both
         *  the initial KEEP-policy schedule and the success-driven REPLACE
         *  reschedule. Keeping them aligned matters: a flex-interval drift
         *  here would silently shift the cadence after the first reset. */
        internal fun buildPeriodicRequest() =
            PeriodicWorkRequestBuilder<TokenRefreshWorker>(
                1, TimeUnit.HOURS,
                15, TimeUnit.MINUTES
            ).build()

        /**
         * Build a one-shot work request to run after [delaySeconds]. Used by
         * the 429 handling: when Hevy sends a `Retry-After` header we schedule
         * an explicit deferred run instead of letting WorkManager apply its
         * own exponential backoff (which doesn't know about the server's
         * preferred wait time).
         *
         * NOT expedited — the whole point of honouring Retry-After is to
         * actually wait the server-requested duration.
         */
        internal fun buildDelayedRequest(delaySeconds: Long) =
            OneTimeWorkRequestBuilder<TokenRefreshWorker>()
                .setInitialDelay(delaySeconds, TimeUnit.SECONDS)
                .build()

        /**
         * Enqueue a one-shot deferred refresh. Used by the 429 path.
         */
        fun runAfterDelay(context: Context, delaySeconds: Long) {
            createNotificationChannel(context)
            WorkManager.getInstance(context).enqueue(buildDelayedRequest(delaySeconds))
            Log.d(TAG, "One-shot deferred refresh enqueued in ${delaySeconds}s (Retry-After)")
        }

        /**
         * Fire a single immediate refresh + push, e.g. from the widget tap or
         * a watch-push retry. Expedited so JobScheduler runs the work at
         * IMPORTANT_FOREGROUND priority the moment it's enqueued — even if
         * the caller's process was cached/frozen when it requested the work.
         *
         * RUN_AS_NON_EXPEDITED_WORK_REQUEST: if the app has exhausted its
         * expedited quota for the day, fall back to a normal-priority job
         * rather than dropping the work. doWork()'s own setForeground() call
         * still tries to promote the run; either way the refresh attempt
         * happens.
         */
        fun runOnce(context: Context) {
            createNotificationChannel(context)
            WorkManager.getInstance(context).enqueue(
                OneTimeWorkRequestBuilder<TokenRefreshWorker>()
                    .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
                    .build()
            )
            Log.d(TAG, "One-shot token refresh enqueued (expedited)")
        }
    }
}
