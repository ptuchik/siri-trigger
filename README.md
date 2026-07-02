# SiriTrigger — HFP voice-recognition trigger for BYD DiLink

Goal: long press of the steering-wheel voice button on a DiLink head unit →
send HFP `AT+BVRA=1` to the Bluetooth-connected iPhone → Siri activates.
The long press is natively unused (BYD's own assistant opens/closes on a
*short* press, which is a different event), so intercepting it conflicts
with nothing. With no iPhone connected the press is a silent no-op.

## What is verified vs. not

Verified **on the DiLink unit**:
1. DiLink's BT phone stack is Android's stack — the HFP-client proxy binds. ✔
2. The reflection call works: the on-screen test button sends `AT+BVRA=1`
   and Siri activates on the iPhone. ✔
3. The SWC voice button exists as a kernel input event (`adb shell getevent -l`
   shows `BTN_TL2` DOWN/UP) and **does reach the AccessibilityService's key
   filter**: DiLink's key layout maps it to keycode **312** (confirmed with
   Discovery mode; note this is a vendor mapping — AOSP's generic layout
   would have produced 104/BUTTON_L2). 312 is the default trigger keycode. ✔

## Build

Open the project in Android Studio and Build → Build APK, or:

    ./gradlew assembleRelease

The signed production APK lands at
`app/build/outputs/apk/release/siri-trigger.apk` (signing uses the
checked-in personal-use `release.keystore`). Install on the head unit
(ADB or file manager sideload). Fresh installs default to silent live
mode; turn on "Debug mode" in the app when diagnosing.

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

2. **Enable KeyCatcher.** The in-app button tries the Accessibility settings
   screen first; DiLink has none, so it falls back to enabling the service
   directly — which needs a one-time adb grant:

       adb shell pm grant com.ptuchik.siritrigger android.permission.WRITE_SECURE_SETTINGS

   then tap the button again. (Or skip the app entirely:
   `adb shell settings put secure enabled_accessibility_services com.ptuchik.siritrigger/com.ptuchik.siri.KeyCatcherService`
   followed by `adb shell settings put secure accessibility_enabled 1`.)

3. **Confirm the keycode** (already done on this unit: **312**). With Debug
   and Discovery mode checked, press the SWC voice button and note the
   toasted code. If no toast ever appears, BYD consumes the button before
   input dispatch reaches the service.

4. **Arm the trigger.** Uncheck Discovery mode, keep keycode 312 (or whatever
   discovery showed), Save. Long-pressing the SWC voice button now triggers
   Siri immediately (on key DOWN). With no phone connected the press does
   nothing, silently. Short press is a different event and still runs BYD's
   own assistant as stock.

## Notes

- Android 12+ builds will additionally prompt for the BLUETOOTH_CONNECT
  runtime permission on first use; grant it.
- **DiLink "Disable Self-Start" list:** DiLink blocks background process
  starts for every app by default — each app is listed with its self-start
  *disabled* (toggle on = blocked). While Siri Trigger is blocked there,
  neither the boot receiver nor the boot-time accessibility bind can run —
  the app looks enabled but is dead until launched manually. Turn the
  toggle OFF for Siri Trigger in that list (off = self-start allowed);
  this is required for everything below to work after a restart.
- Once enabled, the accessibility service is normally started by the system
  at boot. DiLink, however, can restore the setting at boot *without binding
  the service* — it looks enabled but never sees keys. The app works around
  this by re-toggling the secure setting (forcing a rebind) from three
  places: a receiver listening for boot **and** for Bluetooth devices
  connecting (i.e. the iPhone pairing at ignition — it waits 3 s and only
  kicks if the service is still dead), app launch, and the enable button
  (which reports "enabled but not bound" and restarts it). All of this
  needs the one-time WRITE_SECURE_SETTINGS adb grant from step 2.
- The service pre-binds the HFP proxy so the first press isn't delayed, and
  rebinds automatically (throttled to every 5 s) if the proxy was never up
  or dropped.
- Debug vs. live mode: with "Debug mode" checked, every trigger shows a toast
  and everything is logged under tags `HfpSiriTrigger` and `KeyCatcher`
  (`adb logcat -s HfpSiriTrigger KeyCatcher`), and Discovery mode is
  available. Unchecked (live mode) the app is completely silent — no toasts,
  no logs — and just triggers Siri.
