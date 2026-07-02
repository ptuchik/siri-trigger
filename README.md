# SiriTrigger — HFP voice-recognition trigger for BYD DiLink

Goal: long-press of the steering-wheel mic button on a DiLink head unit →
send HFP `AT+BVRA=1` to the Bluetooth-connected iPhone → Siri activates.

## What is verified vs. not

Verified (Bluetooth HFP spec + AOSP source):
- HFP carries a voice-recognition activation command (`AT+BVRA=1`);
  Apple implements it — it is how car "voice" buttons trigger Siri.
- AOSP's hidden `BluetoothHeadsetClient.startVoiceRecognition(BluetoothDevice)`
  sends that command when Android is the HFP hands-free role.

NOT verified for DiLink 5.0 (must be tested on the unit):
1. Whether DiLink's BT phone stack is Android's stack at all (vs. external MCU).
2. Whether hidden-API enforcement / permission checks block the reflection call.
3. Whether the SWC mic button is delivered as an Android KeyEvent visible to
   an AccessibilityService, or consumed earlier by BYD's own service.

The app is built to answer all three empirically.

## Build

Open the project in Android Studio (AGP 7.4.x, compileSdk 33) and
Build → Build APK, or:

    ./gradlew assembleDebug

Install on the head unit (ADB or file manager sideload).

## Test procedure (in order)

1. **On-screen test first.** Open the app, wait for "HFP status", press
   "TEST: Trigger Siri now". Outcomes:
   - Siri activates on the iPhone → unknowns #1 and #2 are cleared. Continue.
   - "getProfileProxy returned false" or "no BluetoothAdapter" → BT is not in
     the Android stack (or HFP-client profile disabled). The APK approach is
     dead on this firmware; stop here.
   - "Hidden API blocked" → if you have ADB:
         adb shell settings put global hidden_api_policy 1
     then reboot the app and retest.
   - "Permission denied" → the BT service enforces a privileged permission on
     this build. Works only if the APK is installed to /system/priv-app (root)
     with a privapp-permissions whitelist entry, or platform-signed.

2. **Find the button's keycode.** Enable the KeyCatcher accessibility service
   (button in the app opens Settings). Leave "Discovery mode" checked. Press
   the SWC mic button. If a toast shows a keycode → note it. If nothing ever
   appears, BYD consumes the button before input dispatch; an unprivileged app
   cannot intercept it. (With ADB, `getevent -l` can confirm whether the button
   exists as an input event at all.)

3. **Arm the trigger.** Uncheck Discovery mode, enter the keycode found in
   step 2 (default 231 = KEYCODE_VOICE_ASSIST), Save. Long-press (>= 600 ms)
   the SWC mic button → Siri. Short press still goes to the BYD assistant.

## Notes

- Android 12+ builds will additionally prompt for the BLUETOOTH_CONNECT
  runtime permission on first use; grant it.
- The accessibility service pre-binds the HFP proxy so the first long-press
  isn't delayed.
- Everything is logged under tags `HfpSiriTrigger` and `KeyCatcher`
  (`adb logcat -s HfpSiriTrigger KeyCatcher`).
