package com.tapwake.app

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    /** Re-read on resume so returning from the system overlay screen refreshes it. */
    private val overlayGranted = mutableStateOf(false)
    private val accessibilityEnabled = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlayGranted.value = Settings.canDrawOverlays(this)
        accessibilityEnabled.value = isAccessibilityEnabled(this)
        if (Prefs.isEnabled(this)) {
            WakeService.start(this)
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    HomeScreen(
                        canOverlay = overlayGranted.value,
                        volumeAccessEnabled = accessibilityEnabled.value
                    )
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayGranted.value = Settings.canDrawOverlays(this)
        accessibilityEnabled.value = isAccessibilityEnabled(this)
    }
}

@Composable
private fun HomeScreen(canOverlay: Boolean, volumeAccessEnabled: Boolean) {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(Prefs.isEnabled(context)) }
    var chargingOnly by remember { mutableStateOf(Prefs.isChargingOnly(context)) }
    var liftEnabled by remember { mutableStateOf(Prefs.isLiftEnabled(context)) }
    var liftBrightness by remember { mutableStateOf(Prefs.getLiftBrightness(context)) }
    var liftSensitivity by remember { mutableStateOf(Prefs.getLiftSensitivity(context)) }
    var volumeWake by remember { mutableStateOf(Prefs.isVolumeWakeEnabled(context)) }
    var mediaButtonWake by remember { mutableStateOf(Prefs.isMediaButtonWakeEnabled(context)) }
    var chargerWake by remember { mutableStateOf(Prefs.isChargerWakeEnabled(context)) }
    var shakeWake by remember { mutableStateOf(Prefs.isShakeEnabled(context)) }
    var shakeSensitivity by remember { mutableStateOf(Prefs.getShakeSensitivity(context)) }
    var airWave by remember { mutableStateOf(Prefs.isAirWaveEnabled(context)) }
    var airSensitivity by remember { mutableStateOf(Prefs.getAirWaveSensitivity(context)) }
    var airDebug by remember { mutableStateOf("") }

    LaunchedEffect(airWave) {
        while (airWave) {
            airDebug = if (AirWaveDebug.eventCount == 0) {
                "Proximity events: 0 - wave over the sensor while the screen is off"
            } else {
                "Proximity events: ${AirWaveDebug.eventCount} · value=" +
                    "%.1f".format(AirWaveDebug.lastValue) +
                    " · ${if (AirWaveDebug.lastNear) "near" else "far"}"
            }
            delay(400L)
        }
        airDebug = ""
    }
    var ignoringBatteryOptimizations by remember {
        mutableStateOf(isIgnoringBatteryOptimizations(context))
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp)
    ) {
        Text("TapWake", style = MaterialTheme.typography.headlineMedium)
        Spacer(Modifier.height(4.dp))
        Text(
            "Tap or raise the screen to light it up again.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))

        if (!canOverlay) {
            PermissionCard(onClick = { openOverlaySettings(context) })
            Spacer(Modifier.height(16.dp))
        }

        ToggleRow(
            title = "Double-tap to wake",
            subtitle = if (canOverlay) "Watching for the screen going off" else "Needs overlay permission first",
            checked = enabled,
            onChange = { wanted ->
                if (wanted && !canOverlay) {
                    openOverlaySettings(context)
                } else {
                    enabled = wanted
                    Prefs.setEnabled(context, wanted)
                    if (wanted) WakeService.start(context) else WakeService.stop(context)
                }
            }
        )

        ToggleRow(
            title = "Raise to wake",
            subtitle = "Screen wakes at a dim glow when you raise the phone from flat",
            checked = liftEnabled,
            onChange = { value ->
                liftEnabled = value
                Prefs.setLiftEnabled(context, value)
                if (Prefs.isEnabled(context)) WakeService.start(context) else WakeService.stop(context)
            }
        )

        if (liftEnabled) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    "Wake glow: ${(liftBrightness * 100).roundToInt()}%",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = liftBrightness,
                    onValueChange = { value ->
                        liftBrightness = value
                        Prefs.setLiftBrightness(context, value)
                    },
                    valueRange = 0.03f..0.30f
                )
                Text(
                    "Raise sensitivity: ${liftSensitivity.roundToInt()} deg",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = liftSensitivity,
                    onValueChange = { value ->
                        liftSensitivity = value
                        Prefs.setLiftSensitivity(context, value)
                    },
                    onValueChangeFinished = {
                        if (Prefs.isEnabled(context)) WakeService.start(context)
                    },
                    valueRange = 5f..45f,
                    steps = 7
                )
                Text(
                    "Lower wakes as soon as the phone lifts; higher needs a clearer raise.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        ToggleRow(
            title = "Volume keys to wake",
            subtitle = "Pressing volume up or down lights the screen at the Wake glow level",
            checked = volumeWake,
            onChange = { value ->
                volumeWake = value
                Prefs.setVolumeWakeEnabled(context, value)
                if (Prefs.isEnabled(context)) WakeService.start(context) else WakeService.stop(context)
            }
        )

        ToggleRow(
            title = "Shake to wake",
            subtitle = "Shake the phone while the screen is off to light it at the Wake glow level",
            checked = shakeWake,
            onChange = { value ->
                shakeWake = value
                Prefs.setShakeEnabled(context, value)
                if (Prefs.isEnabled(context)) WakeService.start(context) else WakeService.stop(context)
            }
        )

        if (shakeWake) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    "Shake sensitivity: ${shakeSensitivity.roundToInt()}/10",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = shakeSensitivity,
                    onValueChange = { value ->
                        shakeSensitivity = value
                        Prefs.setShakeSensitivity(context, value)
                    },
                    onValueChangeFinished = {
                        if (Prefs.isEnabled(context)) WakeService.start(context)
                    },
                    valueRange = 1f..10f,
                    steps = 8
                )
                Text(
                    "Lower wakes on a gentle nudge; higher ignores small movements.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        ToggleRow(
            title = "Air wave to wake",
            subtitle = "Wave your hand over the proximity sensor while the screen is off",
            checked = airWave,
            onChange = { value ->
                airWave = value
                Prefs.setAirWaveEnabled(context, value)
                if (Prefs.isEnabled(context)) WakeService.start(context) else WakeService.stop(context)
            }
        )

        if (airWave) {
            val sensorInfo = remember { AirWaveEngine.info(context) }
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp)
            ) {
                Text(
                    "Air sensitivity: ${airSensitivity.roundToInt()}/10",
                    style = MaterialTheme.typography.bodyMedium
                )
                Slider(
                    value = airSensitivity,
                    onValueChange = { value ->
                        airSensitivity = value
                        Prefs.setAirWaveSensitivity(context, value)
                    },
                    onValueChangeFinished = {
                        if (Prefs.isEnabled(context)) WakeService.start(context)
                    },
                    valueRange = 1f..10f,
                    steps = 8
                )
                Text(
                    if (airSensitivity.roundToInt() <= 2)
                        "Two quick waves are required to avoid pocket and bed false wakes."
                    else
                        "One clear wave wakes the screen.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (sensorInfo.available)
                        "Proximity: ${sensorInfo.name} · wake-up=${sensorInfo.wakeUp} · range=${sensorInfo.maxRange} cm"
                    else
                        "No proximity sensor found. Air wave will not work on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (airDebug.isNotBlank()) {
                    Text(
                        airDebug,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        ToggleRow(
            title = "Headset button to wake",
            subtitle = "Play/pause on wired or Bluetooth headphones wakes the screen",
            checked = mediaButtonWake,
            onChange = { value ->
                mediaButtonWake = value
                Prefs.setMediaButtonWakeEnabled(context, value)
            }
        )

        ToggleRow(
            title = "Charger to wake",
            subtitle = "Plugging in the USB cable lights the screen",
            checked = chargerWake,
            onChange = { value ->
                chargerWake = value
                Prefs.setChargerWakeEnabled(context, value)
            }
        )

        if (volumeWake && !volumeAccessEnabled) {
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Volume key access (optional)", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Volume wake runs permanently through TapWake's foreground service " +
                            "and media session. Accessibility is only a fallback for phones " +
                            "that block it. Android itself may switch it off after an app " +
                            "update - volume wake still works without it.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = { openAccessibilitySettings(context) }) {
                        Text("Open Accessibility settings")
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        OutlinedButton(
            onClick = {
                if (!canOverlay) {
                    Toast.makeText(context, "Grant display-over permission first", Toast.LENGTH_SHORT).show()
                } else {
                    WakeService.wakeFromExternal(context, "test button")
                    Toast.makeText(context, "Wake triggered - check the screen", Toast.LENGTH_SHORT).show()
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text("Test wake now") }
        Spacer(Modifier.height(12.dp))

        if (liftEnabled && !ignoringBatteryOptimizations) {
            Surface(
                tonalElevation = 2.dp,
                shape = MaterialTheme.shapes.medium,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Battery optimisation", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Some phones pause the watcher while the screen is off unless the app " +
                            "is excluded from battery optimisation.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(Modifier.height(12.dp))
                    Button(onClick = {
                        requestIgnoreBatteryOptimizations(context)
                        ignoringBatteryOptimizations = true
                    }) { Text("Allow unrestricted") }
                }
            }
            Spacer(Modifier.height(16.dp))
        }

        ToggleRow(
            title = "Only while charging",
            subtitle = "Keep daily battery drain at zero - the watcher runs only when plugged in",
            checked = chargingOnly,
            onChange = { value ->
                chargingOnly = value
                Prefs.setChargingOnly(context, value)
            }
        )

        Spacer(Modifier.height(24.dp))
        Text("How it works", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            "Tap mode: when the screen goes off, TapWake shows a black screen that is awake " +
                "at near-zero brightness so the digitizer can see your double tap. The display " +
                "pipeline has to stay active for that - the cost no app can avoid; firmware " +
                "double-tap-to-wake is cheaper because the touch controller does it with the " +
                "processor asleep.\n\n" +
                "Raise mode: only the accelerometer listens. The screen wakes at the glow you " +
                "set once the phone tilts past the sensitivity angle, which costs far less than " +
                "keeping the display lit.\n\n" +
                "Shake: the accelerometer watches for a deliberate shake at your chosen " +
                "sensitivity.\n\n" +
                "Air wave: the proximity sensor sees a hand pass over the phone. Low " +
                "sensitivity needs two waves; high sensitivity accepts one. A long cover " +
                "is ignored, so a pocket or pillow will not wake it.\n\n" +
                "Headset and charger wake use system broadcasts.\n\n" +
                "Volume keys: when enabled, volume up or down lights the screen while it is " +
                "off, at the same glow level. Headset and charger wake use system broadcasts " +
                "and need no accessibility service.\n\n" +
                "With 'Only while charging' on, the watcher switches itself off whenever the " +
                "phone is unplugged, so everyday use costs nothing.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "A PIN or pattern is still asked for after waking. This app does not bypass " +
                "your lock screen.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
        OutlinedButton(
            onClick = { WakeService.stop(context); Prefs.setEnabled(context, false); enabled = false },
            modifier = Modifier.fillMaxWidth(),
            enabled = enabled
        ) { Text("Turn everything off") }
        Spacer(Modifier.height(48.dp))
    }
}

@Composable
private fun PermissionCard(onClick: () -> Unit) {
    Surface(
        tonalElevation = 2.dp,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Display over other apps", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text(
                "Required so the wake screen can appear above the lock screen.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))
            Button(onClick = onClick) { Text("Allow") }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String?,
    checked: Boolean,
    onChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val powerManager = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
    return powerManager?.isIgnoringBatteryOptimizations(context.packageName) ?: false
}

private fun requestIgnoreBatteryOptimizations(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun isAccessibilityEnabled(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    val component = ComponentName(context, VolumeKeyAccessibilityService::class.java).flattenToString()
    return enabled.split(':').any { it.equals(component, ignoreCase = true) }
}

private fun openAccessibilitySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}

private fun openOverlaySettings(context: Context) {
    runCatching {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
