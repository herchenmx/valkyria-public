package com.example.hevycompanion

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.hevycompanion.wear.HevyApiVersionPrefs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The cache that backs the "API spoof" lines in MainActivity and feeds
 * HevyApiVersionSync's diff check. Two failure modes worth pinning:
 *  - fresh state must be null (so the UI shows "Unknown — waiting for first
 *    sync") rather than a default that would be mistakenly pushed to the watch
 *  - saveSync vs markPushed are independent — a sync without a push (no watch
 *    connected) still bumps lastSyncedAt but leaves lastPushedAt unchanged.
 */
@RunWith(RobolectricTestRunner::class)
// Pin the stock Application. The real HevyCompanionApp.onCreate fires
// HevyApiVersionSync.syncAndPush on a background scope, which under Robolectric
// fails offline and — since failures are now persisted — writes a live
// System.currentTimeMillis() into the very prefs file these tests assert on.
// Before failures were recorded this raced harmlessly; now it does not.
@Config(application = android.app.Application::class)
class HevyApiVersionPrefsTest {

    private lateinit var prefs: HevyApiVersionPrefs

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<android.app.Application>()
        context.getSharedPreferences("hevy_api_version", Context.MODE_PRIVATE)
            .edit().clear().commit()
        prefs = HevyApiVersionPrefs(context)
    }

    @Test
    fun `fresh state has null version and zero timestamps`() {
        assertNull(prefs.versionName)
        assertNull(prefs.versionCode)
        assertEquals(0L, prefs.lastSyncedAt)
        assertEquals(0L, prefs.lastPushedAt)
        assertEquals(0L, prefs.lastSyncAttemptAt)
        assertNull(prefs.lastSyncError)
    }

    @Test
    fun `saveSync round-trips values and stamps lastSyncedAt only`() {
        prefs.saveSync("3.0.13", "2033100", 1_700_000_000_000L)
        assertEquals("3.0.13", prefs.versionName)
        assertEquals("2033100", prefs.versionCode)
        assertEquals(1_700_000_000_000L, prefs.lastSyncedAt)
        assertEquals(0L, prefs.lastPushedAt)
    }

    @Test
    fun `markPushed stamps lastPushedAt without touching sync state`() {
        prefs.saveSync("3.0.13", "2033100", 1_700_000_000_000L)
        prefs.markPushed(1_700_000_500_000L)
        assertEquals("3.0.13", prefs.versionName)
        assertEquals("2033100", prefs.versionCode)
        assertEquals(1_700_000_000_000L, prefs.lastSyncedAt)
        assertEquals(1_700_000_500_000L, prefs.lastPushedAt)
    }

    @Test
    fun `markSyncFailed records the reason without disturbing the cached pair`() {
        prefs.saveSync("3.0.12", "2032997", 1_000L)
        prefs.markSyncFailed("HTTP 404", 5_000L)
        // The watch is still advertising the cached pair, and the last good
        // sync really did happen — only the attempt stamp and reason move.
        assertEquals("3.0.12", prefs.versionName)
        assertEquals("2032997", prefs.versionCode)
        assertEquals(1_000L, prefs.lastSyncedAt)
        assertEquals(5_000L, prefs.lastSyncAttemptAt)
        assertEquals("HTTP 404", prefs.lastSyncError)
    }

    @Test
    fun `a later success clears the stored failure`() {
        prefs.markSyncFailed("HTTP 404", 5_000L)
        assertEquals("HTTP 404", prefs.lastSyncError)
        prefs.saveSync("3.1.14", "3308731", 9_000L)
        assertNull(prefs.lastSyncError)
        assertEquals(9_000L, prefs.lastSyncedAt)
        assertEquals(9_000L, prefs.lastSyncAttemptAt)
    }

    @Test
    fun `failure before any success leaves lastSyncedAt at zero`() {
        prefs.markSyncFailed("HTTP 404", 5_000L)
        assertEquals(0L, prefs.lastSyncedAt)
        assertEquals(5_000L, prefs.lastSyncAttemptAt)
        assertNull(prefs.versionName)
    }

    @Test
    fun `blank reason is stored as something readable`() {
        prefs.markSyncFailed("", 5_000L)
        assertEquals("unknown error", prefs.lastSyncError)
    }

    @Test
    fun `saveSync overwrites prior values`() {
        prefs.saveSync("3.0.12", "2032997", 1_000L)
        prefs.saveSync("3.0.13", "2033100", 2_000L)
        assertEquals("3.0.13", prefs.versionName)
        assertEquals("2033100", prefs.versionCode)
        assertEquals(2_000L, prefs.lastSyncedAt)
    }
}
