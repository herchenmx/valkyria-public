package com.example.hevywatch

import com.example.hevywatch.data.model.SetType
import com.example.hevywatch.presentation.navigation.Screen
import com.example.hevywatch.ui.components.Cohort
import com.example.hevywatch.ui.components.brightnessCohort
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins the per-screen / per-set-type brightness cohort rules. Brightness now
 * applies app-wide (anywhere Hevy is foreground): the cohort only chooses
 * between `AlwaysBright` (no idle ramp) and `AllowDim` (default → max on
 * touch → default after idle).
 *
 * AlwaysBright fires only inside a workout, only when warmup choreography
 * needs the user's full attention:
 *   - LogSetScreen showing a WARMUP set
 *   - rest-timer screen when the just-completed set was a WARMUP
 *     (covers warmup → warmup rest AND warmup → first-normal rest)
 *
 * Everything else (no workout, normal sets, overview, rest-timer between
 * normal sets, mode selection, settings, …) is AllowDim.
 */
class BrightnessCohortTest {

    @Test
    fun `no active workout means AllowDim regardless of screen`() {
        listOf(Screen.LOG_SET, Screen.LOG_WORKOUT, "rest_timer/90", Screen.SETTINGS, null).forEach { route ->
            assertEquals(
                "route=$route should be AllowDim when no workout",
                Cohort.AllowDim,
                brightnessCohort(
                    hasActiveWorkout = false,
                    currentRoute = route,
                    currentSetType = SetType.WARMUP,
                    lastCompletedSetType = SetType.WARMUP,
                )
            )
        }
    }

    @Test
    fun `LogSetScreen warmup set during workout stays bright`() {
        assertEquals(
            Cohort.AlwaysBright,
            brightnessCohort(
                hasActiveWorkout = true,
                currentRoute = Screen.LOG_SET,
                currentSetType = SetType.WARMUP,
                lastCompletedSetType = null,
            )
        )
    }

    @Test
    fun `LogSetScreen normal set during workout allows dim`() {
        assertEquals(
            Cohort.AllowDim,
            brightnessCohort(
                hasActiveWorkout = true,
                currentRoute = Screen.LOG_SET,
                currentSetType = SetType.NORMAL,
                lastCompletedSetType = null,
            )
        )
    }

    @Test
    fun `RestTimer after warmup during workout stays bright`() {
        // Covers warmup → warmup rest AND warmup → first-normal rest.
        assertEquals(
            Cohort.AlwaysBright,
            brightnessCohort(
                hasActiveWorkout = true,
                currentRoute = "rest_timer/90",
                currentSetType = SetType.NORMAL,
                lastCompletedSetType = SetType.WARMUP,
            )
        )
    }

    @Test
    fun `RestTimer between two normal sets allows dim`() {
        assertEquals(
            Cohort.AllowDim,
            brightnessCohort(
                hasActiveWorkout = true,
                currentRoute = "rest_timer/90",
                currentSetType = SetType.NORMAL,
                lastCompletedSetType = SetType.NORMAL,
            )
        )
    }

    @Test
    fun `LogWorkout overview allows dim`() {
        assertEquals(
            Cohort.AllowDim,
            brightnessCohort(
                hasActiveWorkout = true,
                currentRoute = Screen.LOG_WORKOUT,
                currentSetType = SetType.WARMUP,
                lastCompletedSetType = SetType.WARMUP,
            )
        )
    }

    @Test
    fun `LogSetScreen warmup without workout still allows dim`() {
        // hasActiveWorkout=false short-circuits before the set-type check —
        // out-of-workout there's no warmup choreography to protect.
        assertEquals(
            Cohort.AllowDim,
            brightnessCohort(
                hasActiveWorkout = false,
                currentRoute = Screen.LOG_SET,
                currentSetType = SetType.WARMUP,
                lastCompletedSetType = null,
            )
        )
    }

    @Test
    fun `null route with active workout allows dim`() {
        assertEquals(
            Cohort.AllowDim,
            brightnessCohort(
                hasActiveWorkout = true,
                currentRoute = null,
                currentSetType = null,
                lastCompletedSetType = null,
            )
        )
    }
}
