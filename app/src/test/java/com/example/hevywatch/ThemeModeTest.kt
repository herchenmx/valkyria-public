package com.example.hevywatch

import com.example.hevywatch.data.store.ThemeMode
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pure parsing logic for [ThemeMode]. The persisted preference is a string;
 * [ThemeMode.fromName] must round-trip the enum names and fall back to DARK
 * (the default scheme) for anything unrecognised — including a fresh install
 * where the key is absent (null).
 */
class ThemeModeTest {

    @Test
    fun `fromName round-trips each enum name`() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromName("DARK"))
        assertEquals(ThemeMode.LIGHT, ThemeMode.fromName("LIGHT"))
    }

    @Test
    fun `fromName defaults to DARK for null`() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromName(null))
    }

    @Test
    fun `fromName defaults to DARK for an unknown value`() {
        assertEquals(ThemeMode.DARK, ThemeMode.fromName("sepia"))
        assertEquals(ThemeMode.DARK, ThemeMode.fromName(""))
    }
}
