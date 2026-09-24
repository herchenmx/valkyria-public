package com.example.hevywatch.util

import android.content.Context
import android.os.VibrationEffect
import android.os.Vibrator

/**
 * Thin wrapper around the Vibrator service for the workout flow. Every method
 * is a best-effort no-op when the service is unavailable (emulator, broken OEM).
 *
 * Rationale:
 * - Wear OS 2 / API 28 supports [VibrationEffect] (API 26+).
 * - Some Wear OS 2 OEMs suppress predefined effects, so we stick to one-shots
 *   and waveforms at [VibrationEffect.DEFAULT_AMPLITUDE].
 * - Patterns are short because the Scallop 2's motor is small and long
 *   patterns are perceived as low-battery warnings.
 */
object Haptics {

    /** One crisp tap — fire on a positive, discrete event (set completed, PR). */
    fun tick(context: Context) {
        vibrator(context)?.vibrate(
            VibrationEffect.createOneShot(30L, VibrationEffect.DEFAULT_AMPLITUDE)
        )
    }

    /** Double-tap — a slightly stronger "you did a thing" signal for PR detection. */
    fun celebrate(context: Context) {
        vibrator(context)?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0L, 40L, 60L, 60L), -1)
        )
    }

    /** Short error buzz — fire on save failure / network error so the user looks down. */
    fun error(context: Context) {
        vibrator(context)?.vibrate(
            VibrationEffect.createWaveform(longArrayOf(0L, 150L, 80L, 80L), -1)
        )
    }

    private fun vibrator(context: Context): Vibrator? =
        context.getSystemService(Vibrator::class.java)?.takeIf { it.hasVibrator() }
}
