package com.example.hevywatch

import com.example.hevywatch.ui.components.shouldKeepScreenOn
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the pause-gate on FLAG_KEEP_SCREEN_ON. Screen must only stay lit
 * while the user is *actively* logging a workout; a paused workout must
 * release the flag so the OS ambient timeout can dim the display —
 * otherwise a workout left paused (answered a call, walked away) silently
 * drains the battery until the user comes back.
 */
class WorkoutDisplayControllerTest {

    @Test fun `no workout keeps screen off`() {
        assertFalse(shouldKeepScreenOn(workoutActive = false, isPaused = false))
        assertFalse(shouldKeepScreenOn(workoutActive = false, isPaused = true))
    }

    @Test fun `active workout keeps screen on`() {
        assertTrue(shouldKeepScreenOn(workoutActive = true, isPaused = false))
    }

    @Test fun `paused workout releases screen`() {
        assertFalse(shouldKeepScreenOn(workoutActive = true, isPaused = true))
    }
}
