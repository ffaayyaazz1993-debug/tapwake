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
 * Detects a "pick up to wake" gesture using only the accelerometer.
 *
 * Geometry: gravity points through the screen (z) when the phone lies flat and
 * along the long edge (y) when upright, so acos(|gz| / |g|) is the tilt away
 * from flat in degrees (0 flat, 90 upright).
 *
 * The rest does NOT have to be flat. A phone propped on a surface at a negative
 * or inclined angle must still wake when picked up, so "rest" is detected as
 * stillness (low linear acceleration) at ANY orientation, not a flat-only zone.
 *
 * State machine:
 *  1. Phone is still (any angle) -> record restSince + restAngle.
 *  2. Phone starts moving -> record leftRestAt (the pickup began).
 *  3. It tilts further upright than the rest angle AND reaches the user's
 *     sensitivity, quickly and with a fast enough angular rate -> fire once.
 *
 * Guards: a minimum rest dwell (was actually set down), a short rise window and
 * a minimum angular rate (a fast pickup, not a slow lean), and a debounce.
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
    private var restSince = 0L
    private var restAngle = 0f
    private var leftRestAt = 0L
    private var lastLiftAt = 0L

    /** Degrees of tilt away from flat required to fire. Set by the user. */
    var sensitivityDegrees: Float = DEFAULT_SENSITIVITY_DEGREES
        set(value) { field = value.coerceIn(MIN_SENSITIVITY_DEGREES, MAX_SENSITIVITY_DEGREES) }

    fun start() {
        val sensor = accelerometer ?: run {
            Log.w("TAPWAK", "No accelerometer - pick up to wake unavailable")
            return
        }
        if (!listening) {
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
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
        Log.d("TAPWAK", "Pickup watcher armed (sensitivity=${sensitivityDegrees} deg)")
    }

    fun disarm() {
        armed = false
        resetState()
    }

    private fun resetState() {
        restSince = 0L
        leftRestAt = 0L
        restAngle = 0f
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

        // Linear acceleration (gravity removed) tells us still vs moving.
        val dx = raw[0] - gravity[0]
        val dy = raw[1] - gravity[1]
        val dz = raw[2] - gravity[2]
        val linear = sqrt(dx * dx + dy * dy + dz * dz)
        val now = SystemClock.elapsedRealtime()

        if (linear < STILL_THRESHOLD) {
            // Stationary at ANY angle: a rest, not necessarily flat.
            if (restSince == 0L) restSince = now
            restAngle = angle
            leftRestAt = 0L
            return
        }

        // Moving. Without a prior rest this is not a pickup.
        if (restSince == 0L) return
        if (leftRestAt == 0L) leftRestAt = now

        val movedUpright = angle > restAngle + UPRIGHT_EPSILON
        val reachedThreshold = angle >= sensitivityDegrees
        if (!(movedUpright && reachedThreshold)) {
            if (now - leftRestAt > LIFT_WINDOW_MS) {
                restSince = 0L
                leftRestAt = 0L
            }
            return
        }

        val restDwell = leftRestAt - restSince
        val riseDuration = now - leftRestAt
        val rate = if (riseDuration > 0) (angle - restAngle) / (riseDuration / 1000f) else Float.MAX_VALUE
        val fromAngle = restAngle
        restSince = 0L
        leftRestAt = 0L

        if (restDwell < REST_DWELL_MS) return          // never really set down
        if (riseDuration > LIFT_WINDOW_MS) return      // too slow to be a pickup
        if (rate < MIN_LIFT_RATE) return               // a slow lean, not a lift
        if (now - lastLiftAt < DEBOUNCE_MS) return

        lastLiftAt = now
        armed = false
        Log.d(
            "TAPWAK",
            "Pickup detected: $angle deg from rest $fromAngle deg (rest ${restDwell}ms, rise ${riseDuration}ms, rate ${rate.toInt()} deg/s)"
        )
        onLift()
    }

    companion object {
        private const val FILTER_ALPHA = 0.8f
        private const val STILL_THRESHOLD = 0.5f      // m/s^2 linear below this = at rest
        private const val UPRIGHT_EPSILON = 2f        // must tilt at least this much more upright
        private const val REST_DWELL_MS = 300L
        private const val LIFT_WINDOW_MS = 800L
        private const val MIN_LIFT_RATE = 25f         // deg/s - fast pickup, not a slow lean
        private const val DEBOUNCE_MS = 2_000L

        const val MIN_SENSITIVITY_DEGREES = 5f
        const val MAX_SENSITIVITY_DEGREES = 45f
        const val DEFAULT_SENSITIVITY_DEGREES = 10f
    }
}
