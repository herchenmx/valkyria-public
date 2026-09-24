package com.example.hevycompanion

import androidx.work.ListenableWorker
import com.example.hevycompanion.data.RefreshResult
import com.example.hevycompanion.wear.WatchTokenSender
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the worker's branching contract: refresh outcome × push outcome → Result.
 *
 * The worker itself runs on WorkManager, but the decision logic lives in
 * [TokenRefreshWorker.classifyOutcome] (extracted for testability). If either
 * side broadens its result type, these tests force a deliberate mapping
 * update — silently defaulting to .retry() for an unrecognised case could
 * mask a regression where every refresh pretends to succeed.
 */
class TokenRefreshWorkerOutcomeTest {

    @Test fun `refresh success and push pushed yields Result success`() {
        val r = TokenRefreshWorker.classifyOutcome(
            RefreshResult.Success(expiresAt = "2030"),
            WatchTokenSender.Result.Pushed(nodeCount = 1)
        )
        assertEquals(ListenableWorker.Result.success(), r)
    }

    @Test fun `refresh success but no watch connected yields retry`() {
        val r = TokenRefreshWorker.classifyOutcome(
            RefreshResult.Success(expiresAt = "2030"),
            WatchTokenSender.Result.NoWatchConnected
        )
        assertEquals(ListenableWorker.Result.retry(), r)
    }

    @Test fun `refresh success but push failed yields retry`() {
        val r = TokenRefreshWorker.classifyOutcome(
            RefreshResult.Success(expiresAt = "2030"),
            WatchTokenSender.Result.Failed("timeout")
        )
        assertEquals(ListenableWorker.Result.retry(), r)
    }

    @Test fun `not logged in yields success without retry storm`() {
        val r = TokenRefreshWorker.classifyOutcome(
            RefreshResult.NotLoggedIn,
            push = null
        )
        // Critical: must NOT retry. If logged out, retrying just burns
        // battery checking nothing.
        assertEquals(ListenableWorker.Result.success(), r)
    }

    @Test fun `auth expired yields success without retry storm`() {
        val r = TokenRefreshWorker.classifyOutcome(
            RefreshResult.AuthExpired,
            push = null
        )
        // Same reasoning as NotLoggedIn: the interactor cleared credentials
        // on 401, so any retry would re-discover the empty prefs state.
        // Quietly idle until the user signs back in.
        assertEquals(ListenableWorker.Result.success(), r)
    }

    @Test fun `forbidden yields success — retrying against a blocked account is pointless`() {
        val r = TokenRefreshWorker.classifyOutcome(
            RefreshResult.Forbidden(code = 403),
            push = null
        )
        assertEquals(ListenableWorker.Result.success(), r)
    }

    @Test fun `server error retries`() {
        val r = TokenRefreshWorker.classifyOutcome(
            RefreshResult.ServerError(code = 502),
            push = null
        )
        assertEquals(ListenableWorker.Result.retry(), r)
    }

    @Test fun `rate limited with no Retry-After falls back to retry (WorkManager backoff)`() {
        val r = TokenRefreshWorker.classifyOutcome(
            RefreshResult.RateLimited(retryAfterSeconds = null),
            push = null
        )
        // classifyOutcome still maps RateLimited→retry; doWork() is the layer
        // that branches on the Retry-After value via classifyRateLimited and
        // can promote to an explicit deferred one-shot.
        assertEquals(ListenableWorker.Result.retry(), r)
    }

    // ── 429 explicit Retry-After scheduling ─────────────────────────────────

    @Test fun `classifyRateLimited honours a usable Retry-After header`() {
        val a = TokenRefreshWorker.classifyRateLimited(30L)
        assertEquals(TokenRefreshWorker.RateLimitedAction.ScheduleAfter(30L), a)
    }

    @Test fun `classifyRateLimited falls back to default backoff when header missing`() {
        val a = TokenRefreshWorker.classifyRateLimited(null)
        assertEquals(TokenRefreshWorker.RateLimitedAction.DefaultBackoff, a)
    }

    @Test fun `classifyRateLimited rejects non-positive Retry-After`() {
        // A server-sent zero / negative is nonsense — don't burn a wakeup on it.
        assertEquals(
            TokenRefreshWorker.RateLimitedAction.DefaultBackoff,
            TokenRefreshWorker.classifyRateLimited(0L)
        )
        assertEquals(
            TokenRefreshWorker.RateLimitedAction.DefaultBackoff,
            TokenRefreshWorker.classifyRateLimited(-5L)
        )
    }

    @Test fun `classifyRateLimited caps overlong Retry-After at one hour`() {
        // Without this cap a server-sent 86400 (24h) would silently disable
        // refresh for the day. Cap protects against bad/malicious server values.
        val a = TokenRefreshWorker.classifyRateLimited(86_400L)
        assertEquals(
            TokenRefreshWorker.RateLimitedAction.ScheduleAfter(
                TokenRefreshWorker.RATE_LIMIT_CAP_SECONDS
            ),
            a
        )
    }

    @Test fun `buildDelayedRequest carries the requested initial delay`() {
        // Sanity-check the OneTimeWorkRequest builder so a future refactor
        // that drops setInitialDelay (e.g. someone reuses runOnce's expedited
        // shape) is caught immediately.
        val req = TokenRefreshWorker.buildDelayedRequest(45L)
        // initialDelay is stored in milliseconds in the WorkSpec.
        assertEquals(45_000L, req.workSpec.initialDelay)
    }

    @Test fun `other HTTP error retries`() {
        val r = TokenRefreshWorker.classifyOutcome(
            RefreshResult.OtherHttpError(code = 422),
            push = null
        )
        assertEquals(ListenableWorker.Result.retry(), r)
    }

    @Test fun `contract error retries`() {
        val r = TokenRefreshWorker.classifyOutcome(
            RefreshResult.ContractError("missing access_token"),
            push = null
        )
        assertEquals(ListenableWorker.Result.retry(), r)
    }

    @Test fun `network error retries`() {
        val r = TokenRefreshWorker.classifyOutcome(
            RefreshResult.NetworkError("connect timeout"),
            push = null
        )
        assertEquals(ListenableWorker.Result.retry(), r)
    }

    @Test fun `persistence error retries`() {
        val r = TokenRefreshWorker.classifyOutcome(
            RefreshResult.PersistenceError("keystore unavailable"),
            push = null
        )
        assertEquals(ListenableWorker.Result.retry(), r)
    }
}
