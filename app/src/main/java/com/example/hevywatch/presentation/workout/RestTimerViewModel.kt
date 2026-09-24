package com.example.hevywatch.presentation.workout

import android.app.Application
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import com.example.hevywatch.HevyApp
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class RestTimerViewModel(app: Application) : AndroidViewModel(app) {

    private var totalSeconds: Int = 0

    /**
     * Atomic — every ± tap and the tick loop read-modify-write this field, and
     * two ± taps on the watch can interleave before the screen redraws.
     * `AtomicLong.addAndGet` / `accumulateAndGet` guarantee both writes are
     * preserved; a plain `endTimeMs += delta` lost the second of two rapid
     * taps because both reads saw the same pre-tap value before either write
     * landed.
     */
    private val endTimeMs = java.util.concurrent.atomic.AtomicLong(0L)
    private var timerJob: Job? = null
    private var lastCountdownVibratedAt: Int = Int.MAX_VALUE

    var formattedTime by mutableStateOf("00:00")
        private set
    var progress by mutableFloatStateOf(1f)
        private set
    var navigateBack by mutableStateOf(false)
        private set

    fun start(seconds: Int) {
        if (timerJob?.isActive == true) return

        // Clear any stale navigateBack from a prior timer that completed while
        // RestTimerScreen was unmounted (user navigated away to WORKOUT_CONTROL
        // or swiped back, then onComplete fired with no observer to consume the
        // flag). Without this, the next mount would observe the latched `true`
        // and popBackStack() immediately — leaving the user on LogSetScreen
        // with the new rest tick running invisibly in the top bar.
        navigateBack = false

        // If a timer is already running in the background (user swiped back and returned),
        // resume displaying it instead of starting a new one.
        val existingEnd = hevyApp().restTimerEndMs
        val nowMs = SystemClock.elapsedRealtime()
        if (existingEnd != null && existingEnd > nowMs) {
            endTimeMs.set(existingEnd)
            totalSeconds = ((existingEnd - nowMs) / 1000L).toInt().coerceAtLeast(1)
            lastCountdownVibratedAt = Int.MAX_VALUE
            tick()
            return
        }

        totalSeconds = seconds.coerceAtLeast(1)
        val newEnd = nowMs + totalSeconds * 1000L
        endTimeMs.set(newEnd)
        lastCountdownVibratedAt = Int.MAX_VALUE
        hevyApp().restTimerEndMs = newEnd
        hevyApp().requestTileUpdate()
        tick()
    }

    fun addTime(seconds: Int) {
        val updated = endTimeMs.addAndGet(seconds * 1000L)
        hevyApp().restTimerEndMs = updated
    }

    fun subtractTime(seconds: Int) {
        // Floor: end can never be earlier than (now + 1s) — otherwise the
        // visible timer hits 0 instantly. The floor is recomputed each
        // attempt because elapsedRealtime advances during retries.
        val updated = endTimeMs.accumulateAndGet(-seconds * 1000L) { current, delta ->
            val proposed = current + delta
            val floor = SystemClock.elapsedRealtime() + 1000L
            if (proposed < floor) floor else proposed
        }
        hevyApp().restTimerEndMs = updated
    }

    fun dismiss() {
        timerJob?.cancel()
        hevyApp().restTimerEndMs = null
        hevyApp().requestTileUpdate()
        navigateBack = true
    }

    private fun hevyApp() = getApplication<HevyApp>()

    fun onNavigated() { navigateBack = false }

    private fun tick() {
        timerJob = viewModelScope.launch {
            while (true) {
                // Use elapsedRealtime (monotonic since boot) so the timer is
                // immune to NTP corrections, timezone changes, and DST shifts
                // mid-rest. The watch sleeping/waking is fine either way —
                // the loop catches up on the next iteration.
                val nowMs = SystemClock.elapsedRealtime()
                val end = endTimeMs.get()
                if (nowMs >= end) {
                    formattedTime = "00:00"
                    progress = 0f
                    onComplete()
                    break
                }
                val remainingSec = restRemainingSeconds(end, nowMs)
                formattedTime = "%02d:%02d".format(remainingSec / 60, remainingSec % 60)
                progress = ((end - nowMs).toFloat() / (totalSeconds * 1000f)).coerceIn(0f, 1f)
                if (remainingSec in 1..3 && remainingSec != lastCountdownVibratedAt) {
                    lastCountdownVibratedAt = remainingSec
                    vibrateShort()
                }
                // P2 — 250 ms during the final 5-second countdown so the
                // vibration cue stays sharp; 500 ms during the rest of the
                // rest so the timer doesn't burn cycles redrawing 10× per
                // second the user can't perceive.
                delay(if (remainingSec <= 5) 250L else 500L)
            }
        }
    }

    private fun onComplete() {
        hevyApp().restTimerEndMs = null
        vibrate()
        navigateBack = true
    }

    /** True when the app is in the foreground AND a workout is being recorded.
     *  Rest-timer haptics fire from the VM tick regardless of which screen is
     *  showing, so we gate at the moment of firing instead of at subscribe-time:
     *  the user wants the buzz only when they could plausibly react to it
     *  (Hevy in foreground, workout active), not e.g. while their phone is in
     *  a pocket and Settings is foreground. */
    private fun shouldFireHaptic(): Boolean {
        val foreground = ProcessLifecycleOwner.get()
            .lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        val workoutActive = hevyApp().activeWorkout != null
        return foreground && workoutActive
    }

    private fun vibrateShort() {
        if (!shouldFireHaptic()) return
        val vibrator = getApplication<Application>()
            .getSystemService(Vibrator::class.java) ?: return
        vibrator.vibrate(VibrationEffect.createOneShot(80L, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    private fun vibrate() {
        if (!shouldFireHaptic()) return
        val vibrator = getApplication<Application>()
            .getSystemService(Vibrator::class.java) ?: return
        // Two quick pulses on completion
        vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0L, 120L, 80L, 120L), -1))
    }

    override fun onCleared() {
        super.onCleared()
        timerJob?.cancel()
        // Do NOT clear restTimerEndMs — the timer keeps running in the background.
        // It will auto-clear when it expires (onComplete) or when the user taps Skip.
    }
}
