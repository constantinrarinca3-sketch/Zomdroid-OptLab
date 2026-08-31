package com.zomdroid;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/** Restart-scoped ownership switches for native game modules. */
public final class NativeModulesPreferences {
    public static final int SCHEMA = 4;

    private static final String PREFS_NAME = "zomdroid_native_modules_v1";
    private static final String K_LIGHTING64 = "lighting64_native";
    private static final String K_PZCLIPPER = "pzclipper_native";
    private static final String K_PATHFINDING = "pathfinding_native";
    private static final String K_POPMAN = "popman_native";

    private final SharedPreferences prefs;
    private final OptLabPreferences optLab;

    private NativeModulesPreferences(SharedPreferences prefs, OptLabPreferences optLab) {
        this.prefs = prefs;
        this.optLab = optLab;
    }

    @NonNull
    public static NativeModulesPreferences from(@NonNull Context context) {
        Context app = context.getApplicationContext();
        return new NativeModulesPreferences(
                app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE),
                OptLabPreferences.from(app));
    }

    /** Legacy implementation is non-parity; it remains an explicit experiment. */
    public boolean isLighting64Enabled() {
        return enabled(K_LIGHTING64, false);
    }

    /** Existing audited CP4 behavior remains the default baseline. */
    public boolean isPzClipperEnabled() {
        return enabled(K_PZCLIPPER, true);
    }

    /** Pathfinding stays opt-in until device A/B validation is complete. */
    public boolean isPathfindingEnabled() {
        return enabled(K_PATHFINDING, false);
    }

    public boolean isPopManEnabled() {
        return enabled(K_POPMAN, false);
    }

    public void setLighting64Enabled(boolean enabled) {
        writeBoolean(K_LIGHTING64, enabled);
    }

    public void setPzClipperEnabled(boolean enabled) {
        writeBoolean(K_PZCLIPPER, enabled);
    }

    public void setPathfindingEnabled(boolean enabled) {
        writeBoolean(K_PATHFINDING, enabled);
    }

    /** PopMan remains opt-in because it changes persistent zombie-population state. */
    public void setPopManEnabled(boolean enabled) {
        writeBoolean(K_POPMAN, enabled);
    }

    public String machineReadable() {
        return "nativeModulesSchema=" + SCHEMA
                + " safeMode=" + bit(optLab.isSafeModeEnabled())
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

    private void writeBoolean(String key, boolean value) {
        commit(prefs.edit().putBoolean(key, value));
        if (prefs.getBoolean(key, !value) != value) {
            throw new IllegalStateException("Native Modules setting read-back mismatch for " + key);
        }
    }

    private boolean enabled(String key, boolean fallback) {
        return !optLab.isSafeModeEnabled() && prefs.getBoolean(key, fallback);
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }
}
