package com.tapwake.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * Detects a "raise from flat" gesture using only the accelerometer.
 *
 * Geometry: gravity points through the screen (z) when the phone lies flat and
 * along the long edge (y) when upright, so acos(|gz| / |g|) is the tilt away
 * from flat in degrees.
 *
 * State machine (the ordering matters):
 *  1. Phone is flat -> record flatSince; no other timestamp matters.
 *  2. Phone leaves the flat zone -> record leftFlatAt (the lift began).
 *  3. Tilt reaches the user's sensitivity -> check that it was flat long enough
 *     before the lift, and that the lift itself was quick. Fire once.
 *
 * The "quick" check is measured from when the phone *left* flat, NOT from when
 * it first became flat. A phone lying flat for ten minutes must still wake.
 */
class LiftWakeEngine(
    context: Context,
    private val onLift: () -> Unit,
) : SensorEventListener {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var listening = false
    private var armed = false
    private var gravity = FloatArray(3)
    private var gravityReady = false
    private var flatSince = 0L
    private var leftFlatAt = 0L
    private var lastLiftAt = 0L

    /** Degrees of tilt away from flat required to fire. Set by the user. */
    var sensitivityDegrees: Float = DEFAULT_SENSITIVITY_DEGREES
        set(value) { field = value.coerceIn(MIN_SENSITIVITY_DEGREES, MAX_SENSITIVITY_DEGREES) }

    fun start() {
        val sensor = accelerometer ?: run {
            Log.w("TAPWAK", "No accelerometer - raise to wake unavailable")
            return
        }
        if (!listening) {
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            listening = true
            Log.d("TAPWAK", "Accelerometer listener registered")
        }
    }

    fun stop() {
        if (listening) {
            sensorManager?.unregisterListener(this)
            listening = false
        }
        resetState()
    }

    fun arm() {
        armed = true
        resetState()
        Log.d("TAPWAK", "Raise watcher armed (sensitivity=${sensitivityDegrees} deg)")
    }

    fun disarm() {
        armed = false
        resetState()
    }

    private fun resetState() {
        flatSince = 0L
        leftFlatAt = 0L
        gravityReady = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (!armed || event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val raw = event.values
        if (!gravityReady) {
            System.arraycopy(raw, 0, gravity, 0, 3)
            gravityReady = true
        } else {
            gravity[0] = FILTER_ALPHA * gravity[0] + (1f - FILTER_ALPHA) * raw[0]
            gravity[1] = FILTER_ALPHA * gravity[1] + (1f - FILTER_ALPHA) * raw[1]
            gravity[2] = FILTER_ALPHA * gravity[2] + (1f - FILTER_ALPHA) * raw[2]
        }

        val magnitude = sqrt(gravity[0] * gravity[0] + gravity[1] * gravity[1] + gravity[2] * gravity[2])
        if (magnitude < 0.5f) return

        val tiltRatio = (gravity[2] / magnitude).coerceIn(-1f, 1f)
        val angle = Math.toDegrees(acos(abs(tiltRatio)).toDouble()).toFloat()
        val now = SystemClock.elapsedRealtime()

        if (angle < FLAT_ZONE_DEGREES) {
            // Lying (or resting) flat: only the flat timer runs here.
            if (flatSince == 0L) flatSince = now
            leftFlatAt = 0L
            return
        }

        // Left the flat zone. Without a prior flat period this is not a raise.
        if (flatSince == 0L) return
        if (leftFlatAt == 0L) leftFlatAt = now

        // Not tilted far enough yet - keep waiting, but only briefly.
        if (angle < sensitivityDegrees) {
            if (now - leftFlatAt > LIFT_WINDOW_MS) {
                flatSince = 0L
                leftFlatAt = 0L
            }
            return
        }

        val flatDwell = leftFlatAt - flatSince
        val riseDuration = now - leftFlatAt
        flatSince = 0L
        leftFlatAt = 0L

        if (flatDwell < FLAT_DWELL_MS) return          // never really rested flat
        if (riseDuration > LIFT_WINDOW_MS) return     // too slow to be a lift
        if (now - lastLiftAt < DEBOUNCE_MS) return

        lastLiftAt = now
        armed = false
        Log.d(
            "TAPWAK",
            "Raise detected: $angle deg (flat ${flatDwell}ms, rise ${riseDuration}ms)"
        )
        onLift()
    }

    companion object {
        private const val FILTER_ALPHA = 0.8f
        private const val FLAT_ZONE_DEGREES = 4f    // genuinely flat; below min sensitivity
        private const val FLAT_DWELL_MS = 200L
        private const val LIFT_WINDOW_MS = 800L
        private const val DEBOUNCE_MS = 2_000L

        const val MIN_SENSITIVITY_DEGREES = 5f
        const val MAX_SENSITIVITY_DEGREES = 45f
        const val DEFAULT_SENSITIVITY_DEGREES = 10f
    }
}
