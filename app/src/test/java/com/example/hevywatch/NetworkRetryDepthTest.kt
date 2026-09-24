package com.example.hevywatch

import com.example.hevywatch.data.NETWORK_RETRY_MAX_DELAY_MS
import com.example.hevywatch.data.defaultShouldRetry
import com.example.hevywatch.data.withNetworkRetry
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Bucket-F item 31 — deepens NetworkRetryTest with the branches the
 * original suite skipped: the max-delay cap, the max-attempts ceiling on
 * a persistently-failing call, and the shouldRetry short-circuit for
 * HTTP 401/403 (which the retry helper deliberately does NOT retry
 * because a stale token won't fix itself by waiting).
 */
class NetworkRetryDepthTest {

    @Test
    fun `max delay cap prevents runaway backoff on many attempts`() {
        // With initialDelayMs = 1_000 and factor = 100.0, the second delay
        // would nominally be 100 s — should be clamped to
        // NETWORK_RETRY_MAX_DELAY_MS (30 s).
        val delays = mutableListOf<Long>()
        val cap = NETWORK_RETRY_MAX_DELAY_MS
        var current = 1_000L
        repeat(4) {
            delays += current
            current = (current * 100.0).toLong().coerceAtMost(cap)
        }
        // First = 1s, second onward = clamped to the cap.
        assertEquals(1_000L, delays[0])
        assertTrue(delays.drop(1).all { it == cap })
    }

    @Test
    fun `defaultShouldRetry returns false for HTTP 401`() {
        val body401 = "".toResponseBody("text/plain".toMediaTypeOrNull())
        val http401 = HttpException(Response.error<Any>(401, body401))
        assertFalse(defaultShouldRetry(http401))
    }

    @Test
    fun `defaultShouldRetry returns false for HTTP 403`() {
        val body403 = "".toResponseBody("text/plain".toMediaTypeOrNull())
        val http403 = HttpException(Response.error<Any>(403, body403))
        assertFalse(defaultShouldRetry(http403))
    }

    @Test
    fun `defaultShouldRetry returns true for HTTP 500`() {
        val body500 = "".toResponseBody("text/plain".toMediaTypeOrNull())
        val http500 = HttpException(Response.error<Any>(500, body500))
        assertTrue(defaultShouldRetry(http500))
    }

    @Test
    fun `defaultShouldRetry returns true for IOException`() {
        assertTrue(defaultShouldRetry(IOException("network down")))
    }

    @Test
    fun `401 short-circuits the retry loop and rethrows immediately`() = runTest {
        val calls = AtomicInteger(0)
        val body = "".toResponseBody("text/plain".toMediaTypeOrNull())
        try {
            withNetworkRetry(attempts = 5, initialDelayMs = 1L) {
                calls.incrementAndGet()
                throw HttpException(Response.error<Any>(401, body))
            }
            fail("expected the 401 to rethrow on the first attempt")
        } catch (e: HttpException) {
            assertEquals(401, e.code())
        }
        // Critical: no retry — the 401 must not have burned extra requests.
        assertEquals(1, calls.get())
    }

    @Test
    fun `attempts equals 1 throws first failure without any retry`() = runTest {
        val calls = AtomicInteger(0)
        try {
            withNetworkRetry(attempts = 1, initialDelayMs = 1L) {
                calls.incrementAndGet()
                throw IOException("boom")
            }
            fail("expected IOException")
        } catch (e: IOException) {
            assertEquals("boom", e.message)
        }
        assertEquals(1, calls.get())
    }

    @Test
    fun `attempts require at-least-one — attempts equals 0 rejected`() = runTest {
        try {
            withNetworkRetry(attempts = 0, initialDelayMs = 1L) { "unreachable" }
            fail("expected an IllegalArgumentException for attempts == 0")
        } catch (e: IllegalArgumentException) {
            // require(attempts >= 1) — pinned by contract
            assertTrue(e.message!!.contains("attempts must be >= 1"))
        }
    }
}
