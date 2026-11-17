package com.tapwake.app

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * Volume-key wake that does not depend on the foreground service staying alive.
 * The user enables this once in Settings -> Accessibility; after that Android
 * keeps this component bound and delivers hardware key events to it.
 */
class VolumeKeyAccessibilityService : AccessibilityService() {

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
}
