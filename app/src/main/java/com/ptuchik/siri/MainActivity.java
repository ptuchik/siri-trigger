package com.ptuchik.siri;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.provider.Settings;
import android.view.KeyEvent;
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
        CheckBox discovery = findViewById(R.id.chkDiscovery);
        EditText keycode = findViewById(R.id.editKeycode);
        Button save = findViewById(R.id.btnSave);

        SharedPreferences p = getSharedPreferences(KeyCatcherService.PREFS, MODE_PRIVATE);
        discovery.setChecked(p.getBoolean(KeyCatcherService.PREF_DISCOVERY, true));
        keycode.setText(String.valueOf(
                p.getInt(KeyCatcherService.PREF_KEYCODE, KeyEvent.KEYCODE_VOICE_ASSIST)));

        // Bind HFP proxy immediately so status is meaningful.
        HfpSiriTrigger trigger = HfpSiriTrigger.get(this);
        status.postDelayed(() -> status.setText("HFP status: " + trigger.getStatus()), 1500);

        test.setOnClickListener(v -> {
            String result = trigger.triggerSiri();
            status.setText(result);
            Toast.makeText(this, result, Toast.LENGTH_LONG).show();
        });

        openA11y.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));

        save.setOnClickListener(v -> {
            int code;
            try {
                code = Integer.parseInt(keycode.getText().toString().trim());
            } catch (NumberFormatException e) {
                Toast.makeText(this, "Invalid keycode", Toast.LENGTH_SHORT).show();
                return;
            }
            p.edit()
                    .putBoolean(KeyCatcherService.PREF_DISCOVERY, discovery.isChecked())
                    .putInt(KeyCatcherService.PREF_KEYCODE, code)
                    .apply();
            Toast.makeText(this, "Saved. Discovery="
                    + discovery.isChecked() + ", keycode=" + code, Toast.LENGTH_SHORT).show();
        });
    }
}
