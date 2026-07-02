# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this app is

A diagnostic Android app for a BYD DiLink car head unit. **The final result this project is working toward: a long press of the SWC (steering-wheel control) Voice Assistant button on the BYD triggers Siri on the iPhone over Bluetooth** — by sending HFP `AT+BVRA=1` to the Bluetooth-connected iPhone (a short press must still reach BYD's own assistant). The current app is deliberately built to *answer three unknowns empirically* (see README.md): whether DiLink's BT stack is Android's, whether hidden-API enforcement blocks the reflection call, and whether the mic button reaches Android input dispatch at all. Failure messages are the product — every error path returns a human-readable diagnostic string shown as a toast/status, not just a log line. Preserve that property when changing code.

## Build

- `./gradlew assembleDebug` — AGP 8.10.1 with a Gradle 8.13 wrapper (requires JDK 17–21 to run; Android Studio's embedded JBR works: `JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"`).
- Java 8 source/target, compileSdk 33, minSdk 26. Plain Java, no Kotlin, no dependencies, no tests. `applicationId` is `com.ptuchik.siritrigger` (hyphens are invalid in Android application IDs); the Java `namespace` is `com.ptuchik.siri` — they intentionally differ.
- Install output (`app/build/outputs/apk/debug/`) on the head unit via ADB or file-manager sideload.

## Architecture

Three classes in `app/src/main/java/com/ptuchik/siri/`, two entry points:

- **`HfpSiriTrigger`** — singleton (`HfpSiriTrigger.get(ctx)`) that binds the *hidden* HFP-client profile (`BluetoothProfile.HEADSET_CLIENT` = 16, hardcoded because the constant is not in the SDK) and invokes `startVoiceRecognition(BluetoothDevice)` **via reflection**. Do not replace the reflection/`Object` handling with SDK types — the class (`BluetoothHeadsetClient`) is hidden and won't compile against the public SDK. Binding happens once at first `get()`; `lastStatus` records the outcome for the UI.
- **`KeyCatcherService`** — AccessibilityService with `flagRequestFilterKeyEvents` (config in `res/xml/keycatcher_config.xml`). Two modes: *discovery* (toast/log every keycode, never consume) and *trigger* (long-press ≥ 600 ms of the configured keycode → `HfpSiriTrigger`; short press is passed through so BYD's own assistant still works).
- **`MainActivity`** — manual test button (fires the HFP trigger directly, bypassing the key path), shortcut to Accessibility settings, and settings editor.

The two components communicate only through SharedPreferences: file `"siri"` (`KeyCatcherService.PREFS`), keys `discovery_mode` (bool) and `trigger_keycode` (int, default 231 = `KEYCODE_VOICE_ASSIST`). MainActivity writes, the service reads on every key event.

## Testing / debugging

There is no emulator path that means anything — behavior must be verified on the head unit. Follow the ordered test procedure in README.md (on-screen HFP test first, then keycode discovery, then arming the trigger). Logs: `adb logcat -s HfpSiriTrigger KeyCatcher`. If hidden-API enforcement blocks reflection: `adb shell settings put global hidden_api_policy 1`.
