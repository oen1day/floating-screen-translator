package io.github.oen1day.floatingtranslator;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.provider.Settings;
import android.util.Log;

/**
 * Device-only accessibility self-healing.
 *
 * <p>WRITE_SECURE_SETTINGS must be granted once with ADB. The healer only touches
 * this app's own accessibility component and never removes or rewrites unrelated
 * accessibility services.</p>
 */
public final class AccessibilityHealer {
    public enum Result {
        NOT_GRANTED,
        NOT_OPTED_IN,
        DISABLED_BY_USER,
        ALREADY_OK,
        REPAIRED,
        RELOADING,
        FAILED
    }

    private static final String TAG = "AccessibilityAutoRecovery";
    private static final String PREFS = "accessibility_auto_recovery";
    private static final String KEY_EVER_ENABLED = "ever_enabled";
    private static final String KEY_AUTO_HEAL = "auto_heal";
    private static final String SECURE_ENABLED = "enabled_accessibility_services";
    private static final String SECURE_ACCESSIBILITY = "accessibility_enabled";

    private static volatile long lastCheckAt;
    private static volatile long lastReloadAt;
    private static volatile boolean reloadInFlight;

    private AccessibilityHealer() {
    }

    public static boolean hasPermission(Context context) {
        return context.checkSelfPermission("android.permission.WRITE_SECURE_SETTINGS")
                == PackageManager.PERMISSION_GRANTED;
    }

    public static boolean hasEverBeenEnabled(Context context) {
        return prefs(context).getBoolean(KEY_EVER_ENABLED, false);
    }

    public static boolean isAutoHealEnabled(Context context) {
        return prefs(context).getBoolean(KEY_AUTO_HEAL, true);
    }

    public static void setAutoHealEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_AUTO_HEAL, enabled).apply();
    }

    public static void markEverEnabled(Context context) {
        prefs(context).edit().putBoolean(KEY_EVER_ENABLED, true).apply();
    }

    public static Result heal(Context context, boolean force) {
        Context app = context.getApplicationContext();
        if (!hasPermission(app)) {
            return Result.NOT_GRANTED;
        }
        if (!isAutoHealEnabled(app)) {
            return Result.DISABLED_BY_USER;
        }
        if (!hasEverBeenEnabled(app)) {
            return Result.NOT_OPTED_IN;
        }

        long now = System.currentTimeMillis();
        if (!force && now - lastCheckAt < 1500L) {
            return Result.ALREADY_OK;
        }
        lastCheckAt = now;

        try {
            String our = componentId(app);
            String current = Settings.Secure.getString(
                    app.getContentResolver(),
                    SECURE_ENABLED
            );
            boolean listed = containsComponent(current, our);
            boolean connected = ScreenCaptureAccessibilityService.isConnected();
            if (listed && connected && !force) {
                return Result.ALREADY_OK;
            }

            if (!listed) {
                String repaired = appendComponent(current, our);
                Settings.Secure.putString(app.getContentResolver(), SECURE_ENABLED, repaired);
                Settings.Secure.putString(app.getContentResolver(), SECURE_ACCESSIBILITY, "1");
                Log.i(TAG, "Restored own accessibility service");
                return Result.REPAIRED;
            }

            if (!force && now - lastReloadAt < 6000L) {
                return Result.RELOADING;
            }
            lastReloadAt = now;
            reloadOwnService(app, current, our);
            return Result.RELOADING;
        } catch (Exception error) {
            Log.w(TAG, "Accessibility self-heal failed", error);
            return Result.FAILED;
        }
    }

    private static void reloadOwnService(Context app, String current, String our) {
        if (reloadInFlight) {
            return;
        }
        reloadInFlight = true;
        new Thread(() -> {
            try {
                String without = removeComponent(current, our);
                Settings.Secure.putString(app.getContentResolver(), SECURE_ENABLED, without);
                Thread.sleep(250L);
                String restored = appendComponent(without, our);
                Settings.Secure.putString(app.getContentResolver(), SECURE_ENABLED, restored);
                Settings.Secure.putString(app.getContentResolver(), SECURE_ACCESSIBILITY, "1");
                Log.i(TAG, "Reloaded own accessibility service");
            } catch (Exception error) {
                Log.w(TAG, "Accessibility service reload failed", error);
            } finally {
                reloadInFlight = false;
            }
        }, "accessibility-auto-recovery").start();
    }

    private static String componentId(Context context) {
        return context.getPackageName()
                + "/"
                + ScreenCaptureAccessibilityService.class.getName();
    }

    private static boolean containsComponent(String list, String component) {
        if (list == null || list.trim().isEmpty()) {
            return false;
        }
        for (String item : list.split(":")) {
            if (sameComponent(item.trim(), component)) {
                return true;
            }
        }
        return false;
    }

    private static String removeComponent(String list, String component) {
        StringBuilder builder = new StringBuilder();
        if (list != null) {
            for (String item : list.split(":")) {
                String trimmed = item.trim();
                if (trimmed.isEmpty() || sameComponent(trimmed, component)) {
                    continue;
                }
                if (builder.length() > 0) {
                    builder.append(':');
                }
                builder.append(trimmed);
            }
        }
        return builder.toString();
    }

    private static String appendComponent(String list, String component) {
        String without = removeComponent(list, component);
        return without.isEmpty() ? component : without + ":" + component;
    }

    private static boolean sameComponent(String actual, String expected) {
        if (actual == null || expected == null) {
            return false;
        }
        if (actual.equals(expected)) {
            return true;
        }
        ComponentName actualComponent = ComponentName.unflattenFromString(actual);
        ComponentName expectedComponent = ComponentName.unflattenFromString(expected);
        return actualComponent != null && actualComponent.equals(expectedComponent);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
