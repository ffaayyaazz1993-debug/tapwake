package com.tapwake.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.util.Log
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationCompat

/**
 * Watches for the screen switching off and raises the wake overlay. It is a
 * foreground service so Android does not kill it mid-session; the overlay itself
 * is what allows a background activity start, which needs the SYSTEM_ALERT_WINDOW
 * grant from the user.
 */
class WakeService : Service() {

    private var screenReceiver: BroadcastReceiver? = null

    /** Accelerometer-based raise-to-wake detector, used when that mode is enabled. */
    private var liftEngine: LiftWakeEngine? = null

    /** Shake-to-wake detector, can run alongside raise/volume watching. */
    private var shakeEngine: ShakeWakeEngine? = null

    /** Proximity wave detector, can run alongside the other screen-off watchers. */
    private var airWaveEngine: AirWaveEngine? = null

    /** Screen-off volume-key wake via a MediaSession remote volume provider. */
    private var volumeWakeSession: VolumeWakeSession? = null

    /**
     * While the screen is off the CPU suspends unless something holds a partial
     * wake lock - and a suspended CPU delivers no accelerometer events, so the
     * raise would never be seen. This lock exists only for that window; it is
     * bounded and refreshed so it cannot silently drain the battery.
     */
    private var keepAwakeLock: PowerManager.WakeLock? = null
    private val keepAliveHandler = Handler(Looper.getMainLooper())
    private var keepAliveRunnable: Runnable? = null
    private var liftArmed = false
    private var simulateRegistered = false
    private var volumeRegistered = false
    private val watchdogHandler = Handler(Looper.getMainLooper())
    private var watchdogRunnable: Runnable? = null

    /**
     * The overlay turns the screen back on itself, which fires ACTION_SCREEN_ON.
     * That first self-inflicted event must be ignored or the overlay would close
     * immediately after opening.
     */
    private var ignoreNextScreenOn = false

    override fun onCreate() {
        super.onCreate()
        registerScreenReceiver()
        liftEngine = LiftWakeEngine(this) { onLiftDetected() }
        shakeEngine = ShakeWakeEngine(this) { onShakeDetected() }
        airWaveEngine = AirWaveEngine(this) { onAirWaveDetected() }
        volumeWakeSession = VolumeWakeSession(this) { source ->
            if (source == "media button") onExternalWake(source) else onVolumeWake()
        }
    }

