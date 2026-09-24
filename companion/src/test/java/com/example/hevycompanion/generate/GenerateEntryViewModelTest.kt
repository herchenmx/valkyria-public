package com.example.hevycompanion.generate

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.example.hevycompanion.browse.Source
import com.example.hevycompanion.generate.mm.MmGeneratorViewModel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the unified Generate-entry coordinator: opening the feature drives the
 * correct inner VM into its first phase, switching source closes the current
 * inner VM and opens the other one, and `lastSource` round-trips through
 * SharedPreferences.
 */
@RunWith(RobolectricTestRunner::class)
class GenerateEntryViewModelTest {

    private lateinit var app: Application
    private lateinit var entry: GenerateEntryViewModel
    private lateinit var genVm: GeneratorViewModel
    private lateinit var mmGenVm: MmGeneratorViewModel

    @Before fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        // Wipe both prefs slots so tests are independent.
        app.getSharedPreferences("hevy_generate_entry", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        entry = GenerateEntryViewModel(app)
        genVm = GeneratorViewModel(app)
        mmGenVm = MmGeneratorViewModel(app)
    }

    @Test fun `default source is HEVY (saveable catalog) and feature is closed`() {
        assertEquals(Source.HEVY, entry.source)
        assertTrue(!entry.isOpen)
    }

    @Test fun `open with HEVY source drives the Hevy VM into MusclePicker`() {
        entry.open(genVm, mmGenVm)
        assertTrue(entry.isOpen)
        assertEquals(GeneratorViewModel.Screen.MusclePicker, genVm.screen)
        assertEquals(MmGeneratorViewModel.Screen.Closed, mmGenVm.screen)
    }

    @Test fun `open with MM source drives the M&M VM into Setup`() {
        entry.setSource(Source.MM, genVm, mmGenVm)
        // setSource opened the new flow; close-then-reopen models a fresh
        // user-tap sequence on the home button.
        entry.close(genVm, mmGenVm)
        assertEquals(MmGeneratorViewModel.Screen.Closed, mmGenVm.screen)
        entry.open(genVm, mmGenVm)
        assertTrue(entry.isOpen)
        assertEquals(MmGeneratorViewModel.Screen.Setup, mmGenVm.screen)
        assertEquals(GeneratorViewModel.Screen.Closed, genVm.screen)
    }

    @Test fun `setSource HEVY to MM closes the Hevy flow and opens the M&M Setup`() {
        entry.open(genVm, mmGenVm)
        assertEquals(GeneratorViewModel.Screen.MusclePicker, genVm.screen)
        entry.setSource(Source.MM, genVm, mmGenVm)
        // Hevy VM was closed before flipping — without that, backing out of
        // the M&M flow would re-surface the Hevy MusclePicker behind the
        // curtain.
        assertEquals(GeneratorViewModel.Screen.Closed, genVm.screen)
        assertEquals(MmGeneratorViewModel.Screen.Setup, mmGenVm.screen)
    }

    @Test fun `setSource is a no-op when source is unchanged`() {
        entry.open(genVm, mmGenVm)
        // genVm is in MusclePicker; calling setSource(HEVY) again should NOT
        // bounce it through Closed → MusclePicker, which would discard any
        // muscle selection the user had built up.
        entry.setSource(Source.HEVY, genVm, mmGenVm)
        assertEquals(GeneratorViewModel.Screen.MusclePicker, genVm.screen)
    }

    @Test fun `lastSource round-trips through prefs`() {
        entry.setSource(Source.MM, genVm, mmGenVm)
        // A freshly-constructed VM should read MM from prefs.
        assertEquals(Source.MM, GenerateEntryViewModel(app).source)
    }

    @Test fun `unknown lastSource enum string falls back to default`() {
        // Forward-compat: a future build with a new enum value should not
        // brick the entry coordinator on an older binary.
        app.getSharedPreferences("hevy_generate_entry", android.content.Context.MODE_PRIVATE)
            .edit().putString("last_source", "FUTURE_SOURCE").commit()
        assertEquals(Source.DEFAULT, GenerateEntryViewModel(app).source)
    }

    @Test fun `close zeroes both inner VMs defensively`() {
        // The router doesn't track which inner VM is "live" — close clears
        // both so any stuck state from a prior flow gets wiped.
        entry.open(genVm, mmGenVm)
        // Manually drive both into a non-Closed phase.
        mmGenVm.open()
        entry.close(genVm, mmGenVm)
        assertTrue(!entry.isOpen)
        assertEquals(GeneratorViewModel.Screen.Closed, genVm.screen)
        assertEquals(MmGeneratorViewModel.Screen.Closed, mmGenVm.screen)
    }
}
