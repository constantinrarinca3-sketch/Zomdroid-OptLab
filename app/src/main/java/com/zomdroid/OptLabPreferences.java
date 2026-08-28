package com.zomdroid;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

/**
 * Independent, restart-scoped settings for the single-APK optimization lab.
 *
 * Keeping these values outside LauncherPreferences' Gson blob makes rollback
 * deterministic and avoids coupling experimental schema changes to normal
 * launcher settings.
 */
public final class OptLabPreferences {
    public static final int SCHEMA = 4;
    public static final String EXPECTED_PZ_JAR_SHA256 =
            "e4661ca9cb168abc995d3cf59994fa17f66ba8a4e2c2899cbfa48f7eacea54b8";
    public static final String EXPECTED_MAIN_THREAD_SHA256 =
            "c7ee1d1d3026185ad49cd80edbf9ddb6f59c0cd7faf2ced4ebbe50f2dada9c0f";
    public static final String EXPECTED_GAME_WINDOW_SHA256 =
            "34c9927f595ecd524e5ed5524ede1b4789c9be462ea1a04a0f5d1b57dd1d5c95";

    private static final String PREFS_NAME = "zomdroid_opt_lab_v1";

    private static final String K_MASTER = "master";
    private static final String K_PROFILE = "profile";
    private static final String K_QUIET = "quiet_runtime";
    private static final String K_STDIO = "stdio_mode";
    private static final String K_PACING = "mainloop_pacing";
    private static final String K_SQLITE = "sqlite_android_native";
    private static final String K_BOX64 = "box64_policy";
    private static final String K_SURFACE = "surface_mode";
    private static final String K_DISPLAY = "display_fps_hint";
    private static final String K_INPUT = "input_queue_mode";
    private static final String K_ANALOG = "input_analog_filter";
    private static final String K_COALESCE = "input_coalesce";
    private static final String K_MGL_LOG = "mobilegl_file_log";
    private static final String K_STREAM_WAKE = "stream_wake";
    private static final String K_STREAM_QUEUE_FAST = "stream_queue_fast";
    private static final String K_STREAM_VELOCITY_ETA = "stream_velocity_eta";
    private static final String K_FBO_DIRTY_DEDUP = "fbo_dirty_dedup";
    private static final String K_FBO_FRAME_BUDGET = "fbo_frame_budget";
    private static final String K_STREAM_FBO_COORDINATOR = "stream_fbo_coordinator";
    private static final String K_ONLY_BUILD42 = "only_build_42";

    public enum Profile {
        BASELINE("ALL OFF / legacy"),
        RUNTIME_SAFE("Runtime safe"),
        STREAM_ALL("STREAM ALL"),
        FBO_ALL("FBO ALL"),
        FULL_CANDIDATE("FULL CANDIDATE"),
        ALL_TEST_ON("All test toggles ON"),
        CUSTOM("Custom");

