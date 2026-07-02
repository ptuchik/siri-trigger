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
 *  - Trigger mode: long-press (>= LONG_PRESS_MS) of the configured keycode
 *    calls HfpSiriTrigger.
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
    private static final long LONG_PRESS_MS = 600;

    private long downTime = -1;
    private final Handler main = new Handler(Looper.getMainLooper());

    @Override
    protected boolean onKeyEvent(KeyEvent event) {
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);
        boolean discovery = p.getBoolean(PREF_DISCOVERY, true);
        int targetKey = p.getInt(PREF_KEYCODE, KeyEvent.KEYCODE_VOICE_ASSIST);

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

        if (action == KeyEvent.ACTION_DOWN) {
            if (event.getRepeatCount() == 0) downTime = event.getEventTime();
            return true; // hold the key while we decide
        }
        if (action == KeyEvent.ACTION_UP) {
            long held = event.getEventTime() - downTime;
            downTime = -1;
            if (held >= LONG_PRESS_MS) {
                String result = HfpSiriTrigger.get(this).triggerSiri();
                Log.i(TAG, "long-press → " + result);
                main.post(() -> Toast.makeText(this, result, Toast.LENGTH_LONG).show());
                return true; // consume: don't also fire BYD assistant
            }
            return false; // short press: let the system/BYD handle it
        }
        return false;
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) { /* not used */ }

    @Override
    public void onInterrupt() { /* not used */ }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        // Pre-bind the HFP proxy so the first trigger isn't delayed.
        HfpSiriTrigger.get(this);
        Log.i(TAG, "KeyCatcherService connected");
    }
}
