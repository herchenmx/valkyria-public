package com.example.hevywatch.presentation.workout

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Coalesces UI mutations into a single [flush] call at most once every
 * [intervalMs]. Caller invokes [touch] on every mutation. A single coroutine
 * runs in [scope] ticking every [intervalMs] and only fires [flush] when the
 * dirty bit is set since the last tick.
 *
 * Replaces the per-mutation + 10 s idle-flush combo: that one issued one Gson
 * serialise per UI mutation (dozens per second during a weight scroll) plus an
 * idle-flush coroutine that re-armed every tick. The throttled saver caps the
 * worst-case serialise rate at ~30 / min — matching the recovery durability of
 * the old scheme (~2 s of scrolling lost worst case after a process kill, vs
 * ~10 s under the prior scheme).
 *
 * The loop **quiesces** after [IDLE_TICKS_BEFORE_STOP] consecutive no-op
 * ticks and the next [touch] restarts it. Without that, a 90-minute workout
 * with long rest gaps woke the CPU ~2700 times to do nothing — real battery
 * cost on a Snapdragon Wear 2100. The trade-off is that the first mutation
 * after a quiet stretch schedules a fresh [intervalMs] delay, which is the
 * same latency an already-running loop would have given it.
 *
 * The two explicit `saveBlocking()` checkpoints in LogWorkoutViewModel (set
 * completion + pause) still fire synchronously and stand outside the throttle.
 */
class ThrottledSaver(
    private val scope: CoroutineScope,
    private val intervalMs: Long,
    private val flush: () -> Unit,
) {
    private val dirty = AtomicBoolean(false)
    private var job: Job? = null

    /** Mark dirty and ensure the throttle loop is running. */
    fun touch() {
        dirty.set(true)
        if (job?.isActive == true) return
        job = scope.launch {
            var idleTicks = 0
            while (isActive && idleTicks < IDLE_TICKS_BEFORE_STOP) {
                delay(intervalMs)
                if (dirty.compareAndSet(true, false)) {
                    flush()
                    idleTicks = 0
                } else {
                    idleTicks++
                }
            }
        }
    }

    /** Stop the loop. Any pending dirty bit is dropped — callers using this at
     *  an explicit `saveBlocking()` checkpoint have already flushed the state. */
    fun cancel() {
        job?.cancel()
        job = null
        dirty.set(false)
    }

    companion object {
        /** Consecutive no-op ticks before the loop stops itself. 5 × the
         *  interval is long enough that a normal set-logging cadence never
         *  pays the restart, short enough that a rest period doesn't. */
        const val IDLE_TICKS_BEFORE_STOP = 5
    }
}