        private final String label;
        Profile(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public enum StdioMode {
        LEGACY("Legacy logger"),
        BUFFERED("Buffered / bounded");

        private final String label;
        StdioMode(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public enum Box64Policy {
        LEGACY_3_0("Legacy STRONGMEM=3, BIGBLOCK=0"),
        LEVEL1_0("Test STRONGMEM=1, BIGBLOCK=0"),
        DEFAULT("Box64 defaults");

        private final String label;
        Box64Policy(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public enum SurfaceMode {
        LEGACY,
        GEN_ACK
    }

    public enum DisplayFpsHint {
        OFF,
        NATIVE_REFRESH
    }

    public enum InputQueueMode {
        LEGACY,
        MUTEX_SAFE
    }

    private final SharedPreferences prefs;

    private OptLabPreferences(SharedPreferences prefs) {
        this.prefs = prefs;
    }

    @NonNull
    public static OptLabPreferences from(@NonNull Context context) {
        return new OptLabPreferences(context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    public boolean isMasterEnabled() { return prefs.getBoolean(K_MASTER, false); }
    public Profile getProfile() {
        return isMasterEnabled() ? enumValue(K_PROFILE, Profile.class, Profile.CUSTOM)
                : Profile.BASELINE;
    }
    public boolean isQuietRuntime() { return isMasterEnabled() && prefs.getBoolean(K_QUIET, false); }
    public StdioMode getStdioMode() {
        return isMasterEnabled() ? enumValue(K_STDIO, StdioMode.class, StdioMode.LEGACY)
                : StdioMode.LEGACY;
    }
    public boolean isMainloopPacing() { return isMasterEnabled() && prefs.getBoolean(K_PACING, false); }
    public boolean isSqliteAndroidNative() { return isMasterEnabled() && prefs.getBoolean(K_SQLITE, false); }
    public Box64Policy getBox64Policy() {
        return isMasterEnabled() ? enumValue(K_BOX64, Box64Policy.class, Box64Policy.LEGACY_3_0)
                : Box64Policy.LEGACY_3_0;
    }
    public SurfaceMode getSurfaceMode() {
        return isMasterEnabled() ? enumValue(K_SURFACE, SurfaceMode.class, SurfaceMode.LEGACY)
                : SurfaceMode.LEGACY;
    }
    public DisplayFpsHint getDisplayFpsHint() {
        return isMasterEnabled() ? enumValue(K_DISPLAY, DisplayFpsHint.class, DisplayFpsHint.OFF)
                : DisplayFpsHint.OFF;
    }
    public InputQueueMode getInputQueueMode() {
        return isMasterEnabled() ? enumValue(K_INPUT, InputQueueMode.class, InputQueueMode.LEGACY)
                : InputQueueMode.LEGACY;
    }
    public boolean isAnalogFilter() { return isMasterEnabled() && prefs.getBoolean(K_ANALOG, false); }
    public boolean isInputCoalesce() { return isMasterEnabled() && prefs.getBoolean(K_COALESCE, false); }
    public boolean isMobileGlFileLogEnabled() {
        return isMasterEnabled() && prefs.getBoolean(K_MGL_LOG, false);
    }
    public boolean isStreamWake() {
        return isMasterEnabled() && prefs.getBoolean(K_STREAM_WAKE, false);
    }
    public boolean isStreamQueueFast() {
        return isMasterEnabled() && prefs.getBoolean(K_STREAM_QUEUE_FAST, false);
    }
    public boolean isStreamVelocityEta() {
        return isMasterEnabled() && prefs.getBoolean(K_STREAM_VELOCITY_ETA, false);
    }
    public boolean isFboDirtyDedup() {
        return isMasterEnabled() && prefs.getBoolean(K_FBO_DIRTY_DEDUP, false);
    }
    public boolean isFboFrameBudget() {
        return isMasterEnabled() && prefs.getBoolean(K_FBO_FRAME_BUDGET, false);
    }
    public boolean isStreamFboCoordinator() {
        return isMasterEnabled() && prefs.getBoolean(K_STREAM_FBO_COORDINATOR, false);
    }
    public boolean isOnlyBuild42() {
        return prefs.getBoolean(K_ONLY_BUILD42, true);
    }
    public boolean isAnyAgentOptimizationEnabled() {
        return isMainloopPacing() || isStreamWake() || isStreamQueueFast()
                || isStreamVelocityEta() || isFboDirtyDedup() || isFboFrameBudget()
                || isStreamFboCoordinator();
    }

    public void setProfile(Profile profile) {
        SharedPreferences.Editor editor = prefs.edit().putString(K_PROFILE, profile.name());
        switch (profile) {
            case BASELINE:
                // A deterministic clean slate for one-at-a-time A/B tests.  The master
                // switch by itself remains a non-destructive kill switch, but choosing
                // the BASELINE profile deliberately clears every stored experiment.
                editor.putBoolean(K_MASTER, false)
                        .putBoolean(K_QUIET, false)
                        .putString(K_STDIO, StdioMode.LEGACY.name())
                        .putBoolean(K_PACING, false)
                        .putBoolean(K_SQLITE, false)
                        .putString(K_BOX64, Box64Policy.LEGACY_3_0.name())
                        .putString(K_SURFACE, SurfaceMode.LEGACY.name())
                        .putString(K_DISPLAY, DisplayFpsHint.OFF.name())
                        .putString(K_INPUT, InputQueueMode.LEGACY.name())
                        .putBoolean(K_ANALOG, false)
                        .putBoolean(K_COALESCE, false)
                        .putBoolean(K_MGL_LOG, false)
                        .putBoolean(K_STREAM_WAKE, false)
                        .putBoolean(K_STREAM_QUEUE_FAST, false)
                        .putBoolean(K_STREAM_VELOCITY_ETA, false)
                        .putBoolean(K_FBO_DIRTY_DEDUP, false)
                        .putBoolean(K_FBO_FRAME_BUDGET, false)
                        .putBoolean(K_STREAM_FBO_COORDINATOR, false);
                break;
            case RUNTIME_SAFE:
                editor.putBoolean(K_MASTER, true)
                        .putBoolean(K_QUIET, true)
                        .putString(K_STDIO, StdioMode.BUFFERED.name())
                        .putBoolean(K_PACING, false)
                        .putBoolean(K_SQLITE, false)
                        .putString(K_BOX64, Box64Policy.LEGACY_3_0.name())
                        .putString(K_SURFACE, SurfaceMode.GEN_ACK.name())
                        .putString(K_DISPLAY, DisplayFpsHint.NATIVE_REFRESH.name())
                        .putString(K_INPUT, InputQueueMode.MUTEX_SAFE.name())
                        .putBoolean(K_ANALOG, true)
                        .putBoolean(K_COALESCE, true)
                        .putBoolean(K_MGL_LOG, false)
                        .putBoolean(K_STREAM_WAKE, false)
                        .putBoolean(K_STREAM_QUEUE_FAST, false)
                        .putBoolean(K_STREAM_VELOCITY_ETA, false)
                        .putBoolean(K_FBO_DIRTY_DEDUP, false)
                        .putBoolean(K_FBO_FRAME_BUDGET, false)
                        .putBoolean(K_STREAM_FBO_COORDINATOR, false);
                break;
            case STREAM_ALL:
                setIsolatedPack(editor, true, true, true, false, false, false);
                break;
            case FBO_ALL:
                setIsolatedPack(editor, false, false, false, true, true, false);
                break;
            case FULL_CANDIDATE:
                editor.putBoolean(K_MASTER, true)
                        .putBoolean(K_QUIET, true)
                        .putString(K_STDIO, StdioMode.BUFFERED.name())
                        .putBoolean(K_PACING, false)
                        .putBoolean(K_SQLITE, false)
                        .putString(K_BOX64, Box64Policy.LEGACY_3_0.name())
                        .putString(K_SURFACE, SurfaceMode.GEN_ACK.name())
                        .putString(K_DISPLAY, DisplayFpsHint.NATIVE_REFRESH.name())
                        .putString(K_INPUT, InputQueueMode.MUTEX_SAFE.name())
                        .putBoolean(K_ANALOG, true)
                        .putBoolean(K_COALESCE, true)
                        .putBoolean(K_MGL_LOG, false)
                        .putBoolean(K_STREAM_WAKE, true)
                        .putBoolean(K_STREAM_QUEUE_FAST, true)
                        .putBoolean(K_STREAM_VELOCITY_ETA, true)
                        .putBoolean(K_FBO_DIRTY_DEDUP, true)
                        .putBoolean(K_FBO_FRAME_BUDGET, true)
                        .putBoolean(K_STREAM_FBO_COORDINATOR, true);
                break;
            case ALL_TEST_ON:
                editor.putBoolean(K_MASTER, true)
                        .putBoolean(K_QUIET, true)
                        .putString(K_STDIO, StdioMode.BUFFERED.name())
                        .putBoolean(K_PACING, true)
                        .putBoolean(K_SQLITE, false)
                        .putString(K_BOX64, Box64Policy.LEVEL1_0.name())
                        .putString(K_SURFACE, SurfaceMode.GEN_ACK.name())
                        .putString(K_DISPLAY, DisplayFpsHint.NATIVE_REFRESH.name())
                        .putString(K_INPUT, InputQueueMode.MUTEX_SAFE.name())
                        .putBoolean(K_ANALOG, true)
                        .putBoolean(K_COALESCE, true)
                        .putBoolean(K_MGL_LOG, false)
                        .putBoolean(K_STREAM_WAKE, true)
                        .putBoolean(K_STREAM_QUEUE_FAST, true)
                        .putBoolean(K_STREAM_VELOCITY_ETA, true)
                        .putBoolean(K_FBO_DIRTY_DEDUP, true)
                        .putBoolean(K_FBO_FRAME_BUDGET, true)
                        .putBoolean(K_STREAM_FBO_COORDINATOR, true);
                break;
            case CUSTOM:
                editor.putBoolean(K_MASTER, true);
                break;
        }
        commit(editor);
    }

    public void setMasterEnabled(boolean value) {
        if (!value) {
            // OFF is an atomic, durable rollback. R1 only hid the stored ON values and used
            // asynchronous apply(), so an immediate force-stop could resurrect ALL_TEST_ON.
            setProfile(Profile.BASELINE);
            return;
        }
        commit(prefs.edit().putBoolean(K_MASTER, true)
                .putString(K_PROFILE, Profile.CUSTOM.name()));
    }

    public void setQuietRuntime(boolean value) { editCustom(K_QUIET, value); }
    public void setStdioMode(StdioMode value) { editCustom(K_STDIO, value.name()); }
    public void setMainloopPacing(boolean value) { editCustom(K_PACING, value); }
    public void setSqliteAndroidNative(boolean value) { editCustom(K_SQLITE, value); }
    public void setBox64Policy(Box64Policy value) { editCustom(K_BOX64, value.name()); }
    public void setSurfaceMode(SurfaceMode value) { editCustom(K_SURFACE, value.name()); }
    public void setDisplayFpsHint(DisplayFpsHint value) { editCustom(K_DISPLAY, value.name()); }
    public void setInputQueueMode(InputQueueMode value) { editCustom(K_INPUT, value.name()); }
    public void setAnalogFilter(boolean value) { editCustom(K_ANALOG, value); }
    public void setInputCoalesce(boolean value) { editCustom(K_COALESCE, value); }
    public void setMobileGlFileLogEnabled(boolean value) { editCustom(K_MGL_LOG, value); }
    public void setStreamWake(boolean value) { editCustom(K_STREAM_WAKE, value); }
    public void setStreamQueueFast(boolean value) { editCustom(K_STREAM_QUEUE_FAST, value); }
    public void setStreamVelocityEta(boolean value) { editCustom(K_STREAM_VELOCITY_ETA, value); }
    public void setFboDirtyDedup(boolean value) { editCustom(K_FBO_DIRTY_DEDUP, value); }
    public void setFboFrameBudget(boolean value) { editCustom(K_FBO_FRAME_BUDGET, value); }
    public void setStreamFboCoordinator(boolean value) {
        editCustom(K_STREAM_FBO_COORDINATOR, value);
    }
    public void setOnlyBuild42(boolean value) {
        commit(prefs.edit().putBoolean(K_ONLY_BUILD42, value));
    }

    public String build42Summary() {
        return "Only Build 42=" + (isOnlyBuild42() ? "ON" : "OFF")
                + " · pacing=" + bool(isMainloopPacing())
                + " stream=" + bool(isStreamWake() || isStreamQueueFast()
                        || isStreamVelocityEta())
                + " fbo=" + bool(isFboDirtyDedup() || isFboFrameBudget())
                + " coordinator=" + bool(isStreamFboCoordinator());
    }

    public String summary() {
        if (!isMasterEnabled()) return "ALL OFF / legacy";
        return getProfile() + " · quiet=" + bool(isQuietRuntime())
                + " pacing=" + bool(isMainloopPacing())
                + " box64=" + getBox64Policy().name()
                + " surface=" + getSurfaceMode().name()
                + " input=" + getInputQueueMode().name()
                + " stream=" + bool(isStreamWake() || isStreamQueueFast()
                        || isStreamVelocityEta())
                + " fbo=" + bool(isFboDirtyDedup() || isFboFrameBudget());
    }

    public String machineReadable() {
        return "schema=" + SCHEMA
                + " profile=" + getProfile().name()
                + " master=" + bool(isMasterEnabled())
                + " quiet=" + bool(isQuietRuntime())
                + " stdio=" + getStdioMode().name()
                + " pacing=" + bool(isMainloopPacing())
                + " sqlite=" + bool(isSqliteAndroidNative())
                + " box64=" + getBox64Policy().name()
                + " surface=" + getSurfaceMode().name()
                + " refresh=" + getDisplayFpsHint().name()
                + " input=" + getInputQueueMode().name()
                + " analog=" + bool(isAnalogFilter())
                + " coalesce=" + bool(isInputCoalesce())
                + " mglFileLog=" + bool(isMobileGlFileLogEnabled())
                + " streamWake=" + bool(isStreamWake())
                + " streamQueueFast=" + bool(isStreamQueueFast())
                + " streamVelocityEta=" + bool(isStreamVelocityEta())
                + " fboDirtyDedup=" + bool(isFboDirtyDedup())
                + " fboFrameBudget=" + bool(isFboFrameBudget())
                + " streamFboCoordinator=" + bool(isStreamFboCoordinator())
                + " onlyBuild42=" + bool(isOnlyBuild42());
    }

    private static void setIsolatedPack(SharedPreferences.Editor editor,
                                        boolean streamWake,
                                        boolean streamQueueFast,
                                        boolean streamVelocityEta,
                                        boolean fboDirtyDedup,
                                        boolean fboFrameBudget,
                                        boolean coordinator) {
        editor.putBoolean(K_MASTER, true)
                .putBoolean(K_QUIET, false)
                .putString(K_STDIO, StdioMode.LEGACY.name())
                .putBoolean(K_PACING, false)
                .putBoolean(K_SQLITE, false)
                .putString(K_BOX64, Box64Policy.LEGACY_3_0.name())
                .putString(K_SURFACE, SurfaceMode.LEGACY.name())
                .putString(K_DISPLAY, DisplayFpsHint.OFF.name())
                .putString(K_INPUT, InputQueueMode.LEGACY.name())
                .putBoolean(K_ANALOG, false)
                .putBoolean(K_COALESCE, false)
                .putBoolean(K_MGL_LOG, false)
                .putBoolean(K_STREAM_WAKE, streamWake)
                .putBoolean(K_STREAM_QUEUE_FAST, streamQueueFast)
                .putBoolean(K_STREAM_VELOCITY_ETA, streamVelocityEta)
                .putBoolean(K_FBO_DIRTY_DEDUP, fboDirtyDedup)
                .putBoolean(K_FBO_FRAME_BUDGET, fboFrameBudget)
                .putBoolean(K_STREAM_FBO_COORDINATOR, coordinator);
    }

    private void editCustom(String key, boolean value) {
        commit(prefs.edit().putBoolean(key, value)
                .putString(K_PROFILE, Profile.CUSTOM.name()));
    }

    private void editCustom(String key, String value) {
        commit(prefs.edit().putString(key, value)
                .putString(K_PROFILE, Profile.CUSTOM.name()));
    }

    private static void commit(SharedPreferences.Editor editor) {
        if (!editor.commit()) {
            throw new IllegalStateException("Could not persist ZomDroid OPT-LAB settings");
        }
    }

    private <T extends Enum<T>> T enumValue(String key, Class<T> type, T fallback) {
        String value = prefs.getString(key, fallback.name());
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return fallback;
        }
    }

    private static int bool(boolean value) { return value ? 1 : 0; }
}
