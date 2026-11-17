package com.tapwake.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.sqrt

/**
 * Detects a deliberate shake using the accelerometer's linear acceleration
 * (gravity removed by a low-pass filter). Three distinct peaks above the
 * sensitivity threshold within a short window count as one shake.
 *
 * Uses the same partial wake lock as raise-to-wake so the sensor keeps
 * delivering events while the screen is off.
 */
class ShakeWakeEngine(
    context: Context,
    private val onShake: () -> Unit,
) : SensorEventListener {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var listening = false
    private var armed = false
    private var gravityReady = false
    private var gravity = FloatArray(3)
    private var windowStart = 0L
    private var lastPeakAt = 0L
    private var peaks = 0
    private var lastShakeAt = 0L

    /** 1 = easiest, 10 = firmest shake. */
    var sensitivity: Float = 4f
        set(value) { field = value.coerceIn(1f, 10f) }

    fun start() {
        val sensor = accelerometer ?: run {
            Log.w("TAPWAK", "No accelerometer - shake to wake unavailable")
            return
        }
        if (!listening) {
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            listening = true
            Log.d("TAPWAK", "Shake accelerometer listener registered")
        }
    }

    fun stop() {
        if (listening) {
            sensorManager?.unregisterListener(this)
            listening = false
        }
        reset()
    }

    fun arm() {
        armed = true
        reset()
        Log.d("TAPWAK", "Shake watcher armed (sensitivity=${sensitivity.toInt()})")
    }

    fun disarm() {
        armed = false
        reset()
    }

    private fun reset() {
        gravityReady = false
        windowStart = 0L
        lastPeakAt = 0L
        peaks = 0
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (!armed || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        val raw = event.values
        if (!gravityReady) {
            System.arraycopy(raw, 0, gravity, 0, 3)
            gravityReady = true
            return
        }

        gravity[0] = GRAVITY_ALPHA * gravity[0] + (1 - GRAVITY_ALPHA) * raw[0]
        gravity[1] = GRAVITY_ALPHA * gravity[1] + (1 - GRAVITY_ALPHA) * raw[1]
        gravity[2] = GRAVITY_ALPHA * gravity[2] + (1 - GRAVITY_ALPHA) * raw[2]

        val dx = raw[0] - gravity[0]
        val dy = raw[1] - gravity[1]
        val dz = raw[2] - gravity[2]
        val linear = sqrt(dx * dx + dy * dy + dz * dz)
        val threshold = MIN_THRESHOLD + (sensitivity - 1f) * THRESHOLD_STEP

        val now = SystemClock.elapsedRealtime()
        if (linear < threshold) return

        // One physical shake produces many samples; collapse them into one peak.
        if (now - lastPeakAt < PEAK_GAP_MS) return
        lastPeakAt = now

        if (windowStart == 0L || now - windowStart > WINDOW_MS) {
            windowStart = now
            peaks = 0
        }
        peaks++
        Log.d("TAPWAK", "Shake peak ${peaks} linear=${linear} threshold=${threshold}")

        if (peaks < REQUIRED_PEAKS) return
        if (now - lastShakeAt < DEBOUNCE_MS) return

        windowStart = 0L
        peaks = 0
        lastShakeAt = now
        armed = false
        Log.d("TAPWAK", "Shake detected (sensitivity=${sensitivity.toInt()})")
        onShake()
    }

    companion object {
        private const val GRAVITY_ALPHA = 0.85f
        private const val MIN_THRESHOLD = 3.0f
        private const val THRESHOLD_STEP = 1.8f
        private const val PEAK_GAP_MS = 100L
        private const val WINDOW_MS = 1200L
        private const val REQUIRED_PEAKS = 3
        private const val DEBOUNCE_MS = 2_000L
    }
}
