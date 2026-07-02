package com.ptuchik.siri;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * Enables / re-enables the KeyCatcher accessibility service by rewriting the
 * secure setting (requires WRITE_SECURE_SETTINGS, granted once via adb).
 *
 * Some head-unit builds restore enabled_accessibility_services at boot
 * without actually binding the service — it looks enabled but onKeyEvent
 * never fires. Removing and re-adding the component forces
 * AccessibilityManagerService to rebind it.
 */
final class KeyCatcherToggle {

    private static final long REENABLE_DELAY_MS = 1500;

    private KeyCatcherToggle() { }

    static String component(Context ctx) {
        return new ComponentName(ctx, KeyCatcherService.class).flattenToString();
    }

    static boolean canWriteSecureSettings(Context ctx) {
        return ctx.checkSelfPermission(
                android.Manifest.permission.WRITE_SECURE_SETTINGS)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean isEnabledInSettings(Context ctx) {
        String enabled = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled != null && enabled.contains(component(ctx));
    }

    /** Removes and re-adds the service in secure settings, forcing a rebind. */
    static void kick(Context context, Runnable whenDone) {
        final Context ctx = context.getApplicationContext();
        if (!canWriteSecureSettings(ctx)) {
            if (whenDone != null) whenDone.run();
            return;
        }
        String comp = component(ctx);
        String current = Settings.Secure.getString(ctx.getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        StringBuilder without = new StringBuilder();
        if (current != null) {
            for (String s : current.split(":")) {
                if (s.isEmpty() || s.equals(comp)) continue;
                if (without.length() > 0) without.append(':');
                without.append(s);
            }
        }
        try {
            Settings.Secure.putString(ctx.getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    without.toString());
        } catch (Throwable t) {
            if (whenDone != null) whenDone.run();
            return;
        }
        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            try {
                String with = without.length() == 0
                        ? comp : without + ":" + comp;
                Settings.Secure.putString(ctx.getContentResolver(),
                        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, with);
                Settings.Secure.putString(ctx.getContentResolver(),
                        Settings.Secure.ACCESSIBILITY_ENABLED, "1");
            } catch (Throwable ignored) { }
            if (whenDone != null) whenDone.run();
        }, REENABLE_DELAY_MS);
    }
}
