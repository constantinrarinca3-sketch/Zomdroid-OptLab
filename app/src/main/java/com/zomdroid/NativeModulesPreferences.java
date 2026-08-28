package com.zomdroid;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/** Restart-scoped ownership switches for native game modules. */
public final class NativeModulesPreferences {
    public static final int SCHEMA = 2;

    private static final String PREFS_NAME = "zomdroid_native_modules_v1";
    private static final String K_LIGHTING64 = "lighting64_native";
    private static final String K_PZCLIPPER = "pzclipper_native";
    private static final String K_PATHFINDING = "pathfinding_native";
    private static final String K_POPMAN = "popman_native";

    private final SharedPreferences prefs;

    private NativeModulesPreferences(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    @NonNull
    public static NativeModulesPreferences from(@NonNull Context context) {
        return new NativeModulesPreferences(context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    /** Existing audited CP4 behavior remains the default baseline. */
    public boolean isLighting64Enabled() {
        return prefs.getBoolean(K_LIGHTING64, true);
    }

    /** Existing audited CP4 behavior remains the default baseline. */
    public boolean isPzClipperEnabled() {
        return prefs.getBoolean(K_PZCLIPPER, true);
    }

    /** Pathfinding stays opt-in until device A/B validation is complete. */
    public boolean isPathfindingEnabled() {
        return prefs.getBoolean(K_PATHFINDING, false);
    }

    public boolean isPopManEnabled() {
        return prefs.getBoolean(K_POPMAN, false);
    }

    public void setLighting64Enabled(boolean enabled) {
        commit(prefs.edit().putBoolean(K_LIGHTING64, enabled));
    }

    public void setPzClipperEnabled(boolean enabled) {
        commit(prefs.edit().putBoolean(K_PZCLIPPER, enabled));
    }

    public void setPathfindingEnabled(boolean enabled) {
        commit(prefs.edit().putBoolean(K_PATHFINDING, enabled));
    }

    /** PopMan remains opt-in because it changes persistent zombie-population state. */
    public void setPopManEnabled(boolean enabled) {
        commit(prefs.edit().putBoolean(K_POPMAN, enabled));
    }

    public String machineReadable() {
        return "nativeModulesSchema=" + SCHEMA
                + " lighting64=" + bit(isLighting64Enabled())
                + " pzClipper=" + bit(isPzClipperEnabled())
                + " pathfinding=" + bit(isPathfindingEnabled())
                + " popMan=" + bit(isPopManEnabled());
    }

    public String summary() {
        return "Lighting=" + onOff(isLighting64Enabled())
                + " · PZClipper=" + onOff(isPzClipperEnabled())
                + " · Pathfinding=" + onOff(isPathfindingEnabled())
                + " · PopMan=" + onOff(isPopManEnabled());
    }

    private static void commit(SharedPreferences.Editor editor) {
        if (!editor.commit()) {
            throw new IllegalStateException("Could not persist Native Modules settings");
        }
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }
}
