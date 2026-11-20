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
 * "Lean back to lock": the mirror of pick-up-to-wake. While the screen is on,
 * once the phone has been held upright, leaning it back past the user's angle
 * (propping it / setting it down) locks the screen.
 *
 * tilt = acos(|gz|/|g|): 0 deg flat, 90 deg upright. The user's angle is measured
 * as degrees leaned back FROM upright, so the lock point is (90 - angle).
 *
 * Guards: the phone must first be clearly upright (so a phone already lying flat
 * with the screen on is not locked), the lean must hold briefly (a transient
 * tilt during use does not lock), and a debounce after a lock.
 */
class TiltLockEngine(
    context: Context,
    private val onLock: () -> Unit,
) : SensorEventListener {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val accelerometer: Sensor? =
        sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    private var listening = false
    private var gravity = FloatArray(3)
    private var gravityReady = false
    private var wasUpright = false
    private var belowSince = 0L
    private var lastLockAt = 0L

    /** Degrees leaned back from upright that triggers the lock. Set by the user. */
    var lockAngleDegrees: Float = DEFAULT_LOCK_ANGLE
        set(value) { field = value.coerceIn(MIN_LOCK_ANGLE, MAX_LOCK_ANGLE) }

    fun start() {
        val sensor = accelerometer ?: run {
            Log.w("TAPWAK", "No accelerometer - lean back to lock unavailable")
            return
        }
        if (!listening) {
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_GAME)
            listening = true
        }
        resetState()
    }

    fun stop() {
        if (listening) {
            sensorManager?.unregisterListener(this)
            listening = false
        }
        resetState()
    }

    private fun resetState() {
        wasUpright = false
        belowSince = 0L
        gravityReady = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
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
        val tilt = Math.toDegrees(acos(abs(tiltRatio)).toDouble()).toFloat()
        val now = SystemClock.elapsedRealtime()

        val lockTilt = 90f - lockAngleDegrees
        val uprightZone = minOf(85f, lockTilt + UPRIGHT_MARGIN)

        if (tilt >= uprightZone) {
            wasUpright = true
            belowSince = 0L
            return
        }
        if (!wasUpright) return

        if (tilt < lockTilt) {
            if (belowSince == 0L) {
                belowSince = now
            } else if (now - belowSince >= LEAN_DWELL_MS && now - lastLockAt >= DEBOUNCE_MS) {
                lastLockAt = now
                wasUpright = false
                belowSince = 0L
                Log.d("TAPWAK", "Lean-back lock: tilt $tilt deg (past $lockAngleDegrees deg from upright)")
                onLock()
            }
        } else {
            belowSince = 0L
        }
    }

    companion object {
        private const val FILTER_ALPHA = 0.8f
        private const val UPRIGHT_MARGIN = 10f
        private const val LEAN_DWELL_MS = 300L
        private const val DEBOUNCE_MS = 2_000L

        const val MIN_LOCK_ANGLE = 10f
        const val MAX_LOCK_ANGLE = 80f
        const val DEFAULT_LOCK_ANGLE = 30f
    }
}
