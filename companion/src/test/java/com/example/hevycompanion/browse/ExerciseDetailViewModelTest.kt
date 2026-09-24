package com.example.hevycompanion.browse

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Pins the navigation back-stack contract: `openHevy` / `openMm` push,
 * `goBack` pops one, the system-back gesture (also routed through
 * `goBack`) returns to the previous level OR to the spawning feature
 * when the stack empties. The `current` derived state always reflects
 * the top of the stack.
 *
 * We don't exercise the catalog-loading path here — it's a network call
 * driven by viewModelScope, and the unit suite has no MockWebServer
 * harness. The two `*Target` derived states are covered by their `is X`
 * branches via the openHevy / openMm calls; the catalog-resolution
 * lookup uses an empty `hevyCatalog` here, which is itself the
 * "still-loading" branch we render a placeholder for.
 */
@RunWith(RobolectricTestRunner::class)
class ExerciseDetailViewModelTest {

    private lateinit var vm: ExerciseDetailViewModel

    @Before
    fun setUp() {
        vm = ExerciseDetailViewModel(ApplicationProvider.getApplicationContext<Application>())
    }

    @Test fun `initial stack is empty and isOpen is false`() {
        assertTrue(vm.stack.isEmpty())
        assertNull(vm.current)
        assertEquals(false, vm.isOpen)
    }

    @Test fun `openHevy pushes a Hevy selection onto the stack`() {
        vm.openHevy("BENCH_BB")
        assertEquals(1, vm.stack.size)
        val sel = vm.current
        assertNotNull(sel)
        assertTrue(sel is ExerciseDetailViewModel.Selection.Hevy)
        assertEquals("BENCH_BB", (sel as ExerciseDetailViewModel.Selection.Hevy).templateId)
        assertEquals(true, vm.isOpen)
    }

    @Test fun `openMm pushes an M&M selection onto the stack`() {
        vm.openMm("MM_SQUAT")
        val sel = vm.current
        assertTrue(sel is ExerciseDetailViewModel.Selection.Mm)
        assertEquals("MM_SQUAT", (sel as ExerciseDetailViewModel.Selection.Mm).exerciseId)
    }

    @Test fun `goBack pops one level at a time`() {
        vm.openHevy("A")
        vm.openHevy("B")
        vm.openHevy("C")
        assertEquals(3, vm.stack.size)

        vm.goBack()
        assertEquals(2, vm.stack.size)
        assertEquals("B", (vm.current as ExerciseDetailViewModel.Selection.Hevy).templateId)

        vm.goBack()
        assertEquals(1, vm.stack.size)
        assertEquals("A", (vm.current as ExerciseDetailViewModel.Selection.Hevy).templateId)

        vm.goBack()
        assertTrue(vm.stack.isEmpty())
        assertNull(vm.current)
    }

    @Test fun `goBack on empty stack is a no-op`() {
        // Predictive-back fires before the BackHandler is unregistered, so
        // an extra pop after the stack empties must not throw.
        vm.goBack()
        vm.goBack()
        assertTrue(vm.stack.isEmpty())
    }

    @Test fun `closeAll clears the entire stack`() {
        vm.openHevy("A")
        vm.openMm("M1")
        vm.openHevy("B")
        vm.closeAll()
        assertTrue(vm.stack.isEmpty())
        assertNull(vm.current)
    }

    @Test fun `Hevy and M&M selections can interleave on the same stack`() {
        // Tap-similar from a Hevy detail can only push another Hevy, but a
        // user could plausibly have both catalogs pushed across separate
        // open sessions. The VM doesn't enforce same-catalog stacks; that's
        // the screen's job.
        vm.openHevy("A")
        vm.openMm("M1")
        vm.openHevy("B")

        assertEquals(3, vm.stack.size)
        assertTrue(vm.current is ExerciseDetailViewModel.Selection.Hevy)
        vm.goBack()
        assertTrue(vm.current is ExerciseDetailViewModel.Selection.Mm)
        vm.goBack()
        assertTrue(vm.current is ExerciseDetailViewModel.Selection.Hevy)
    }
}
