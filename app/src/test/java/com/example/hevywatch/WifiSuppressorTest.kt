package com.example.hevywatch

import android.app.Application
import android.content.Context
import android.net.wifi.WifiManager
import androidx.test.core.app.ApplicationProvider
import com.example.hevywatch.util.WifiSuppressor
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the Phase E Wi-Fi suppression state machine. The invariants:
 *  - suppress() is idempotent (calling twice in a row is a no-op the
 *    second time)
 *  - restore() returns Wi-Fi to its **prior** state — a user who had
 *    Wi-Fi off before the workout doesn't get it turned ON for them
 *  - restoreIfStaleFromCrash() handles the case where we suppressed,
 *    crashed mid-workout, and rebooted into a clean (no active workout)
 *    state — Wi-Fi gets restored so the user isn't stuck offline
 *  - the prior-enabled bit is persisted across process death (so a crash
 *    inside the workout doesn't lose the "what was their default" bit)
 */
@RunWith(RobolectricTestRunner::class)
class WifiSuppressorTest {

    private lateinit var context: Context
    private lateinit var wifi: WifiManager

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        wifi = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
        // Start every test from a clean prefs file.
        context.getSharedPreferences("wifi_suppressor", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @After
    fun tearDown() {
        // Be a good citizen — don't bleed Wi-Fi state into the next test.
        wifi.setWifiEnabled(true)
    }

    @Test
    fun `suppress disables wifi and remembers it was on`() {
        wifi.setWifiEnabled(true)
        val changed = WifiSuppressor.suppress(context)
        assertTrue("first suppress should change state", changed)
        assertFalse(wifi.isWifiEnabled)
        assertTrue(WifiSuppressor.isSuppressed(context))
    }

    @Test
    fun `suppress is a no-op when called twice`() {
        wifi.setWifiEnabled(true)
        WifiSuppressor.suppress(context)
        // User then manually re-enabled Wi-Fi mid-workout (edge case). The
        // second suppress should still no-op — we only act once per workout
        // and trust the user's mid-workout intervention.
        wifi.setWifiEnabled(true)
        val changedAgain = WifiSuppressor.suppress(context)
        assertFalse("second suppress must no-op", changedAgain)
    }

    @Test
    fun `restore re-enables wifi when prior state was on`() {
        wifi.setWifiEnabled(true)
        WifiSuppressor.suppress(context)
        assertFalse(wifi.isWifiEnabled)

        val restored = WifiSuppressor.restore(context)
        assertTrue(restored)
        assertTrue(wifi.isWifiEnabled)
        assertFalse(WifiSuppressor.isSuppressed(context))
    }

    @Test
    fun `restore leaves wifi off when prior state was off`() {
        wifi.setWifiEnabled(false)
        WifiSuppressor.suppress(context)   // no-op flip — wifi already off
        // But the suppressed flag is still set (we recorded the attempt).
        WifiSuppressor.restore(context)
        // User had Wi-Fi off before the workout; restore must not flip it on.
        assertFalse(
            "restore must respect the user's prior Wi-Fi-off preference",
            wifi.isWifiEnabled
        )
    }

    @Test
    fun `restore without prior suppress is a no-op`() {
        wifi.setWifiEnabled(true)
        val restored = WifiSuppressor.restore(context)
        assertFalse(restored)
        assertTrue(wifi.isWifiEnabled)
    }

    @Test
    fun `restoreIfStaleFromCrash restores when no active workout`() {
        wifi.setWifiEnabled(true)
        WifiSuppressor.suppress(context)
        // App crashes; next boot has no active workout.
        val restored = WifiSuppressor.restoreIfStaleFromCrash(
            context, hasActiveWorkout = false,
        )
        assertTrue(restored)
        assertTrue(wifi.isWifiEnabled)
    }

    @Test
    fun `restoreIfStaleFromCrash leaves wifi off when workout will be resumed`() {
        wifi.setWifiEnabled(true)
        WifiSuppressor.suppress(context)
        // App crashes; next boot HAS a recoverable workout — the user might
        // resume it, so leave Wi-Fi suppressed.
        val restored = WifiSuppressor.restoreIfStaleFromCrash(
            context, hasActiveWorkout = true,
        )
        assertFalse(restored)
        assertFalse(wifi.isWifiEnabled)
        assertTrue(WifiSuppressor.isSuppressed(context))
    }

    // ── Phase G — priority steering ─────────────────────────────────────

    @Test
    fun `pickPrioritySsid returns null when priority list is empty`() {
        val result = WifiSuppressor.pickPrioritySsid(
            prioritySsids = emptyList(),
            savedSsids = listOf("\"Home\"", "\"Work\""),
        )
        org.junit.Assert.assertNull(result)
    }

    @Test
    fun `pickPrioritySsid returns null when saved list is empty`() {
        val result = WifiSuppressor.pickPrioritySsid(
            prioritySsids = listOf("Home"),
            savedSsids = emptyList(),
        )
        org.junit.Assert.assertNull(result)
    }

    @Test
    fun `pickPrioritySsid matches first priority when saved`() {
        val result = WifiSuppressor.pickPrioritySsid(
            prioritySsids = listOf("Home", "PhoneHotspot"),
            savedSsids = listOf("\"PhoneHotspot\"", "\"Home\"", "\"Gym\""),
        )
        // First priority wins even though it isn't first in the saved list.
        org.junit.Assert.assertEquals("\"Home\"", result)
    }

    @Test
    fun `pickPrioritySsid falls through to next priority when first not saved`() {
        val result = WifiSuppressor.pickPrioritySsid(
            prioritySsids = listOf("Home", "PhoneHotspot"),
            savedSsids = listOf("\"PhoneHotspot\"", "\"Gym\""),
        )
        org.junit.Assert.assertEquals("\"PhoneHotspot\"", result)
    }

    @Test
    fun `pickPrioritySsid returns null when no match in saved`() {
        val result = WifiSuppressor.pickPrioritySsid(
            prioritySsids = listOf("Home"),
            savedSsids = listOf("\"PhoneHotspot\"", "\"Gym\""),
        )
        org.junit.Assert.assertNull(result)
    }

    @Test
    fun `pickPrioritySsid handles quoted priority entries`() {
        // Users may copy SSIDs from elsewhere with surrounding quotes; the
        // matcher must normalise both sides so this still resolves.
        val result = WifiSuppressor.pickPrioritySsid(
            prioritySsids = listOf("\"Home\""),
            savedSsids = listOf("\"Home\""),
        )
        org.junit.Assert.assertEquals("\"Home\"", result)
    }

    @Test
    fun `pickPrioritySsid handles unquoted saved entries`() {
        // Defensive — some platform versions / hidden APIs may return saved
        // SSIDs without the quote wrapping. Match must still work.
        val result = WifiSuppressor.pickPrioritySsid(
            prioritySsids = listOf("Home"),
            savedSsids = listOf("Home"),
        )
        org.junit.Assert.assertEquals("Home", result)
    }

    @Test
    fun `pickPrioritySsid skips blank entries`() {
        val result = WifiSuppressor.pickPrioritySsid(
            prioritySsids = listOf("", "  ", "Home"),
            savedSsids = listOf("\"Home\""),
        )
        org.junit.Assert.assertEquals("\"Home\"", result)
    }

    @Test
    fun `restoreIfStaleFromCrash is a no-op when nothing was suppressed`() {
        wifi.setWifiEnabled(true)
        val restored = WifiSuppressor.restoreIfStaleFromCrash(
            context, hasActiveWorkout = false,
        )
        assertFalse(restored)
    }
}
