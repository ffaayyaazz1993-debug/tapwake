package com.tapwake.app

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.SystemClock
import android.util.Log

object AirWaveDebug {
    @Volatile var eventCount = 0
    @Volatile var lastValue = -1f
    @Volatile var lastNear = false
    @Volatile var lastEventAt = 0L

    fun reset() {
        eventCount = 0
        lastValue = -1f
        lastNear = false
        lastEventAt = 0L
    }
}

data class ProximityInfo(
    val available: Boolean,
    val name: String,
    val wakeUp: Boolean,
    val maxRange: Float,
)

/**
 * Wave-to-wake using the proximity sensor.
 *
 * A pass is far -> near -> far with a plausible near duration. Sensitivity
 * controls how large the near region is and how strictly timing is judged.
 * Very low sensitivity still needs two passes; normal and high settings use
 * one pass, which matches a natural hand wave.
 */
class AirWaveEngine(
    context: Context,
    private val onWave: () -> Unit,
) : SensorEventListener {

    private val sensorManager =
        context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
    private val proximity: Sensor? = pickProximitySensor()
    private val maxRange: Float = proximity?.maximumRange?.takeIf { it > 0f } ?: 5f

    private var listening = false
    private var armed = false
    private var wasNear = false
    private var nearStart = 0L
    private var lastFarAt = 0L
    private var firstPassAt = 0L
    private var lastPassAt = 0L
    private var passes = 0
    private var lastWaveAt = 0L
    private var loggedFirstEvent = false

    var sensitivity: Float = 8f
        set(value) { field = value.coerceIn(1f, 10f) }

    private fun pickProximitySensor(): Sensor? {
        val manager = sensorManager ?: return null
        val all = manager.getSensorList(Sensor.TYPE_PROXIMITY)
        return all.firstOrNull { it.isWakeUpSensor }
            ?: all.firstOrNull()
            ?: manager.getDefaultSensor(Sensor.TYPE_PROXIMITY)
    }

    fun start() {
        val sensor = proximity ?: run {
            Log.w("TAPWAK", "No proximity sensor - air wave unavailable")
            return
        }
        if (!listening) {
            sensorManager?.registerListener(this, sensor, SensorManager.SENSOR_DELAY_NORMAL)
            listening = true
            Log.d(
                "TAPWAK",
                "Proximity listener registered: name=${sensor.name} wakeUp=${sensor.isWakeUpSensor} maxRange=$maxRange"
            )
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
        AirWaveDebug.reset()
        loggedFirstEvent = false
        Log.d(
            "TAPWAK",
            "Air wave armed: sensitivity=${sensitivity.toInt()} threshold=${nearThreshold()} passes=${requiredPasses()}"
        )
    }

    fun disarm() {
        armed = false
        reset()
    }

    private fun reset() {
        wasNear = false
        nearStart = 0L
        lastFarAt = 0L
        firstPassAt = 0L
        lastPassAt = 0L
        passes = 0
    }

    private fun nearThreshold(): Float {
        // Low sensitivity: narrow near zone. High sensitivity: wide near zone.
        val fraction = 0.20f + sensitivity * 0.075f
        return maxRange * fraction.coerceAtMost(0.95f)
    }

    private fun requiredPasses(): Int = if (sensitivity <= 2f) 2 else 1

    private fun minNearMs(): Long = (140L - sensitivity.toLong() * 11L).coerceAtLeast(20L)

    private fun maxNearMs(): Long = (350L + sensitivity.toLong() * 95L).coerceAtMost(1_300L)

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_PROXIMITY) return
        val value = event.values.firstOrNull() ?: return
        val now = SystemClock.elapsedRealtime()
        val near = value <= nearThreshold()

        AirWaveDebug.eventCount++
        AirWaveDebug.lastValue = value
        AirWaveDebug.lastNear = near
        AirWaveDebug.lastEventAt = now
        if (!loggedFirstEvent) {
            loggedFirstEvent = true
            Log.d("TAPWAK", "Proximity first event: value=$value threshold=${nearThreshold()} near=$near")
        }

        if (!armed) return

        if (near) {
            if (!wasNear) {
                if (now - lastFarAt < FAR_STABLE_MS) return
                nearStart = now
                wasNear = true
                Log.d("TAPWAK", "Proximity NEAR value=$value")
            }
            if (now - nearStart > MAX_COVER_MS) {
                // Covered continuously: pocket, face-down, or under a pillow.
                Log.d("TAPWAK", "Proximity long cover ignored")
                reset()
            }
            return
        }

        if (!wasNear) return
        val duration = now - nearStart
        wasNear = false
        lastFarAt = now
        Log.d("TAPWAK", "Proximity FAR value=$value duration=${duration}ms")
        if (duration < minNearMs() || duration > maxNearMs()) return
        if (now - lastPassAt < PASS_GAP_MS) return

        if (passes == 0 || now - firstPassAt > WAVE_WINDOW_MS) {
            passes = 0
            firstPassAt = now
        }
        passes++
        lastPassAt = now

        if (passes < requiredPasses()) return
        if (now - lastWaveAt < DEBOUNCE_MS) return

        reset()
        lastWaveAt = now
        armed = false
        Log.d("TAPWAK", "Air wave detected")
        onWave()
    }

    companion object {
        private const val FAR_STABLE_MS = 60L
        private const val MAX_COVER_MS = 1_500L
        private const val PASS_GAP_MS = 100L
        private const val WAVE_WINDOW_MS = 2_000L
        private const val DEBOUNCE_MS = 2_000L

        fun info(context: Context): ProximityInfo {
            val manager =
                context.applicationContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            val all = manager?.getSensorList(Sensor.TYPE_PROXIMITY).orEmpty()
            val sensor = all.firstOrNull { it.isWakeUpSensor } ?: all.firstOrNull()
            return if (sensor == null) {
                ProximityInfo(false, "none", false, 0f)
            } else {
                ProximityInfo(
                    available = true,
                    name = sensor.name,
                    wakeUp = sensor.isWakeUpSensor,
                    maxRange = sensor.maximumRange,
                )
            }
        }
    }
}
