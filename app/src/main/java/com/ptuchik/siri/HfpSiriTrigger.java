package com.ptuchik.siri;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
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

    private static HfpSiriTrigger instance;

    private Object headsetClientProxy; // BluetoothHeadsetClient (hidden class)
    private String lastStatus = "not initialised";

    public static synchronized HfpSiriTrigger get(Context ctx) {
        if (instance == null) {
            instance = new HfpSiriTrigger();
            instance.bind(ctx.getApplicationContext());
        }
        return instance;
    }

    private void bind(Context ctx) {
        try {
            BluetoothManager bm =
                    (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
            BluetoothAdapter adapter = bm != null ? bm.getAdapter() : null;
            if (adapter == null) {
                lastStatus = "No BluetoothAdapter (BT may be handled outside Android)";
                Log.w(TAG, lastStatus);
                return;
            }
            boolean requested = adapter.getProfileProxy(ctx,
                    new BluetoothProfile.ServiceListener() {
                        @Override
                        public void onServiceConnected(int profile, BluetoothProfile proxy) {
                            headsetClientProxy = proxy;
                            lastStatus = "HEADSET_CLIENT proxy connected: "
                                    + proxy.getClass().getName();
                            Log.i(TAG, lastStatus);
                        }

                        @Override
                        public void onServiceDisconnected(int profile) {
                            headsetClientProxy = null;
                            lastStatus = "HEADSET_CLIENT proxy disconnected";
                            Log.i(TAG, lastStatus);
                        }
                    }, PROFILE_HEADSET_CLIENT);
            if (!requested) {
                lastStatus = "getProfileProxy(HEADSET_CLIENT) returned false — "
                        + "HFP client profile not available in this Android BT stack";
                Log.w(TAG, lastStatus);
            } else {
                lastStatus = "binding to HEADSET_CLIENT…";
            }
        } catch (Throwable t) {
            lastStatus = "bind failed: " + t;
            Log.e(TAG, "bind failed", t);
        }
    }

    /** @return human-readable result for toasts / the test screen */
    public String triggerSiri() {
        if (headsetClientProxy == null) {
            return "No HEADSET_CLIENT proxy (" + lastStatus + ")";
        }
        try {
            @SuppressWarnings("unchecked")
            List<BluetoothDevice> devices = (List<BluetoothDevice>) headsetClientProxy
                    .getClass()
                    .getMethod("getConnectedDevices")
                    .invoke(headsetClientProxy);
            if (devices == null) devices = Collections.emptyList();
            if (devices.isEmpty()) {
                return "HEADSET_CLIENT proxy is up but reports no connected AG device. "
                        + "The phone-call link may be handled outside the Android stack.";
            }
            BluetoothDevice phone = devices.get(0);
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
            Log.e(TAG, "triggerSiri failed", t);
            return "Failed: " + t;
        }
    }

    public String getStatus() {
        return lastStatus;
    }
}
