package com.tapwake.app

import android.content.Context

object Prefs {
    const val NAME = "tapwake"
    const val KEY_ENABLED = "enabled"
    const val KEY_CHARGING_ONLY = "charging_only"
    const val KEY_LIFT_ENABLED = "lift_enabled"
    const val KEY_LIFT_BRIGHTNESS = "lift_brightness"
    const val KEY_LIFT_SENSITIVITY = "lift_sensitivity"
    const val KEY_VOLUME_WAKE = "volume_wake"
    const val KEY_MEDIA_BUTTON_WAKE = "media_button_wake"
    const val KEY_CHARGER_WAKE = "charger_wake"
    const val KEY_SHAKE_ENABLED = "shake_enabled"
    const val KEY_SHAKE_SENSITIVITY = "shake_sensitivity"
    const val KEY_AIR_WAVE_ENABLED = "air_wave_enabled"
    const val KEY_AIR_WAVE_SENSITIVITY = "air_wave_sensitivity"
    const val KEY_AIR_WAVE_MIGRATED = "air_wave_migrated"
    const val KEY_TILT_LOCK_ENABLED = "tilt_lock_enabled"
    const val KEY_TILT_LOCK_ANGLE = "tilt_lock_angle"

    fun isEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun isChargingOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CHARGING_ONLY, false)

    fun setChargingOnly(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHARGING_ONLY, value).apply()
    }

    fun isLiftEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_LIFT_ENABLED, false)

    fun setLiftEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_LIFT_ENABLED, value).apply()
    }

    /** Wake brightness used when the phone is raised from flat. 0.03..0.30. */
    fun getLiftBrightness(context: Context): Float =
        prefs(context).getFloat(KEY_LIFT_BRIGHTNESS, 0.10f)

    fun setLiftBrightness(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_LIFT_BRIGHTNESS, value).apply()
    }

    /**
     * Raise sensitivity in degrees: how far the phone must tilt away from flat
     * before the wake fires. Lower = wakes sooner, higher = needs a clearer lift.
     */
    fun getLiftSensitivity(context: Context): Float =
        prefs(context).getFloat(KEY_LIFT_SENSITIVITY, 10f)

    fun setLiftSensitivity(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_LIFT_SENSITIVITY, value).apply()
    }

    /** When on, a volume key press while the screen is off lights the wake screen. */
    fun isVolumeWakeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_VOLUME_WAKE, false)

    fun setVolumeWakeEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_VOLUME_WAKE, value).apply()
    }

    fun isMediaButtonWakeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_MEDIA_BUTTON_WAKE, false)

    fun setMediaButtonWakeEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_MEDIA_BUTTON_WAKE, value).apply()
    }

    fun isChargerWakeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_CHARGER_WAKE, false)

    fun setChargerWakeEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_CHARGER_WAKE, value).apply()
    }

    /** Shake to wake: off by default. */
    fun isShakeEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_SHAKE_ENABLED, false)

    fun setShakeEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_SHAKE_ENABLED, value).apply()
    }

    /** 1 = very sensitive, 10 = needs a firm shake. */
    fun getShakeSensitivity(context: Context): Float =
        prefs(context).getFloat(KEY_SHAKE_SENSITIVITY, 4f)

    fun setShakeSensitivity(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_SHAKE_SENSITIVITY, value).apply()
    }

    /** Wave over the proximity sensor to wake; off by default. */
    fun isAirWaveEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AIR_WAVE_ENABLED, false)

    fun setAirWaveEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AIR_WAVE_ENABLED, value).apply()
    }

    /** 1 = easiest, 10 = easiest one-wave detection; only 1-2 need two waves. */
    fun getAirWaveSensitivity(context: Context): Float {
        val prefs = prefs(context)
        if (!prefs.getBoolean(KEY_AIR_WAVE_MIGRATED, false)) {
            val old = prefs.getFloat(KEY_AIR_WAVE_SENSITIVITY, 5f)
            prefs.edit()
                .putFloat(KEY_AIR_WAVE_SENSITIVITY, if (old == 5f) 8f else old)
                .putBoolean(KEY_AIR_WAVE_MIGRATED, true)
                .apply()
        }
        return prefs.getFloat(KEY_AIR_WAVE_SENSITIVITY, 8f)
    }

    fun setAirWaveSensitivity(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_AIR_WAVE_SENSITIVITY, value).apply()
    }

    /** Lean back past an angle (from upright) to lock the screen; off by default. */
    fun isTiltLockEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_TILT_LOCK_ENABLED, false)

    fun setTiltLockEnabled(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_TILT_LOCK_ENABLED, value).apply()
    }

    /** Degrees leaned back from upright that triggers the lock. */
    fun getTiltLockAngle(context: Context): Float =
        prefs(context).getFloat(KEY_TILT_LOCK_ANGLE, 50f)

    fun setTiltLockAngle(context: Context, value: Float) {
        prefs(context).edit().putFloat(KEY_TILT_LOCK_ANGLE, value).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(NAME, Context.MODE_PRIVATE)
}
