package com.ptuchik.siri;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Method;
import java.util.Collections;
import java.util.List;

/**
 * Binds to the hidden HFP-client profile (BluetoothProfile.HEADSET_CLIENT = 16)
 * and invokes startVoiceRecognition(BluetoothDevice) via reflection.
 *
 * On AOSP, startVoiceRecognition() on the HFP client sends AT+BVRA=1 to the
 * connected Audio Gateway (the iPhone), which activates Siri.
 *
 * This will fail (gracefully, with a logged reason) if:
 *  - the head unit's BT telephony does not go through the Android BT stack
 *  - hidden-API enforcement blocks the reflection
 *  - the method requires a privileged permission this app doesn't hold
 */
public class HfpSiriTrigger {

    private static final String TAG = "HfpSiriTrigger";
    // BluetoothProfile.HEADSET_CLIENT (hidden constant)
    private static final int PROFILE_HEADSET_CLIENT = 16;

    private static final long BIND_RETRY_MS = 5000;

    // Debug mode: log everything. Live mode: fully silent.
    private static volatile boolean debugLogging = true;

    private static HfpSiriTrigger instance;

    public static void setDebugLogging(boolean enabled) {
        debugLogging = enabled;
    }

    private static void log(String msg) {
        if (debugLogging) Log.i(TAG, msg);
    }

    private static void log(String msg, Throwable t) {
        if (debugLogging) Log.w(TAG, msg, t);
    }

    private Context appContext;
    private Object headsetClientProxy; // BluetoothHeadsetClient (hidden class)
    private String lastStatus = "not initialised";
    private long lastBindAttempt;
    private volatile boolean permissionDenied;

    // Fallback phone source: ACL connect/disconnect broadcasts (fed by
    // ReviveReceiver) — supplies a device even when the proxy's list is
    // empty because BLUETOOTH_CONNECT was revoked or the list is unreliable.
    private static volatile BluetoothDevice lastAclDevice;

    static void onAclEvent(BluetoothDevice device, boolean connected) {
        if (device == null) return;
        if (connected) {
            lastAclDevice = device;
        } else {
            BluetoothDevice last = lastAclDevice;
            if (last != null && last.getAddress().equals(device.getAddress())) {
                lastAclDevice = null;
            }
        }
    }

    public static synchronized HfpSiriTrigger get(Context ctx) {
        if (instance == null) {
            instance = new HfpSiriTrigger();
            instance.appContext = ctx.getApplicationContext();
            instance.bind(instance.appContext);
        }
        return instance;
    }

    /** Retries the profile bind (throttled) if it failed or the proxy dropped. */
    private synchronized void ensureBound() {
        if (headsetClientProxy != null) return;
        if (SystemClock.elapsedRealtime() - lastBindAttempt < BIND_RETRY_MS) return;
        bind(appContext);
    }

    private void bind(Context ctx) {
        lastBindAttempt = SystemClock.elapsedRealtime();
        try {
            BluetoothManager bm =
                    (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
            if (adapter == null) {
                lastStatus = "No BluetoothAdapter (BT may be handled outside Android)";
                log(lastStatus);
                return;
            }
            boolean requested = adapter.getProfileProxy(ctx,
                    new BluetoothProfile.ServiceListener() {
                        @Override
                        public void onServiceConnected(int profile, BluetoothProfile proxy) {
                            headsetClientProxy = proxy;
                            lastStatus = "HEADSET_CLIENT proxy connected: "
                                    + proxy.getClass().getName();
                            log(lastStatus);
                        }

                        @Override
                        public void onServiceDisconnected(int profile) {
                            headsetClientProxy = null;
                            lastStatus = "HEADSET_CLIENT proxy disconnected";
                            log(lastStatus);
                        }
                    }, PROFILE_HEADSET_CLIENT);
            if (!requested) {
                lastStatus = "getProfileProxy(HEADSET_CLIENT) returned false — "
                        + "HFP client profile not available in this Android BT stack";
                log(lastStatus);
            } else {
                lastStatus = "binding to HEADSET_CLIENT…";
            }
        } catch (Throwable t) {
            lastStatus = "bind failed: " + t;
            log("bind failed", t);
        }
    }

    private List<BluetoothDevice> connectedDevices() {
        if (headsetClientProxy == null) return Collections.emptyList();
        try {
            @SuppressWarnings("unchecked")
            List<BluetoothDevice> devices = (List<BluetoothDevice>) headsetClientProxy
                    .getClass()
                    .getMethod("getConnectedDevices")
                    .invoke(headsetClientProxy);
            permissionDenied = false;
            return devices != null ? devices : Collections.emptyList();
        } catch (SecurityException e) {
            permissionDenied = true;
            lastStatus = "BLUETOOTH_CONNECT denied — run: adb shell pm grant "
                    + appContext.getPackageName()
                    + " android.permission.BLUETOOTH_CONNECT";
            log(lastStatus, e);
            return Collections.emptyList();
        } catch (Throwable t) {
            log("getConnectedDevices failed", t);
            return Collections.emptyList();
        }
    }

    /**
     * @return human-readable result for toasts / the test screen.
     *
     * Deliberately does NOT touch the audio route: Siri answering over A2DP
     * (media channel) is accepted behavior. An SCO-first variant (bring up
     * the telephony link before +BVRA) was tried in v1.0.4 and rejected —
     * it added latency and was unstable. Don't reintroduce it.
     */
    public String triggerSiri() {
        ensureBound();
        if (headsetClientProxy == null) {
            return "No HEADSET_CLIENT proxy (" + lastStatus + ")";
        }
        try {
            List<BluetoothDevice> devices = connectedDevices();
            BluetoothDevice phone;
            if (!devices.isEmpty()) {
                phone = devices.get(0);
            } else if (lastAclDevice != null) {
                // Proxy list empty (e.g. permission revoked) but ACL tracking
                // saw a phone connect — try it anyway.
                phone = lastAclDevice;
            } else if (permissionDenied) {
                return lastStatus;
            } else {
                return "HEADSET_CLIENT proxy is up but reports no connected AG device. "
                        + "The phone-call link may be handled outside the Android stack.";
            }

            Method m = headsetClientProxy.getClass()
                    .getMethod("startVoiceRecognition", BluetoothDevice.class);
            Object result = m.invoke(headsetClientProxy, phone);
            boolean ok = Boolean.TRUE.equals(result);
            return ok
                    ? "AT+BVRA sent to " + phone.getName() + " — Siri should be active"
                    : "startVoiceRecognition() returned false (AG rejected or profile busy)";
        } catch (NoSuchMethodException e) {
            return "Hidden API blocked or method missing: " + e;
        } catch (SecurityException e) {
            return "Permission denied by BT service: " + e.getMessage();
        } catch (Throwable t) {
            log("triggerSiri failed", t);
            return "Failed: " + t;
        }
    }

    public String getStatus() {
        return lastStatus;
    }
}
