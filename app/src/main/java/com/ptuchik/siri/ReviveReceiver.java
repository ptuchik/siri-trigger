package com.ptuchik.siri;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

/**
 * Self-healing: DiLink can leave the accessibility service unbound after a
 * restart (setting says enabled, service dead). This receiver revives it on
 * two signals — boot completed, and any Bluetooth device connecting (fires
 * when the iPhone pairs at ignition, so recovery needs no screen taps).
 *
 * It first gives the framework a moment to bind the service on its own and
 * only re-kicks if it is still dead, so a healthy service is never touched.
 */
public class ReviveReceiver extends BroadcastReceiver {

    private static final long SETTLE_MS = 3000;

    @Override
    public void onReceive(Context context, Intent intent) {
        final String action = intent.getAction();
        boolean connected = BluetoothDevice.ACTION_ACL_CONNECTED.equals(action);
        boolean disconnected = BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(action);
        if (connected || disconnected) {
            // Keep the fallback phone detector current even if the HFP
            // proxy's device list is unavailable.
            BluetoothDevice device =
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            HfpSiriTrigger.onAclEvent(device, connected);
        }
        boolean relevant = Intent.ACTION_BOOT_COMPLETED.equals(action) || connected;
        if (!relevant) return;
        if (!KeyCatcherToggle.isEnabledInSettings(context)) return;
        if (KeyCatcherService.isRunning()) return;

        final Context app = context.getApplicationContext();
        final PendingResult result = goAsync();
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (KeyCatcherService.isRunning()) {
                result.finish();
                return;
            }
            boolean debug = app.getSharedPreferences(
                            KeyCatcherService.PREFS, Context.MODE_PRIVATE)
                    .getBoolean(KeyCatcherService.PREF_DEBUG,
                            KeyCatcherService.DEFAULT_DEBUG);
            if (debug) Log.i("KeyCatcher", action + ": service dead — re-kicking");
            KeyCatcherToggle.kick(app, result::finish);
        }, SETTLE_MS);
    }
}
