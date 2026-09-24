package com.example.hevywatch.sensors

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import com.example.hevywatch.data.model.HeartRateSample
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Polls the watch's heart-rate sensor once per minute and emits
 * [HeartRateSample]s via [onSample]. Designed for the Scallop 2 (KSW2 / `ray`),
 * which has a Pixart PAH8011 PPG; KSW1 (`shiner`) has no sensor and
 * [start] returns early there.
 *
 * Battery strategy: keep the PPG OFF between polls. Each minute we register
 * the listener, await a reading with accuracy ≥ MEDIUM (the PPG's "locked
 * on" state, typically 3–10 s after register), capture {bpm, timestamp_ms},
 * then unregister. Worst case: an 8-second window per minute. Pinning is
 * critical on a Snapdragon Wear 2100 — keeping the sensor on continuously
 * is several percent of the daily battery budget.
 *
 * Lifecycle: [start] / [stop] from LogWorkoutViewModel. [start] is idempotent
 * and a no-op when hardware/permission are missing. The sampler does NOT
 * observe pause state itself — the VM is expected to stop() on pause and
 * start() on resume.
 *
 * Not thread-safe — start/stop must be called from the same thread (the VM's
 * main).
 */
class HeartRateSampler(
    private val context: Context,
    private val intervalMs: Long = DEFAULT_INTERVAL_MS,
    private val pollTimeoutMs: Long = DEFAULT_POLL_TIMEOUT_MS,
    private val onSample: (HeartRateSample) -> Unit
) {

    private var scope: CoroutineScope? = null
    private var loopJob: Job? = null

    /** Cached once — `getSystemService` + `getDefaultSensor` on every poll was
     *  pure overhead for a value that can't change during a workout. */
    private val sensorManager: SensorManager? by lazy {
        context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    }
    private val hrSensor: Sensor? by lazy {
        sensorManager?.getDefaultSensor(Sensor.TYPE_HEART_RATE)
    }

    /** Outcome of one poll — distinguishes "sensor refused to turn on" (a
     *  hardware fault we should give up on) from "no usable reading in time"
     *  (transient: bad contact, cold PPG, arm movement). */
    private sealed interface PollResult {
        data class Bpm(val value: Double) : PollResult
        /** `registerListener` returned false — the HAL refused to enable the PPG. */
        object RegistrationRefused : PollResult
        /** Registered fine but no reading of acceptable accuracy arrived. */
        object NoReading : PollResult
    }

    /** Begin per-minute polling. Idempotent; subsequent calls are no-ops. */
    fun start() {
        if (loopJob?.isActive == true) return
        if (!HeartRateAvailability.canSample(context)) {
            Log.d(TAG, "skip start — hardware or permission missing")
            return
        }
        val s = CoroutineScope(Dispatchers.Default + SupervisorJob())
        scope = s
        loopJob = s.launch { pollLoop() }
    }

    /** Stop polling. Safe to call multiple times. */
    fun stop() {
        loopJob?.cancel()
        loopJob = null
        scope?.cancel()
        scope = null
    }

    private suspend fun pollLoop() {
        // First sample fires almost immediately so a short workout still
        // records something. Subsequent samples are spaced [intervalMs] apart.
        var firstIteration = true
        var consecutiveRefusals = 0
        while (scope?.isActive == true) {
            if (!firstIteration) delay(intervalMs)
            firstIteration = false
            when (val result = readOneBpm()) {
                is PollResult.Bpm -> {
                    consecutiveRefusals = 0
                    onSample(
                        HeartRateSample(
                            bpm = result.value,
                            timestamp_ms = System.currentTimeMillis()
                        )
                    )
                }
                // Transient — bad contact, cold PPG, arm movement. Keep polling.
                PollResult.NoReading -> consecutiveRefusals = 0
                PollResult.RegistrationRefused -> {
                    consecutiveRefusals++
                    if (consecutiveRefusals >= MAX_CONSECUTIVE_REFUSALS) {
                        // The HAL has refused to enable the PPG this many times
                        // in a row — that's a hardware fault (seen on ray after
                        // a battery replacement left the PPG flex unseated), not
                        // something that recovers mid-session. Retrying every
                        // minute for the rest of a 90-minute workout is ~85
                        // pointless wakeups. Give up for this workout; the next
                        // start() re-arms in case the user reseated the sensor.
                        Log.w(
                            TAG,
                            "HR sensor refused $consecutiveRefusals times in a row " +
                                "— stopping sampler for this workout"
                        )
                        return
                    }
                }
            }
        }
    }

    /** Register listener, wait for the first sample with accuracy ≥ MEDIUM
     *  or accept LOW after [pollTimeoutMs]/2 to handle Fossil PPG warm-up
     *  jitter; abort on timeout. */
    private suspend fun readOneBpm(): PollResult {
        val sm = sensorManager ?: return PollResult.RegistrationRefused
        val sensor = hrSensor ?: return PollResult.RegistrationRefused

        val result = withTimeoutOrNull(pollTimeoutMs) {
            suspendCancellableCoroutine<PollResult> { cont ->
                val startMs = System.currentTimeMillis()
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        val bpm = event.values.firstOrNull()?.toDouble() ?: return
                        if (bpm <= 0.0) return // 0 = no contact / unreliable
                        val acceptable = event.accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM ||
                            (event.accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW &&
                                System.currentTimeMillis() - startMs > pollTimeoutMs / 2)
                        if (!acceptable) return
                        try { sm.unregisterListener(this) } catch (_: Exception) {}
                        if (cont.isActive) cont.resume(PollResult.Bpm(bpm))
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                // A `false` return means the HR sensor's HAL refused to enable —
                // the PPG never turns on and no reading is possible. It's the
                // exact signature of a physically disconnected/faulty HR module
                // (seen on ray after a battery replacement left the PPG flex
                // unseated — every other sensor registered fine, HR returned
                // false for every app on the device). The poll loop counts these
                // and gives up for the workout after MAX_CONSECUTIVE_REFUSALS.
                val registered = sm.registerListener(
                    listener, sensor, SensorManager.SENSOR_DELAY_NORMAL
                )
                cont.invokeOnCancellation {
                    try { sm.unregisterListener(listener) } catch (_: Exception) {}
                }
                if (!registered) {
                    Log.w(TAG, "HR sensor registration refused (registerListener=false)")
                    if (cont.isActive) cont.resume(PollResult.RegistrationRefused)
                }
            }
        }
        // withTimeoutOrNull -> null means we registered fine but no acceptable
        // reading arrived in the budget. Transient, not a hardware fault.
        return result ?: PollResult.NoReading
    }

    companion object {
        private const val TAG = "HeartRateSampler"
        const val DEFAULT_INTERVAL_MS = 60_000L
        /** Per-poll register-and-wait budget. PPG warm-up + first valid sample
         *  on Fossil Gen-4-era hardware lands well inside 8 s in normal use. */
        const val DEFAULT_POLL_TIMEOUT_MS = 8_000L
        /** Consecutive `registerListener == false` results before the sampler
         *  gives up for the rest of the workout. */
        const val MAX_CONSECUTIVE_REFUSALS = 5
    }
}
