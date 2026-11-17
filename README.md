# TapWake

Android utility for waking the screen without the power button.

## Features

- Double-tap to wake using a near-black overlay
- Raise to wake with a 5–45 degree sensitivity slider
- Shake to wake with a 1–10 sensitivity slider
- Air wave to wake using the proximity sensor
- Volume keys to wake via a MediaSession remote volume provider
- Headset play/pause button to wake
- Charger connection to wake
- Wake glow brightness control
- "Only while charging" watchdog gate
- Start after reboot and app update

## Build

```bash
./gradlew :app:assembleDebug
```

APK output:

```text
app/build/outputs/apk/debug/app-debug.apk
```

## Install

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Grant the overlay permission when prompted. Battery optimisation should be disabled for reliable screen-off sensors.

## Notes

- No root required.
- Accessibility is optional and not required for the primary wake paths.
- Lighting the screen does not bypass PIN, pattern, password, fingerprint, or face unlock.
