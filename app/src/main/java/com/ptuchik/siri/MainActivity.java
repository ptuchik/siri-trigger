package com.ptuchik.siri;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        status = findViewById(R.id.status);
        Button test = findViewById(R.id.btnTest);
        Button openA11y = findViewById(R.id.btnA11y);
        CheckBox debug = findViewById(R.id.chkDebug);
        CheckBox discovery = findViewById(R.id.chkDiscovery);
        EditText keycode = findViewById(R.id.editKeycode);
        Button save = findViewById(R.id.btnSave);

        SharedPreferences p = getSharedPreferences(KeyCatcherService.PREFS, MODE_PRIVATE);
        debug.setChecked(p.getBoolean(KeyCatcherService.PREF_DEBUG,
                KeyCatcherService.DEFAULT_DEBUG));
        discovery.setChecked(p.getBoolean(KeyCatcherService.PREF_DISCOVERY, false));
        HfpSiriTrigger.setDebugLogging(debug.isChecked());
        keycode.setText(String.valueOf(
                p.getInt(KeyCatcherService.PREF_KEYCODE, KeyCatcherService.DEFAULT_KEYCODE)));

        // BLUETOOTH_CONNECT is a runtime permission on Android 12+ and gets
        // reset by a reinstall — without it the proxy reports no devices.
        if (android.os.Build.VERSION.SDK_INT >= 31 && checkSelfPermission(
                "android.permission.BLUETOOTH_CONNECT")
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(
                    new String[]{"android.permission.BLUETOOTH_CONNECT"}, 1);
        }

        // Bind HFP proxy immediately, then keep the status line fresh while
        // the proxy connects (it can take a few seconds after boot).
        HfpSiriTrigger trigger = HfpSiriTrigger.get(this);
        status.postDelayed(new Runnable() {
            private int updates = 0;

            @Override
            public void run() {
                status.setText("HFP status: " + trigger.getStatus());
                if (++updates < 6) status.postDelayed(this, 1500);
            }
        }, 1500);

        // Recover from the post-reboot "enabled but not bound" state: opening
        // the app re-kicks the service if the setting says on but it's dead.
        if (KeyCatcherToggle.isEnabledInSettings(this)
                && !KeyCatcherService.isRunning()
                && KeyCatcherToggle.canWriteSecureSettings(this)) {
            Toast.makeText(this, "KeyCatcher not running — restarting…",
                    Toast.LENGTH_SHORT).show();
            KeyCatcherToggle.kick(this, null);
        }

        test.setOnClickListener(v -> {
            String result = trigger.triggerSiri();
            status.setText(result);
            Toast.makeText(this, result, Toast.LENGTH_LONG).show();
        });

        openA11y.setOnClickListener(v -> enableKeyCatcher());

        save.setOnClickListener(v -> {
            int code;
            try {
                code = Integer.parseInt(keycode.getText().toString().trim());
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Invalid keycode", Toast.LENGTH_SHORT).show();
                return;
            }
            p.edit()
                    .putBoolean(KeyCatcherService.PREF_DEBUG, debug.isChecked())
                    .putBoolean(KeyCatcherService.PREF_DISCOVERY, discovery.isChecked())
                    .putInt(KeyCatcherService.PREF_KEYCODE, code)
                    .apply();
            HfpSiriTrigger.setDebugLogging(debug.isChecked());
            Toast.makeText(this, "Saved. Debug=" + debug.isChecked()
                    + ", discovery=" + discovery.isChecked()
                    + ", keycode=" + code, Toast.LENGTH_SHORT).show();
        });
    }

    /**
     * Enables the KeyCatcher accessibility service. Head units often ship
     * without the Accessibility settings screen, so this tries, in order:
     * the Settings screen → writing the secure setting directly (needs
     * WRITE_SECURE_SETTINGS, grantable via adb) → showing the adb commands.
     */
    private void enableKeyCatcher() {
        String component = KeyCatcherToggle.component(this);
        if (KeyCatcherToggle.isEnabledInSettings(this)) {
            if (KeyCatcherService.isRunning()) {
                Toast.makeText(this, "KeyCatcher is enabled and running",
                        Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, "Enabled but not bound — restarting…",
                        Toast.LENGTH_SHORT).show();
                KeyCatcherToggle.kick(this, () -> status.postDelayed(() ->
                        Toast.makeText(this, KeyCatcherService.isRunning()
                                        ? "KeyCatcher is running again"
                                        : "Still not bound — try a reboot or re-run the adb grant",
                                Toast.LENGTH_LONG).show(), 2000));
            }
            return;
        }
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);

        Intent intent = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        if (intent.resolveActivity(getPackageManager()) != null) {
            try {
                startActivity(intent);
                return;
            } catch (Throwable t) {
                Log.w("MainActivity", "Accessibility settings screen failed", t);
            }
        }

        try {
            String newValue = (enabled == null || enabled.isEmpty())
                    ? component : enabled + ":" + component;
            Settings.Secure.putString(getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, newValue);
            Settings.Secure.putString(getContentResolver(),
                    Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            Toast.makeText(this, "KeyCatcher enabled", Toast.LENGTH_SHORT).show();
        } catch (SecurityException e) {
            status.setText("No Accessibility settings UI on this unit. Either grant "
                    + "the app permission to enable itself:\n\n"
                    + "adb shell pm grant " + getPackageName()
                    + " android.permission.WRITE_SECURE_SETTINGS\n\n"
                    + "then tap this button again — or enable the service directly:\n\n"
                    + "adb shell settings put secure enabled_accessibility_services "
                    + component + "\n"
                    + "adb shell settings put secure accessibility_enabled 1");
        }
    }
}