    /** Debug-only trigger: delivers to the dynamic receiver while the service is running. */
    private val simulateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_SIMULATE_LIFT) {
                Log.d("TAPWAK", "Simulated lift received (dynamic receiver)")
                onLiftDetected()
            } else if (intent.action == ACTION_SIMULATE_SHAKE) {
                Log.d("TAPWAK", "Simulated shake received (dynamic receiver)")
                onShakeDetected()
            } else if (intent.action == ACTION_SIMULATE_AIR_WAVE) {
                Log.d("TAPWAK", "Simulated air wave received (dynamic receiver)")
                onAirWaveDetected()
            }
        }
    }

    /**
     * Volume rocker as a wake key. The system broadcasts VOLUME_CHANGED_ACTION on
     * every volume step; we only act when the screen is off so the keys keep
     * their normal behaviour the rest of the time.
     */
    private val volumeReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action != VOLUME_CHANGED_ACTION) return
            if (!Prefs.isVolumeWakeEnabled(context)) return
            val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager?.isInteractive == true) return // screen on - leave the keys alone
            Log.d("TAPWAK", "Volume key while screen off - waking")
            onLiftDetected()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        Log.d("TAPWAK", "Service started action=${intent?.action}")

        // An external trigger (accessibility service / UI test) must not depend
        // on this service having been alive at the moment of the key press.
        when (intent?.action) {
            ACTION_WAKE_EXTERNAL -> {
                onExternalWake(intent.getStringExtra(EXTRA_WAKE_SOURCE) ?: "external")
                return START_STICKY
            }
            ACTION_SIMULATE_LIFT -> {
                Log.d("TAPWAK", "Simulated lift received")
                onLiftDetected()
                return START_STICKY
            }
            ACTION_SIMULATE_SHAKE -> {
                Log.d("TAPWAK", "Simulated shake received")
                onShakeDetected()
                return START_STICKY
            }
            ACTION_SIMULATE_AIR_WAVE -> {
                Log.d("TAPWAK", "Simulated air wave received")
                onAirWaveDetected()
                return START_STICKY
            }
        }

        // If the service is (re)started while the screen is already off and
        // volume wake is on, take control of the volume keys immediately.
        if ((Prefs.isVolumeWakeEnabled(this) || Prefs.isMediaButtonWakeEnabled(this)) &&
            VolumeWakeSession.screenIsOff(this)
        ) {
            volumeWakeSession?.activate()
        }

        if (!simulateRegistered) {
            try {
                registerReceiver(simulateReceiver, IntentFilter().apply {
                addAction(ACTION_SIMULATE_LIFT)
                addAction(ACTION_SIMULATE_SHAKE)
                addAction(ACTION_SIMULATE_AIR_WAVE)
            })
                simulateRegistered = true
            } catch (e: SecurityException) {
                Log.w("TAPWAK", "Cannot register simulate receiver", e)
            }
        }
        if (!volumeRegistered) {
            try {
                registerReceiver(volumeReceiver, IntentFilter(VOLUME_CHANGED_ACTION))
                volumeRegistered = true
            } catch (e: SecurityException) {
                Log.w("TAPWAK", "Cannot register volume receiver", e)
            }
        }
        startWatchdog()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        watchdogRunnable?.let { watchdogHandler.removeCallbacks(it) }
        watchdogRunnable = null
        releaseKeepAwake()
        volumeWakeSession?.deactivate()
        if (simulateRegistered) {
            try { unregisterReceiver(simulateReceiver) } catch (_: IllegalArgumentException) { }
            simulateRegistered = false
        }
        if (volumeRegistered) {
            try { unregisterReceiver(volumeReceiver) } catch (_: IllegalArgumentException) { }
            volumeRegistered = false
        }
        liftEngine?.stop()
        liftEngine = null
        shakeEngine?.stop()
        shakeEngine = null
        airWaveEngine?.stop()
        airWaveEngine = null
        screenReceiver?.let { runCatching { unregisterReceiver(it) } }
        screenReceiver = null
        super.onDestroy()
    }

    private fun registerScreenReceiver() {
        if (screenReceiver != null) return
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    Intent.ACTION_SCREEN_OFF -> onScreenOff(context)
                    Intent.ACTION_SCREEN_ON -> onScreenOn()
                }
            }
        }
        screenReceiver = receiver
        registerReceiver(receiver, filter)
    }

    private fun onScreenOff(context: Context) {
        val logging = buildString {
            val charging = isCharging(context)
            append("Screen off: enabled=").append(Prefs.isEnabled(context))
            append(", chargingOnly=").append(Prefs.isChargingOnly(context))
            append(", charging=").append(charging)
            append(", liftEnabled=").append(Prefs.isLiftEnabled(context))
            append(", sensitivity=").append(Prefs.getLiftSensitivity(context))
            append(", shake=").append(Prefs.isShakeEnabled(context))
            append(", air=").append(Prefs.isAirWaveEnabled(context))
            append(", canOverlay=").append(Settings.canDrawOverlays(context))
        }
        Log.d("TAPWAK", logging)

        if (!Prefs.isEnabled(context)) return
        if (Prefs.isChargingOnly(context) && !isCharging(context)) return
        if (!Settings.canDrawOverlays(context)) return

        val lift = Prefs.isLiftEnabled(context)
        val shake = Prefs.isShakeEnabled(context)
        val air = Prefs.isAirWaveEnabled(context)
        val keys = Prefs.isVolumeWakeEnabled(context) || Prefs.isMediaButtonWakeEnabled(context)
        if (lift || shake || air || keys) {
            // Gesture/key modes: stay dark until one of the triggers fires.
            // The partial wake lock keeps the CPU alive for sensor events and
            // for the MediaSession volume callback.
            ignoreNextScreenOn = false
            liftArmed = true
            acquireKeepAwake()
            scheduleKeepAliveRefresh()

            if (lift) {
                liftEngine?.sensitivityDegrees = Prefs.getLiftSensitivity(context)
                liftEngine?.start()
                liftEngine?.arm()
                Log.d("TAPWAK", "Armed raise-to-wake - overlay waits for the raise")
            }
            if (shake) {
                shakeEngine?.sensitivity = Prefs.getShakeSensitivity(context)
                shakeEngine?.start()
                shakeEngine?.arm()
            }
            if (air) {
                airWaveEngine?.sensitivity = Prefs.getAirWaveSensitivity(context)
                airWaveEngine?.start()
                airWaveEngine?.arm()
            }
            if (keys) {
                volumeWakeSession?.activate()
                Log.d("TAPWAK", "Volume/headset watch also active")
            }
            return
        }

        // Tap mode needs no wake lock: the overlay window itself turns the
        // screen back on and holds it while it is visible.
        ignoreNextScreenOn = true
        Log.d("TAPWAK", "Screen off - launching wake overlay")
        val intent = Intent(context, OverlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(OverlayActivity.EXTRA_WAKE_BRIGHTNESS, BRIGHTNESS_DARK)
        }
        runCatching { context.startActivity(intent) }
    }

    /** Called by [LiftWakeEngine] when a flat-to-upright raise is detected. */
    private fun onLiftDetected(source: String = "raise") {
        val context = applicationContext
        liftArmed = false
        releaseKeepAwake()
        volumeWakeSession?.deactivate()
        shakeEngine?.disarm()
        airWaveEngine?.disarm()
        if (!Prefs.isEnabled(context) || !Settings.canDrawOverlays(context)) {
            liftEngine?.stop()
            shakeEngine?.stop()
            airWaveEngine?.stop()
            return
        }
        val glow = Prefs.getLiftBrightness(context)
        ignoreNextScreenOn = true
        Log.d("TAPWAK", "$source detected - showing wake overlay at glow=$glow")
        val intent = Intent(context, OverlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(OverlayActivity.EXTRA_WAKE_BRIGHTNESS, glow)
        }
        runCatching { context.startActivity(intent) }
    }

    /** Called by [ShakeWakeEngine] when a deliberate shake is detected. */
    private fun onShakeDetected() {
        onLiftDetected("shake")
    }

    /** Called by [AirWaveEngine] when a hand wave is detected. */
    private fun onAirWaveDetected() {
        onLiftDetected("air wave")
    }

    /** Wakes from a source that can run even if this service was killed. */
    private fun onExternalWake(source: String) {
        if (!Prefs.isEnabled(this) || !Settings.canDrawOverlays(this)) {
            Log.w("TAPWAK", "External wake blocked: enabled=${Prefs.isEnabled(this)} overlay=${Settings.canDrawOverlays(this)}")
            return
        }
        liftArmed = false
        releaseKeepAwake()
        volumeWakeSession?.deactivate()
        shakeEngine?.disarm()
        airWaveEngine?.disarm()
        ignoreNextScreenOn = true
        val glow = Prefs.getLiftBrightness(this)
        Log.d("TAPWAK", "External wake ($source) - showing wake overlay at glow=$glow")
        val intent = Intent(this, OverlayActivity::class.java).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP
            )
            putExtra(OverlayActivity.EXTRA_WAKE_BRIGHTNESS, glow)
        }
        runCatching { startActivity(intent) }
    }

    /** Called by [VolumeWakeSession] when a volume key arrives with the screen off. */
    private fun onVolumeWake() {
        volumeWakeSession?.deactivate()
        onLiftDetected("volume")
    }

    private fun onScreenOn() {
        if (ignoreNextScreenOn) {
            ignoreNextScreenOn = false
            return
        }
        liftArmed = false
        releaseKeepAwake()
        volumeWakeSession?.deactivate()
        liftEngine?.disarm()
        shakeEngine?.disarm()
        airWaveEngine?.disarm()
        OverlayActivity.finishActive()
    }

    /**
     * A suspended CPU delivers no accelerometer events, so the raise would never
     * be seen without this. It is bounded to [KEEPALIVE_WINDOW_MS] and refreshed
     * from [scheduleKeepAliveRefresh] while the watcher stays armed.
     */
    private fun acquireKeepAwake() {
        if (keepAwakeLock?.isHeld == true) return
        val powerManager = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
        keepAwakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TapWake:keepAwake").apply {
            setReferenceCounted(false)
            acquire(KEEPALIVE_WINDOW_MS)
        }
        Log.d("TAPWAK", "Partial wake lock acquired - CPU stays up for the accelerometer")
    }

    private fun scheduleKeepAliveRefresh() {
        keepAliveRunnable?.let { keepAliveHandler.removeCallbacks(it) }
        val runnable = object : Runnable {
            override fun run() {
                if (!liftArmed) return
                acquireKeepAwake()
                keepAliveHandler.postDelayed(this, KEEPALIVE_REFRESH_MS)
            }
        }
        keepAliveRunnable = runnable
        keepAliveHandler.postDelayed(runnable, KEEPALIVE_REFRESH_MS)
    }

    private fun releaseKeepAwake() {
        keepAliveRunnable?.let { keepAliveHandler.removeCallbacks(it) }
        keepAliveRunnable = null
        keepAwakeLock?.let { if (it.isHeld) it.release() }
        keepAwakeLock = null
    }

    /**
     * Re-checks every 30s that a screen-off volume watcher is still armed. If a
     * ROM tore down the media session or the wake lock, this re-arms it. The
     * service itself is START_STICKY, so Android also restarts it after a kill.
     */
    private fun startWatchdog() {
        if (watchdogRunnable != null) return
        val runnable = object : Runnable {
            override fun run() {
                val off = VolumeWakeSession.screenIsOff(this@WakeService)
                if (off && (Prefs.isVolumeWakeEnabled(this@WakeService) ||
                        Prefs.isMediaButtonWakeEnabled(this@WakeService))
                ) {
                    volumeWakeSession?.activate()
                    acquireKeepAwake()
                    scheduleKeepAliveRefresh()
                }
                watchdogHandler.postDelayed(this, WATCHDOG_INTERVAL_MS)
            }
        }
        watchdogRunnable = runnable
        watchdogHandler.postDelayed(runnable, WATCHDOG_INTERVAL_MS)
    }

    private fun isCharging(context: Context): Boolean {
        val battery = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == BatteryManager.BATTERY_STATUS_CHARGING ||
            status == BatteryManager.BATTERY_STATUS_FULL
    }

    private fun buildNotification(): Notification {
        val channelId = "tapwake_service"
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(channelId, "Double-tap to wake", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_wake)
            .setContentTitle("TapWake")
            .setContentText("Pressing the power button arms the wake screen.")
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 1001

        /** Debug-only: lets adb trigger the raise path on emulators with no accelerometer. */
        const val ACTION_SIMULATE_LIFT = "com.tapwake.app.action.SIMULATE_LIFT"
        const val ACTION_SIMULATE_SHAKE = "com.tapwake.app.action.SIMULATE_SHAKE"
        const val ACTION_SIMULATE_AIR_WAVE = "com.tapwake.app.action.SIMULATE_AIR_WAVE"

        /** Wake triggered outside the service (accessibility service / UI test). */
        const val ACTION_WAKE_EXTERNAL = "com.tapwake.app.action.WAKE_EXTERNAL"
        const val EXTRA_WAKE_SOURCE = "tapwake.wake_source"

        /** Brightness used by the classic double-tap path: near-black but addressable. */
        private const val BRIGHTNESS_DARK = 0.03f

        /** System broadcast sent on every volume step. */
        private const val VOLUME_CHANGED_ACTION = "android.media.VOLUME_CHANGED_ACTION"

        /** How often the sensor wake lock is re-acquired while the watcher is armed. */
        private const val KEEPALIVE_REFRESH_MS = 60_000L

        /** Upper bound on any single wake-lock hold, so a stuck state cannot drain forever. */
        private const val KEEPALIVE_WINDOW_MS = 75_000L

        /** How often to verify the screen-off volume watcher is still armed. */
        private const val WATCHDOG_INTERVAL_MS = 30_000L

        fun start(context: Context) {
            val intent = Intent(context, WakeService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Called by the accessibility service and the UI test button. */
        fun wakeFromExternal(context: Context, source: String) {
            val intent = Intent(context, WakeService::class.java).apply {
                action = ACTION_WAKE_EXTERNAL
                putExtra(EXTRA_WAKE_SOURCE, source)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Debug-only entry point used by [SimulateLiftReceiver]. */
        fun startWithAction(context: Context, action: String) {
            val intent = Intent(context, WakeService::class.java).setAction(action)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeService::class.java))
        }
    }
}
