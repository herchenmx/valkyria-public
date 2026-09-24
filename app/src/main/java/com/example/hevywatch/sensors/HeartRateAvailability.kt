package com.example.hevywatch.sensors

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import androidx.core.content.ContextCompat

/**
 * Two layers of HR-sampling gating:
 *
 *  1. **Hardware** — does this device physically have a heart-rate sensor?
 *     KSW2 (`ray`) does (Pixart PAH8011 PPG). KSW1 (`shiner`) does not.
 *     This is a sticky, install-time check. If false, the Settings UI hides
 *     the HR section entirely and the sampler is never instantiated.
 *  2. **Permission** — has the user granted `BODY_SENSORS` at runtime?
 *     Required on API 28+ for `TYPE_HEART_RATE`. Independent of the toggle
 *     in Settings; both must be true to actually sample.
 *
 * Pure helpers — no state, no DI. Callers (Settings UI, the HR sampler,
 * the v2 POST gating) each ask the question they care about.
 */
object HeartRateAvailability {

    /** True iff the device exposes the heart-rate sensor through SensorManager. */
    fun hasHardware(context: Context): Boolean {
        // Cheap feature-flag check first (boolean, no allocation).
        val featureFlag = context.packageManager
            .hasSystemFeature(PackageManager.FEATURE_SENSOR_HEART_RATE)
        if (!featureFlag) return false
        // Belt-and-braces: a few OEMs lie about the feature flag, so confirm
        // a sensor is actually wired up.
        val sm = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return false
        return sm.getDefaultSensor(Sensor.TYPE_HEART_RATE) != null
    }

    /** True iff BODY_SENSORS is granted at runtime. */
    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.BODY_SENSORS) ==
            PackageManager.PERMISSION_GRANTED

    /** Convenience: both hardware present and permission granted. */
    fun canSample(context: Context): Boolean =
        hasHardware(context) && hasPermission(context)
}
