package com.example.hevywatch

import androidx.test.core.app.ApplicationProvider
import com.example.hevywatch.presentation.workout.RestTimerViewModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Regression test for the "rest timer screen pops immediately on mount" bug.
 *
 * Background: the RestTimerViewModel is activity-scoped (so its tick coroutine
 * survives navigation away from RestTimerScreen and the elapse haptic still
 * fires). That means the `navigateBack` flag can be set to `true` by
 * `onComplete()` while no screen is mounted to observe it via the
 * `LaunchedEffect(viewModel.navigateBack)` block. When the user next completes
 * a set and RestTimerScreen re-mounts, the latched `true` would cause an
 * immediate popBackStack() — leaving the user on LogSetScreen with the new
 * timer running invisibly in the top bar.
 *
 * `dismiss()` is used here to set `navigateBack = true` deterministically
 * without waiting on the real tick coroutine — it exercises the same field
 * the production bug latches.
 */
@RunWith(RobolectricTestRunner::class)
class RestTimerViewModelNavigateBackTest {

    @Test
    fun `start clears a latched navigateBack from a prior unobserved completion`() {
        val app = ApplicationProvider.getApplicationContext<HevyApp>()
        val vm = RestTimerViewModel(app)

        // Simulate the production race: a prior timer signalled "go back" but
        // the screen was unmounted and never called onNavigated().
        vm.start(60)
        vm.dismiss()
        assertTrue("precondition: dismiss must latch navigateBack", vm.navigateBack)

        // New timer starts (user just completed another set, navigated to
        // RestTimerScreen, which re-mounted and called start()).
        vm.start(45)

        assertFalse(
            "start() must clear the stale navigateBack so the new mount " +
                "doesn't pop itself off the back stack",
            vm.navigateBack
        )
    }

    @Test
    fun `start without a latched navigateBack leaves it false`() {
        val app = ApplicationProvider.getApplicationContext<HevyApp>()
        val vm = RestTimerViewModel(app)

        vm.start(30)

        assertFalse(vm.navigateBack)
    }
}
