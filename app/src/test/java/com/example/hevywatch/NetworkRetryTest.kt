package com.example.hevywatch

import com.example.hevywatch.data.withNetworkRetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import retrofit2.HttpException
import retrofit2.Response
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

/**
 * Covers the retry-with-backoff helper for read endpoints. Uses tiny delays
 * so the suite finishes in under a second; the real production defaults
 * (500 ms / 1.5 s / 4.5 s) are exercised indirectly by the integration flow.
 */
class NetworkRetryTest {

    @Test
    fun `first-try success returns immediately with no retries`() = runBlocking {
        val calls = AtomicInteger(0)
        val result = withNetworkRetry(initialDelayMs = 1L) {
            calls.incrementAndGet()
            "ok"
        }
        assertEquals("ok", result)
        assertEquals(1, calls.get())
    }

    @Test
    fun `transient failure is retried up to attempts times`() = runBlocking {
        val calls = AtomicInteger(0)
        val result = withNetworkRetry(attempts = 3, initialDelayMs = 1L) {
            val n = calls.incrementAndGet()
            if (n < 3) throw IOException("flaky")
            "recovered"
        }
        assertEquals("recovered", result)
        assertEquals(3, calls.get())
    }

    @Test
    fun `all attempts failing rethrows the last error`() = runBlocking {
        val calls = AtomicInteger(0)
        val last = IOException("final")
        try {
            withNetworkRetry(attempts = 3, initialDelayMs = 1L) {
                calls.incrementAndGet()
                throw last
            }
            fail("expected exception to propagate")
        } catch (e: IOException) {
            assertSame(last, e)
        }
        assertEquals(3, calls.get())
    }

    @Test
    fun `shouldRetry returning false skips further attempts`() = runBlocking {
        val calls = AtomicInteger(0)
        val err = IllegalStateException("auth")
        try {
            withNetworkRetry(
                attempts = 3,
                initialDelayMs = 1L,
                shouldRetry = { false }
            ) {
                calls.incrementAndGet()
                throw err
            }
            fail("expected exception to propagate")
        } catch (e: IllegalStateException) {
            assertSame(err, e)
        }
        // Only one attempt because shouldRetry returned false after the first failure.
        assertEquals(1, calls.get())
    }

    @Test
    fun `default predicate skips retry on 401`() = runBlocking {
        val calls = AtomicInteger(0)
        val err = HttpException(
            Response.error<Any>(401, "".toResponseBody("text/plain".toMediaTypeOrNull()))
        )
        try {
            withNetworkRetry(attempts = 3, initialDelayMs = 1L) {
                calls.incrementAndGet()
                throw err
            }
            fail("expected 401 to propagate after one attempt")
        } catch (e: HttpException) {
            assertEquals(401, e.code())
        }
        assertEquals("default predicate must not retry 401", 1, calls.get())
    }

    @Test
    fun `default predicate skips retry on 403`() = runBlocking {
        val calls = AtomicInteger(0)
        val err = HttpException(
            Response.error<Any>(403, "".toResponseBody("text/plain".toMediaTypeOrNull()))
        )
        try {
            withNetworkRetry(attempts = 3, initialDelayMs = 1L) {
                calls.incrementAndGet()
                throw err
            }
            fail("expected 403 to propagate after one attempt")
        } catch (e: HttpException) {
            assertEquals(403, e.code())
        }
        assertEquals("default predicate must not retry 403", 1, calls.get())
    }

    @Test
    fun `default predicate still retries 500`() = runBlocking {
        val calls = AtomicInteger(0)
        val err = HttpException(
            Response.error<Any>(500, "".toResponseBody("text/plain".toMediaTypeOrNull()))
        )
        try {
            withNetworkRetry(attempts = 3, initialDelayMs = 1L) {
                calls.incrementAndGet()
                throw err
            }
            fail("expected 500 to propagate after exhausting attempts")
        } catch (e: HttpException) {
            assertEquals(500, e.code())
        }
        assertEquals("server errors should still retry", 3, calls.get())
    }

    @Test
    fun `CancellationException is rethrown immediately without retrying`() = runBlocking {
        val calls = AtomicInteger(0)
        try {
            withNetworkRetry(attempts = 5, initialDelayMs = 1L) {
                calls.incrementAndGet()
                throw CancellationException("cancel")
            }
            fail("expected cancellation to propagate")
        } catch (e: CancellationException) {
            // expected
        }
        assertEquals(1, calls.get())
    }
}
