package com.ptuchik.siri;

import android.accessibilityservice.AccessibilityService;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * Intercepts hardware key events (if — and only if — the steering-wheel mic
 * button is delivered through the Android input pipeline as a KeyEvent).
 *
 * Two modes, controlled from MainActivity via SharedPreferences:
 *  - Discovery mode: toasts + logs every keycode it sees, so you can find out
 *    what (if anything) the SWC mic button emits.
 *  - Trigger mode: key DOWN of the configured keycode calls HfpSiriTrigger
 *    immediately. No phone-connected gating: with no phone the attempt
 *    fails silently (nothing natively listens to this long-press event —
 *    BYD's own assistant uses a short press, a different event).
 *
 * Once enabled, the accessibility framework starts this service at boot and
 * keeps it running, so no separate autostart mechanism is needed.
 *
 * If BYD's voice assistant consumes the button before input dispatch (e.g.
 * directly from CAN in a system service), this service will never see it —
 * that outcome is itself the diagnostic answer.
 */
public class KeyCatcherService extends AccessibilityService {

    private static final String TAG = "KeyCatcher";
    public static final String PREFS = "siri";
    public static final String PREF_DISCOVERY = "discovery_mode";
    public static final String PREF_KEYCODE = "trigger_keycode";
    public static final String PREF_DEBUG = "debug_mode";
    // BYD SWC voice button: kernel BTN_TL2, mapped by DiLink's key layout to
    // keycode 312 (confirmed with discovery mode on the unit — not the AOSP
    // generic mapping of 104/BUTTON_L2).
    public static final int DEFAULT_KEYCODE = 312;
    // Fresh installs run silent (live mode); enable debug from the app UI.
    public static final boolean DEFAULT_DEBUG = false;

    private final Handler main = new Handler(Looper.getMainLooper());

    // True only while the accessibility framework actually has us bound —
    // the secure setting alone can claim "enabled" while the service is dead.
    private static volatile boolean running;

    public static boolean isRunning() {
        return running;
    }

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean debug = p.getBoolean(PREF_DEBUG, DEFAULT_DEBUG);
        HfpSiriTrigger.setDebugLogging(debug);
        // Discovery only exists in debug mode; live mode is silent.
        boolean discovery = debug && p.getBoolean(PREF_DISCOVERY, false);
        int targetKey = p.getInt(PREF_KEYCODE, DEFAULT_KEYCODE);

        int code = event.getKeyCode();
        int action = event.getAction();

        if (discovery) {
            String msg = "KeyEvent: code=" + code + " ("
                    + KeyEvent.keyCodeToString(code) + ") action="
                    + (action == KeyEvent.ACTION_DOWN ? "DOWN" : "UP");
            Log.i(TAG, msg);
            if (action == KeyEvent.ACTION_DOWN) {
                main.post(() -> Toast.makeText(this, msg, Toast.LENGTH_SHORT).show());
            }
            return false; // never consume in discovery mode
        }

        if (code != targetKey) return false;

        if (action == KeyEvent.ACTION_DOWN && event.getRepeatCount() == 0) {
            // Always attempt the trigger — with no phone connected it fails
            // silently (result only surfaces in debug mode). No gating:
            // BYD's own assistant uses a *short* press, which is a different
            // event; this long-press keycode is natively unused.
            String result = HfpSiriTrigger.get(this).triggerSiri();
            if (debug) {
                Log.i(TAG, "trigger → " + result);
                main.post(() -> Toast.makeText(this, result, Toast.LENGTH_SHORT).show());
            }
        }
        return true;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* not used */ }

    @Override
    public void onInterrupt() { /* not used */ }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        running = true;
        boolean debug = getSharedPreferences(PREFS, MODE_PRIVATE)
                .getBoolean(PREF_DEBUG, DEFAULT_DEBUG);
        HfpSiriTrigger.setDebugLogging(debug);
        // Pre-bind the HFP proxy so the first trigger isn't delayed.
        HfpSiriTrigger.get(this);
        if (debug) Log.i(TAG, "KeyCatcherService connected");
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        running = false;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        running = false;
        super.onDestroy();
    }
}
