package com.example.hevywatch

import androidx.test.core.app.ApplicationProvider
import com.example.hevywatch.data.store.PendingRequestStore
import com.example.hevywatch.presentation.mode.retrySendPending
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * B6 — a corrupted pending-request body must not crash the resume prompt.
 * The retrySendPending helper catches Gson's JsonSyntaxException, clears the
 * un-deserializable entry, and throws a friendly IllegalStateException the
 * surrounding Compose handler can surface as a snackbar.
 */
@RunWith(RobolectricTestRunner::class)
class RetrySendPendingCorruptionTest {

    private lateinit var app: HevyApp
    private lateinit var store: PendingRequestStore

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        store = app.pendingRequestStore
        store.clear()
    }

    @Test
    fun `corrupted JSON body is discarded and surfaces a friendly error`() = runTest {
        // Forge a pending POST with malformed JSON. We can't go through save()
        // because the cap rejects garbage too late — write directly so the
        // store believes it's valid bytes.
        app.getSharedPreferences("pending_request", android.content.Context.MODE_PRIVATE)
            .edit()
            .putString("method", "POST")
            .putString("url", "v1/workouts")
            .putString("body", "{not valid json}")
            .putLong("saved_at_ms", System.currentTimeMillis())
            .commit()
        assertTrue("precondition: pending entry must be present", store.hasPending())

        var failed = false
        try {
            retrySendPending(app)
        } catch (e: IllegalStateException) {
            failed = true
            assertTrue(
                "error message must mention discard + re-log",
                e.message?.contains("discarded", ignoreCase = true) == true
            )
        }

        assertTrue("malformed body must trigger the friendly error path", failed)
        assertFalse("corrupted entry must be cleared from disk", store.hasPending())
    }
}
