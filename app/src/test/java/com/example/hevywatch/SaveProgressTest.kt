package com.example.hevywatch

import com.example.hevywatch.presentation.workout.isRetryableSaveError
import com.example.hevywatch.presentation.workout.saveErrorReason
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.net.SocketTimeoutException

/**
 * Pure-logic coverage for the save retry/feedback helpers behind the Finish
 * spinner (attempt x/y, fallback, and the failure reason incl. HTTP code).
 */
class SaveProgressTest {

    private fun httpError(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "".toResponseBody("text/plain".toMediaType())))

    // ── isRetryableSaveError ────────────────────────────────────────────────

    @Test fun `429 is retryable`() = assertTrue(isRetryableSaveError(httpError(429)))

    @Test fun `500 and 503 are retryable`() {
        assertTrue(isRetryableSaveError(httpError(500)))
        assertTrue(isRetryableSaveError(httpError(503)))
    }

    @Test fun `408 is retryable`() = assertTrue(isRetryableSaveError(httpError(408)))

    @Test fun `network errors are retryable`() {
        assertTrue(isRetryableSaveError(IOException("boom")))
        assertTrue(isRetryableSaveError(SocketTimeoutException()))
    }

    @Test fun `auth and client errors are not retryable`() {
        assertFalse(isRetryableSaveError(httpError(401)))
        assertFalse(isRetryableSaveError(httpError(403)))
        assertFalse(isRetryableSaveError(httpError(400)))
        assertFalse(isRetryableSaveError(httpError(422)))
    }

    @Test fun `unknown throwables are not retryable`() =
        assertFalse(isRetryableSaveError(IllegalStateException("nope")))

    // ── saveErrorReason ─────────────────────────────────────────────────────

    @Test fun `429 reason names the rate limit and the code`() =
        assertEquals("Rate limited (429)", saveErrorReason(httpError(429)))

    @Test fun `5xx reason carries the server code`() =
        assertEquals("Server error (502)", saveErrorReason(httpError(502)))

    @Test fun `auth reason carries the code`() =
        assertEquals("Auth failed (401)", saveErrorReason(httpError(401)))

    @Test fun `other http codes are surfaced with their code`() =
        assertEquals("Failed (400)", saveErrorReason(httpError(400)))

    @Test fun `network reason is human readable`() =
        assertEquals("Network error", saveErrorReason(SocketTimeoutException()))
}
