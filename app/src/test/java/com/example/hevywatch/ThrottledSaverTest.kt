package com.example.hevywatch

import com.example.hevywatch.presentation.workout.ThrottledSaver
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Guards the throttled-save loop that backs the active-workout persistence.
 * Uses virtual time so the 2 s ticks don't slow the suite.
 *
 * Replaces IdleFlushSchedulerTest — the prior debounce scheme was rewritten
 * for power: dozens of Gson serialises per second of weight-scrolling
 * activity, plus a coroutine re-armed on every mutation, was burning watch
 * battery for no recovery benefit. The throttled saver caps saves at one per
 * [intervalMs] regardless of how fast the user is scrolling.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ThrottledSaverTest {

    private val intervalMs: Long = 2_000L

    @Test
    fun `flush fires on the tick after a touch`() = runTest {
        var flushes = 0
        val saver = ThrottledSaver(this, intervalMs) { flushes++ }

        saver.touch()
        advanceTimeBy(intervalMs - 1)
        assertEquals("must not fire before interval elapses", 0, flushes)

        advanceTimeBy(2)
        assertEquals("fires exactly once at interval boundary", 1, flushes)

        saver.cancel()
    }

    @Test
    fun `many touches inside one interval coalesce to one save`() = runTest {
        var flushes = 0
        val saver = ThrottledSaver(this, intervalMs) { flushes++ }

        // Simulate a fast weight-picker scroll — 50 mutations in 1 second.
        repeat(50) { saver.touch() }
        advanceTimeBy(intervalMs + 1)
        assertEquals("50 mutations in one interval → 1 save", 1, flushes)

        saver.cancel()
    }

    @Test
    fun `idle ticks do no work after the dirty bit clears`() = runTest {
        var flushes = 0
        val saver = ThrottledSaver(this, intervalMs) { flushes++ }

        saver.touch()
        advanceTimeBy(intervalMs + 1)
        assertEquals(1, flushes)

        // Loop keeps ticking, but no more touches → no more flushes.
        advanceTimeBy(intervalMs * 5)
        assertEquals("idle ticks must not fire flush", 1, flushes)

        saver.cancel()
    }

    @Test
    fun `interleaved bursts of mutations produce one flush per interval`() = runTest {
        var flushes = 0
        val saver = ThrottledSaver(this, intervalMs) { flushes++ }

        // Burst 1
        repeat(10) { saver.touch() }
        advanceTimeBy(intervalMs + 1)   // flush #1
        // Quiet
        advanceTimeBy(intervalMs)        // no-op
        // Burst 2
        repeat(10) { saver.touch() }
        advanceTimeBy(intervalMs + 1)   // flush #2

        assertEquals(2, flushes)
        saver.cancel()
    }

    @Test
    fun `cancel stops the loop and drops pending dirty state`() = runTest {
        var flushes = 0
        val saver = ThrottledSaver(this, intervalMs) { flushes++ }

        saver.touch()
        advanceTimeBy(intervalMs / 2)   // mid-interval, before flush
        saver.cancel()
        advanceTimeBy(intervalMs * 3)
        assertEquals("cancel must drop the pending flush", 0, flushes)
    }

    @Test
    fun `touch after cancel restarts the loop`() = runTest {
        var flushes = 0
        val saver = ThrottledSaver(this, intervalMs) { flushes++ }

        saver.touch()
        saver.cancel()
        saver.touch()
        advanceTimeBy(intervalMs + 1)
        assertEquals(1, flushes)

        saver.cancel()
    }

    @Test
    fun `loop quiesces after the idle-tick budget and restarts on touch`() = runTest {
        // The loop used to tick for the whole workout — ~2700 no-op wakeups on
        // a 90-minute session. It now stops itself after IDLE_TICKS_BEFORE_STOP
        // empty ticks; the next touch restarts it with the same latency.
        var flushes = 0
        val saver = ThrottledSaver(this, intervalMs) { flushes++ }

        saver.touch()
        advanceTimeBy(intervalMs + 1)
        assertEquals(1, flushes)

        // Sit idle well past the quiesce budget.
        advanceTimeBy(intervalMs * (ThrottledSaver.IDLE_TICKS_BEFORE_STOP + 3L))
        assertEquals("idle ticks must not fire flush", 1, flushes)

        // A touch after quiesce must still flush on the next interval.
        saver.touch()
        advanceTimeBy(intervalMs + 1)
        assertEquals("touch after quiesce must restart the loop", 2, flushes)

        saver.cancel()
    }

    @Test
    fun `sustained activity never quiesces`() = runTest {
        var flushes = 0
        val saver = ThrottledSaver(this, intervalMs) { flushes++ }

        // One touch per interval, for well past the idle budget. The
        // idle counter must reset on every flush, so every interval flushes.
        val rounds = ThrottledSaver.IDLE_TICKS_BEFORE_STOP + 5
        repeat(rounds) {
            saver.touch()
            advanceTimeBy(intervalMs + 1)
        }
        assertEquals(rounds, flushes)

        saver.cancel()
    }
}
