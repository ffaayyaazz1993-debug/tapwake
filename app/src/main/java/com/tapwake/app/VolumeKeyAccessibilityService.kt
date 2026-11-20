package com.tapwake.app

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Volume-key wake that does not depend on the foreground service staying alive,
 * plus "lean back to lock": while the screen is on, leaning the phone back past
 * the set angle locks it via GLOBAL_ACTION_LOCK_SCREEN. The user enables this
 * once in Settings -> Accessibility; Android then keeps the component bound.
 */
class VolumeKeyAccessibilityService : AccessibilityService() {

    private var tiltEngine: TiltLockEngine? = null
    private var screenReceiver: BroadcastReceiver? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.d("TAPWAK", "Accessibility service connected; tiltLockPref=${Prefs.isTiltLockEnabled(this)}")
        tiltEngine = TiltLockEngine(this) { lockFromTilt() }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_ON -> syncTiltLock()
                    Intent.ACTION_SCREEN_OFF -> tiltEngine?.stop()
                }
            }
        }
        screenReceiver = receiver
        registerReceiver(receiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        })
        syncTiltLock()
    }

    /** Start/stop the lean-back watcher to match the pref and the screen state. */
    private fun syncTiltLock() {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        val screenOn = power?.isInteractive == true
        if (screenOn && Prefs.isTiltLockEnabled(this)) {
            tiltEngine?.lockAngleDegrees = Prefs.getTiltLockAngle(this)
            tiltEngine?.start()
            Log.d("TAPWAK", "Lean-back-to-lock watching (angle=${Prefs.getTiltLockAngle(this)} deg)")
        } else {
            tiltEngine?.stop()
        }
    }

    private fun lockFromTilt() {
        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isInteractive != true) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            Log.w("TAPWAK", "Lean-back lock needs Android 9+")
            return
        }
        tiltEngine?.stop()
        val ok = performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN)
        Log.d("TAPWAK", "Lean-back lock: performGlobalAction(LOCK_SCREEN)=$ok")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit
    override fun onInterrupt() = Unit

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (event.keyCode != KeyEvent.KEYCODE_VOLUME_UP &&
            event.keyCode != KeyEvent.KEYCODE_VOLUME_DOWN
        ) return false
        if (!Prefs.isVolumeWakeEnabled(this)) return false

        val power = getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (power?.isInteractive == true) return false // screen on - behave normally

        Log.d("TAPWAK", "Volume key ${event.keyCode} via accessibility - waking")
        WakeService.wakeFromExternal(this, "volume")
        return true // consume it: the key works as a power button, not a volume step
    }

    override fun onDestroy() {
        instance = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        tiltEngine?.stop()
        tiltEngine = null
        super.onDestroy()
    }

    companion object {
        @Volatile
        private var instance: VolumeKeyAccessibilityService? = null

        /** Called by the UI after the tilt-lock pref changes so it applies at once. */
        fun refreshTiltLock() {
            instance?.syncTiltLock()
        }
    }
}
