package com.tapwake.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.util.Log

/** Wakes the screen when the charger is connected. */
class ChargerWakeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_POWER_CONNECTED) return
        if (!Prefs.isEnabled(context) || !Prefs.isChargerWakeEnabled(context)) return
        if (!Settings.canDrawOverlays(context)) return
        Log.d("TAPWAK", "Charger connected - waking")
        WakeService.wakeFromExternal(context, "charger")
    }
}
