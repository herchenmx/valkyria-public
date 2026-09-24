package com.example.hevywatch

import android.app.Application
import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.hevywatch.data.store.BrightnessSettingsStore
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the persistence + clamping contract for user-tunable brightness.
 * Three properties round-trip independently; setters clamp to the supported
 * range so a malformed write can't push the panel to an unreadable level.
 */
@RunWith(RobolectricTestRunner::class)
class BrightnessSettingsStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext<Application>()
        context.getSharedPreferences("brightness_prefs", Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `defaults match the spec`() {
        val store = BrightnessSettingsStore(context)
        assertEquals(BrightnessSettingsStore.DEFAULT_DEFAULT_BRIGHTNESS, store.defaultBrightness)
        assertEquals(BrightnessSettingsStore.DEFAULT_MAX_BRIGHTNESS, store.maxBrightness)
        assertEquals(BrightnessSettingsStore.DEFAULT_IDLE_SECONDS, store.idleSeconds)
    }

    @Test
    fun `setters persist across instances`() {
        BrightnessSettingsStore(context).apply {
            updateDefaultBrightness(0.15f)
            updateMaxBrightness(0.75f)
            updateIdleSeconds(7)
        }
        val reloaded = BrightnessSettingsStore(context)
        assertEquals(0.15f, reloaded.defaultBrightness)
        assertEquals(0.75f, reloaded.maxBrightness)
        assertEquals(7, reloaded.idleSeconds)
    }

    @Test
    fun `brightness setters clamp to allowed range`() {
        val store = BrightnessSettingsStore(context)
        store.updateDefaultBrightness(0.0001f)   // below MIN
        assertEquals(BrightnessSettingsStore.MIN_BRIGHTNESS, store.defaultBrightness)
        store.updateMaxBrightness(99f)            // above MAX
        assertEquals(BrightnessSettingsStore.MAX_BRIGHTNESS, store.maxBrightness)
    }

    @Test
    fun `idle setter clamps to allowed range`() {
        val store = BrightnessSettingsStore(context)
        store.updateIdleSeconds(0)                // below MIN
        assertEquals(BrightnessSettingsStore.MIN_IDLE_SECONDS, store.idleSeconds)
        store.updateIdleSeconds(999)              // above MAX
        assertEquals(BrightnessSettingsStore.MAX_IDLE_SECONDS, store.idleSeconds)
    }
}
