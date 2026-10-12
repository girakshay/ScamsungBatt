# ScamsungBatt (Battery Pill Control)

Samsung One UI 8.5 and later always show the battery percentage inside the status bar battery pill, and
the old "show battery percentage" toggle is gone. This app draws its own pill, without digits, over
Samsung's battery icon.

Tested on a Galaxy S25 running One UI 9. Samsung's SystemUI does not expose a setting that removes only
the number, so the pill covers it.

## How it works

- An accessibility service (`PillService`) draws an opaque capsule with a level fill, and a bolt while charging.
- It reads the exact bounds of Samsung's battery view (`com.android.systemui:id/battery`) so the pill matches
  Samsung's size and position in every state (charging, 100%, unplugged), and the other icons never move.
- The pill hides in the notification shade, and can be shown or hidden on the always-on display.
- Optionally the app also asks SystemUI to hide the number by adding `battery_percent` to the secure
  `icon_blacklist` setting. Samsung may ignore this.

Hiding Samsung's whole battery (`battery_icon` token) is not used: it collapses the slot and shifts the
Wi-Fi and signal icons to the screen edge.

## Setup

1. Build: `./gradlew assembleDebug`. Needs JDK 17 and Android SDK platform 37 plus build-tools 37
   (`sdkmanager "platforms;android-37.0" "build-tools;37.0.0"`). The Gradle wrapper downloads Gradle 9.3.1.
2. Install: `adb install -r app/build/outputs/apk/debug/app-debug.apk`
3. Grant the write permission once:
   `adb shell pm grant dev.scamsung.probe android.permission.WRITE_SECURE_SETTINGS`
4. Settings > Apps > Battery Pill Control > menu > Allow restricted settings.
5. Settings > Accessibility > Installed apps > Battery Pill Control > on.
6. Open the app and turn on "Remove percentage from battery pill".

## UI

The settings screen is Jetpack Compose with Material 3 Expressive (expressive theme and motion, shape-morphing
buttons) and Material You dynamic colours. Expressive components only exist in `material3` 1.5.0, which is still
beta and needs Android Gradle Plugin 9.1+ and compile SDK 37, so the Compose BOM's `material3` 1.4.0 is overridden.
The probe tools screen is Compose too, with the same theme.

## Notes

- The service reads only the position of the system battery icon and which system windows are open. It
  never reads app content.
- "Probe tools" in the app dumps system settings and diffs two snapshots, useful for finding settings
  keys on other One UI builds.
