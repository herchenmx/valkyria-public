package com.example.hevycompanion.recents

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
 * Pure-logic coverage for the companion Resume submit retry/feedback helpers
 * (attempt x/y, private→public fallback, failure reason incl. HTTP code).
 * Mirrors the watch's SaveProgressTest.
 */
class SubmitProgressTest {

    private fun httpError(code: Int): HttpException =
        HttpException(Response.error<Any>(code, "".toResponseBody("text/plain".toMediaType())))

    @Test fun `429 and 5xx and network are retryable`() {
        assertTrue(isRetryableSubmitError(httpError(429)))
        assertTrue(isRetryableSubmitError(httpError(503)))
        assertTrue(isRetryableSubmitError(httpError(408)))
        assertTrue(isRetryableSubmitError(IOException("boom")))
        assertTrue(isRetryableSubmitError(SocketTimeoutException()))
    }

    @Test fun `auth and client errors are not retryable`() {
        assertFalse(isRetryableSubmitError(httpError(401)))
        assertFalse(isRetryableSubmitError(httpError(400)))
        assertFalse(isRetryableSubmitError(IllegalStateException("nope")))
    }

    @Test fun `reason surfaces the http code`() {
        assertEquals("Rate limited (429)", submitErrorReason(httpError(429)))
        assertEquals("Server error (500)", submitErrorReason(httpError(500)))
        assertEquals("Auth failed (401)", submitErrorReason(httpError(401)))
    }

    // 400/422 used to read "Hevy rejected the update", which framed a rejected
    // request body as a transient fault worth retrying. It is not: the same body
    // will be rejected again, and neither a retry nor the public fallback can
    // help. The wording now names the actual cause.
    @Test fun `a rejected request body says the API shape changed`() {
        assertEquals(
            "Hevy rejected the request body (400) — API shape changed",
            submitErrorReason(httpError(400)),
        )
        assertEquals(
            "Hevy rejected the request body (422) — API shape changed",
            submitErrorReason(httpError(422)),
        )
    }

    @Test fun `other 4xx codes keep the generic wording`() {
        assertEquals("Hevy rejected the update (409)", submitErrorReason(httpError(409)))
    }

    @Test fun `network reason is human readable`() {
        assertEquals("Network error — check your connection", submitErrorReason(SocketTimeoutException()))
    }
}
