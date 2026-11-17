package com.tapwake.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log
import android.view.KeyEvent

/** Wakes the screen from a wired or Bluetooth headset play/pause (hook) button. */
class MediaButtonWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        if (!Prefs.isEnabled(context) || !Prefs.isMediaButtonWakeEnabled(context)) return
        if (!Settings.canDrawOverlays(context)) return

        val key = intent.getParcelableExtra<KeyEvent>(Intent.EXTRA_KEY_EVENT)
        if (key != null && key.action != KeyEvent.ACTION_DOWN) return
        if (key != null && key.keyCode != KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE &&
            key.keyCode != KeyEvent.KEYCODE_HEADSETHOOK
        ) return

        Log.d("TAPWAK", "Media button via broadcast - waking")
        WakeService.wakeFromExternal(context, "media button")
    }
}
