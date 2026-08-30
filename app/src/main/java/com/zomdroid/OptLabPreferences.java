package com.zomdroid;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.annotation.NonNull;

import java.util.EnumSet;
import java.util.List;

/**
 * Independent, restart-scoped settings for the single-APK optimization lab.
 *
 * Keeping these values outside LauncherPreferences' Gson blob makes rollback
 * deterministic and avoids coupling experimental schema changes to normal
 * launcher settings.
 */
public final class OptLabPreferences {
    public static final int SCHEMA = 12;
    public static final String EXPECTED_PZ_JAR_SHA256 =
            "e4661ca9cb168abc995d3cf59994fa17f66ba8a4e2c2899cbfa48f7eacea54b8";
    public static final String EXPECTED_PZ_42203_JAR_SHA256 =
            "bda809fb49004a07dbfc560d059c0ee58d0643ab0f33b53351b13bd62f1d8227";
    public static final String EXPECTED_MAIN_THREAD_SHA256 =
            "c7ee1d1d3026185ad49cd80edbf9ddb6f59c0cd7faf2ced4ebbe50f2dada9c0f";
    public static final String EXPECTED_GAME_WINDOW_SHA256 =
            "34c9927f595ecd524e5ed5524ede1b4789c9be462ea1a04a0f5d1b57dd1d5c95";

    private static final String PREFS_NAME = "zomdroid_opt_lab_v1";

    private static final String K_SAFE_MODE = "safe_mode";
    private static final String K_MASTER = "master";
    private static final String K_PROFILE = "profile";
    // Read-only migration input from the unified R14 profile.
    private static final String K_LAB_PROFILE = "lab_profile_v2";
    private static final String K_GENERAL_PROFILE = "general_profile_v3";
    static final String K_BUILD42_PROFILE = "build42_profile_v3";
    private static final String K_ADVANCED_CONTROLS = "advanced_controls_v1";
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
    private static final String K_CHUNK_FORAGE = "chunk_forage";
    private static final String K_CHUNK_NEIGHBOUR_WORKER = "chunk_neighbour_worker";
    private static final String K_CHUNK_NEIGHBOUR_MAIN = "chunk_neighbour_main";
    private static final String K_CHUNK_GRID_LOAD = "chunk_grid_load";
    private static final String K_CHUNK_VEHICLES = "chunk_vehicles";
    private static final String K_CHUNK_BUILDINGS = "chunk_randomized_buildings";
    private static final String K_CHUNK_LUA = "chunk_lua_mapobjects";
    private static final String K_CHUNK_WORLDGEN = "chunk_worldgen_biome";
    private static final String K_CHUNK_CP2C = "chunk_cp2c_dirty_clear";
    private static final String K_RENDER_CHUNK_DEPTH_UPLOAD = "render_chunk_depth_upload";
    private static final String K_RENDER_CHUNK_DEPTH_LOOKUP = "render_chunk_depth_lookup";
    private static final String K_RENDER_RING_EMPTY_CLEAR = "render_ring_empty_clear";
    private static final String K_ONLY_BUILD42 = "only_build_42";

    public enum Profile {
        BASELINE("ALL OFF / legacy"),
        RUNTIME_SAFE("Runtime safe"),
        STREAM_ALL("STREAM ALL"),
        FBO_ALL("FBO ALL"),
        FULL_CANDIDATE("FULL CANDIDATE"),
        ALL_TEST_ON("Toate opțiunile generale ON"),
        CUSTOM("Custom");

        private final String label;
        Profile(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public enum LabProfile {
        SAFE("Safe"),
        RECOMMENDED("Recommended"),
        AGGRESSIVE("Aggressive"),
        CUSTOM("Custom");

        private final String label;
        LabProfile(String label) { this.label = label; }
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
    private final OptLabCustomPresetStore customPresets;

    private OptLabPreferences(SharedPreferences prefs) {
        this.prefs = prefs;
        this.customPresets = new OptLabCustomPresetStore(prefs);
    }

    @NonNull
    public static OptLabPreferences from(@NonNull Context context) {
        return new OptLabPreferences(context.getApplicationContext()
                .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE));
    }

    public static boolean isKnownPzJarSha256(String sha256) {
        return EXPECTED_PZ_JAR_SHA256.equals(sha256)
                || EXPECTED_PZ_42203_JAR_SHA256.equals(sha256);
    }

    /**
     * Global, reversible launch bypass. The configured values remain untouched while every
     * effective optimization getter returns its safe baseline.
     */
    public boolean isSafeModeEnabled() { return prefs.getBoolean(K_SAFE_MODE, false); }
    public boolean isMasterEnabled() { return enabled(K_MASTER, false); }
    public Profile getProfile() {
        return isMasterEnabled() ? enumValue(K_PROFILE, Profile.class, Profile.CUSTOM)
                : Profile.BASELINE;
    }
    /** Compatibility alias: the old unified profile is now General-only. */
    public LabProfile getLabProfile() { return getGeneralProfile(); }
    public LabProfile getGeneralProfile() { return migratedLabProfile(K_GENERAL_PROFILE); }
    public LabProfile getBuild42LabProfile() { return migratedLabProfile(K_BUILD42_PROFILE); }
    public boolean isAdvancedControls() {
        return prefs.getBoolean(K_ADVANCED_CONTROLS, false);
    }
    public boolean isQuietRuntime() { return isMasterEnabled() && prefs.getBoolean(K_QUIET, false); }
    public StdioMode getStdioMode() {
        return isMasterEnabled() ? enumValue(K_STDIO, StdioMode.class, StdioMode.LEGACY)
                : StdioMode.LEGACY;
    }
    public boolean isMainloopPacing() { return enabled(K_PACING, false); }
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
    public boolean isStreamWake() { return enabled(K_STREAM_WAKE, false); }
    public boolean isStreamQueueFast() { return enabled(K_STREAM_QUEUE_FAST, false); }
    public boolean isStreamVelocityEta() {
        return enabled(K_STREAM_VELOCITY_ETA, false);
    }
    public boolean isFboDirtyDedup() { return enabled(K_FBO_DIRTY_DEDUP, false); }
    public boolean isFboFrameBudget() { return enabled(K_FBO_FRAME_BUDGET, false); }
    public boolean isStreamFboCoordinator() {
        return enabled(K_STREAM_FBO_COORDINATOR, false);
    }
    public boolean isChunkForage() { return enabled(K_CHUNK_FORAGE, false); }
    public boolean isChunkNeighbourWorker() {
        return enabled(K_CHUNK_NEIGHBOUR_WORKER, false);
    }
    public boolean isChunkNeighbourMain() {
        return enabled(K_CHUNK_NEIGHBOUR_MAIN, false);
    }
    public boolean isChunkGridLoad() { return enabled(K_CHUNK_GRID_LOAD, false); }
    public boolean isChunkVehicles() { return enabled(K_CHUNK_VEHICLES, false); }
    public boolean isChunkRandomizedBuildings() {
        return enabled(K_CHUNK_BUILDINGS, false);
    }
    public boolean isChunkLuaMapObjects() { return enabled(K_CHUNK_LUA, false); }
    public boolean isChunkWorldgenBiome() {
        return enabled(K_CHUNK_WORLDGEN, false);
    }
    public boolean isChunkCp2cDirtyClear() { return enabled(K_CHUNK_CP2C, false); }
    public boolean isRenderChunkDepthUpload() {
        return enabled(K_RENDER_CHUNK_DEPTH_UPLOAD, false);
    }
    public boolean isRenderChunkDepthLookup() {
        return isRenderChunkDepthUpload()
                && prefs.getBoolean(K_RENDER_CHUNK_DEPTH_LOOKUP, false);
    }
    public boolean isRenderRingEmptyClear() {
        return enabled(K_RENDER_RING_EMPTY_CLEAR, false);
    }
    public boolean isOnlyBuild42() {
        return prefs.getBoolean(K_ONLY_BUILD42, true);
    }
    public boolean isAnyAgentOptimizationEnabled() {
        if (isSafeModeEnabled()) return false;
        for (OptLabFeatureRegistry.Feature feature : OptLabFeatureRegistry.Feature.values()) {
            if (feature.agentProperty != null && isFeatureEnabled(feature)) return true;
        }
        return isChunkCp2cDirtyClear();
    }
    public boolean isAnyChunkOptimizationEnabled() {
        return chunkOptimizationEnabledCount() > 0;
    }
    public boolean isAnyRenderOptimizationEnabled() {
        return renderOptimizationEnabledCount() > 0;
    }

    /** Generic Build 42 feature access used by registry, profiles, UI and launcher. */
    public boolean isFeatureEnabled(@NonNull OptLabFeatureRegistry.Feature feature) {
        if (isSafeModeEnabled() || feature.preferenceKey == null
                || (feature.category != OptLabFeatureRegistry.Category.BUILD42
                && feature.category != OptLabFeatureRegistry.Category.EXPERIMENTAL)) return false;
        if (!prefs.getBoolean(feature.preferenceKey, feature.defaultEnabled)) return false;
        for (OptLabFeatureRegistry.Feature dependency : feature.dependencies) {
            if (!isFeatureEnabled(dependency)) return false;
        }
        return true;
    }

    public void setFeatureEnabled(@NonNull OptLabFeatureRegistry.Feature feature,
                                  boolean value) {
        if (feature.preferenceKey == null
                || (feature.category != OptLabFeatureRegistry.Category.BUILD42
                && feature.category != OptLabFeatureRegistry.Category.EXPERIMENTAL)
                || feature.maturity == OptLabFeatureRegistry.Maturity.ARCHIVED) {
            throw new IllegalArgumentException("Feature is not configurable: " + feature.id);
        }
        SharedPreferences.Editor editor = prefs.edit().putBoolean(feature.preferenceKey, value);
        if (feature.category == OptLabFeatureRegistry.Category.BUILD42) {
            editor.putString(K_BUILD42_PROFILE, LabProfile.CUSTOM.name());
        }
        if (value) {
            enableWithDependencies(editor, feature,
                    EnumSet.noneOf(OptLabFeatureRegistry.Feature.class));
        } else {
            disableWithDependents(editor, feature,
                    EnumSet.noneOf(OptLabFeatureRegistry.Feature.class));
        }
        commit(editor);
    }

    /** Compatibility alias retained for R14 launcher callers. */
    public void setLabProfile(@NonNull LabProfile profile) {
        setGeneralProfile(profile);
    }

    /** Runtime/Display profile; Build 42, Native and Experimental remain untouched. */
    public void setGeneralProfile(@NonNull LabProfile profile) {
        SharedPreferences.Editor editor = prefs.edit()
                .putString(K_GENERAL_PROFILE, profile.name());
        if (profile != LabProfile.CUSTOM) {
            // No unproven aggressive Runtime/Display policy is fabricated here.
            editor.putString(K_PROFILE, Profile.RUNTIME_SAFE.name())
                    .putBoolean(K_MASTER, true)
                    .putBoolean(K_QUIET, true)
                    .putString(K_STDIO, StdioMode.BUFFERED.name())
                    .putBoolean(K_SQLITE, false)
                    .putString(K_BOX64, Box64Policy.LEGACY_3_0.name())
                    .putString(K_SURFACE, SurfaceMode.GEN_ACK.name())
                    .putString(K_DISPLAY, DisplayFpsHint.NATIVE_REFRESH.name())
                    .putString(K_INPUT, InputQueueMode.MUTEX_SAFE.name())
                    .putBoolean(K_ANALOG, true)
                    .putBoolean(K_COALESCE, true)
                    .putBoolean(K_MGL_LOG, false);
        }
        commit(editor);
    }

    /** Build 42 profile; General Runtime/Display, Native and Experimental remain untouched. */
    public void setBuild42LabProfile(@NonNull LabProfile profile) {
        SharedPreferences.Editor editor = prefs.edit()
                .putString(K_BUILD42_PROFILE, profile.name());
        if (profile != LabProfile.CUSTOM) {
            customPresets.clearActive(editor);
            for (OptLabFeatureRegistry.Feature feature
                    : OptLabFeatureRegistry.Feature.values()) {
                if (feature.category != OptLabFeatureRegistry.Category.BUILD42
                        || feature.preferenceKey == null || !feature.isVisible()) continue;
                boolean enabled;
                switch (profile) {
                    case SAFE: enabled = feature.safeProfile; break;
                    case RECOMMENDED: enabled = feature.recommendedProfile; break;
                    case AGGRESSIVE: enabled = feature.aggressiveProfile; break;
                    default: enabled = false; break;
                }
                editor.putBoolean(feature.preferenceKey, enabled);
            }
        }
        commit(editor);
    }

    /** Named snapshots cover all visible registry-owned BUILD42 features only. */
    @NonNull
    public List<OptLabCustomPresetStore.Preset> getCustomBuild42Presets() {
        return customPresets.list();
    }

    public OptLabCustomPresetStore.Preset getActiveCustomBuild42Preset() {
        return customPresets.active();
    }

    public boolean isActiveCustomBuild42PresetModified() {
        return customPresets.isActiveModified();
    }

    public boolean hasCustomBuild42PresetStorageProblem() {
        return customPresets.hasStorageProblem();
    }

    public String getCustomBuild42PresetStorageProblem() {
        return customPresets.storageProblem();
    }

    @NonNull
    public OptLabCustomPresetStore.Preset createCustomBuild42Preset(
            @NonNull String name) {
        return customPresets.create(name);
    }

    @NonNull
    public OptLabCustomPresetStore.Preset applyCustomBuild42Preset(@NonNull String id) {
        return customPresets.apply(id);
    }

    @NonNull
    public OptLabCustomPresetStore.Preset updateCustomBuild42Preset(@NonNull String id) {
        return customPresets.update(id);
    }

    @NonNull
    public OptLabCustomPresetStore.Preset renameCustomBuild42Preset(
            @NonNull String id, @NonNull String name) {
        return customPresets.rename(id, name);
    }

    @NonNull
    public OptLabCustomPresetStore.Preset duplicateCustomBuild42Preset(
            @NonNull String id, @NonNull String name) {
        return customPresets.duplicate(id, name);
    }

    public void deleteCustomBuild42Preset(@NonNull String id) {
        customPresets.delete(id);
    }

    /** UI disclosure only; it never changes the effective launch configuration. */
    public void setAdvancedControls(boolean enabled) {
        commit(prefs.edit().putBoolean(K_ADVANCED_CONTROLS, enabled));
    }

    public void setProfile(Profile profile) {
        if (profile == Profile.STREAM_ALL || profile == Profile.FBO_ALL
                || profile == Profile.FULL_CANDIDATE) {
            setBuild42Profile(profile);
            return;
        }
        SharedPreferences.Editor editor = prefs.edit()
                .putString(K_PROFILE, profile.name())
                .putString(K_GENERAL_PROFILE, LabProfile.CUSTOM.name());
        switch (profile) {
            case BASELINE:
                // A deterministic clean slate for the general Runtime/Display module.
                // Build 42 and Native keep their independent requested state.
                editor.putBoolean(K_MASTER, false)
                        .putBoolean(K_QUIET, false)
                        .putString(K_STDIO, StdioMode.LEGACY.name())
                        .putBoolean(K_SQLITE, false)
                        .putString(K_BOX64, Box64Policy.LEGACY_3_0.name())
                        .putString(K_SURFACE, SurfaceMode.LEGACY.name())
                        .putString(K_DISPLAY, DisplayFpsHint.OFF.name())
                        .putString(K_INPUT, InputQueueMode.LEGACY.name())
                        .putBoolean(K_ANALOG, false)
                        .putBoolean(K_COALESCE, false)
                        .putBoolean(K_MGL_LOG, false);
                break;
            case RUNTIME_SAFE:
                editor.putBoolean(K_MASTER, true)
                        .putBoolean(K_QUIET, true)
                        .putString(K_STDIO, StdioMode.BUFFERED.name())
                        .putBoolean(K_SQLITE, false)
                        .putString(K_BOX64, Box64Policy.LEGACY_3_0.name())
                        .putString(K_SURFACE, SurfaceMode.GEN_ACK.name())
                        .putString(K_DISPLAY, DisplayFpsHint.NATIVE_REFRESH.name())
                        .putString(K_INPUT, InputQueueMode.MUTEX_SAFE.name())
                        .putBoolean(K_ANALOG, true)
                        .putBoolean(K_COALESCE, true)
                        .putBoolean(K_MGL_LOG, false);
                break;
            case STREAM_ALL:
            case FBO_ALL:
            case FULL_CANDIDATE:
                throw new IllegalArgumentException("Build 42 profile was not routed");
            case ALL_TEST_ON:
                editor.putBoolean(K_MASTER, true)
                        .putBoolean(K_QUIET, true)
                        .putString(K_STDIO, StdioMode.BUFFERED.name())
                        .putBoolean(K_SQLITE, false)
                        .putString(K_BOX64, Box64Policy.LEVEL1_0.name())
                        .putString(K_SURFACE, SurfaceMode.GEN_ACK.name())
                        .putString(K_DISPLAY, DisplayFpsHint.NATIVE_REFRESH.name())
                        .putString(K_INPUT, InputQueueMode.MUTEX_SAFE.name())
                        .putBoolean(K_ANALOG, true)
                        .putBoolean(K_COALESCE, true)
                        .putBoolean(K_MGL_LOG, false);
                break;
            case CUSTOM:
                editor.putBoolean(K_MASTER, true);
                break;
        }
        commit(editor);
    }

    public void setMasterEnabled(boolean value) {
        if (!value) {
            // OFF is an atomic, durable rollback for general Runtime/Display only.
            setProfile(Profile.BASELINE);
            return;
        }
        commit(prefs.edit().putBoolean(K_MASTER, true)
                .putString(K_PROFILE, Profile.CUSTOM.name())
                .putString(K_GENERAL_PROFILE, LabProfile.CUSTOM.name()));
    }

    public void setSafeModeEnabled(boolean value) {
        commit(prefs.edit().putBoolean(K_SAFE_MODE, value));
    }

    public void setQuietRuntime(boolean value) { editCustom(K_QUIET, value); }
    public void setStdioMode(StdioMode value) { editCustom(K_STDIO, value.name()); }
    public void setMainloopPacing(boolean value) { editBuild42(K_PACING, value); }
    public void setSqliteAndroidNative(boolean value) { editCustom(K_SQLITE, value); }
    public void setBox64Policy(Box64Policy value) { editCustom(K_BOX64, value.name()); }
    public void setSurfaceMode(SurfaceMode value) { editCustom(K_SURFACE, value.name()); }
    public void setDisplayFpsHint(DisplayFpsHint value) { editCustom(K_DISPLAY, value.name()); }
    public void setInputQueueMode(InputQueueMode value) { editCustom(K_INPUT, value.name()); }
    public void setAnalogFilter(boolean value) { editCustom(K_ANALOG, value); }
    public void setInputCoalesce(boolean value) { editCustom(K_COALESCE, value); }
    public void setMobileGlFileLogEnabled(boolean value) { editCustom(K_MGL_LOG, value); }
    public void setStreamWake(boolean value) { editBuild42(K_STREAM_WAKE, value); }
    public void setStreamQueueFast(boolean value) { editBuild42(K_STREAM_QUEUE_FAST, value); }
    public void setStreamVelocityEta(boolean value) { editBuild42(K_STREAM_VELOCITY_ETA, value); }
    public void setFboDirtyDedup(boolean value) { editBuild42(K_FBO_DIRTY_DEDUP, value); }
    public void setFboFrameBudget(boolean value) { editBuild42(K_FBO_FRAME_BUDGET, value); }
    public void setStreamFboCoordinator(boolean value) {
        editBuild42(K_STREAM_FBO_COORDINATOR, value);
    }
    public void setChunkForage(boolean value) { editBuild42(K_CHUNK_FORAGE, value); }
    public void setChunkNeighbourWorker(boolean value) {
        editBuild42(K_CHUNK_NEIGHBOUR_WORKER, value);
    }
    public void setChunkNeighbourMain(boolean value) {
        editBuild42(K_CHUNK_NEIGHBOUR_MAIN, value);
    }
    public void setChunkGridLoad(boolean value) { editBuild42(K_CHUNK_GRID_LOAD, value); }
    public void setChunkVehicles(boolean value) { editBuild42(K_CHUNK_VEHICLES, value); }
    public void setChunkRandomizedBuildings(boolean value) {
        editBuild42(K_CHUNK_BUILDINGS, value);
    }
    public void setChunkLuaMapObjects(boolean value) { editBuild42(K_CHUNK_LUA, value); }
    public void setChunkWorldgenBiome(boolean value) { editBuild42(K_CHUNK_WORLDGEN, value); }
    public void setChunkCp2cDirtyClear(boolean value) { editExperimental(K_CHUNK_CP2C, value); }
    public void setRenderChunkDepthUpload(boolean value) {
        SharedPreferences.Editor editor = prefs.edit()
                .putString(K_BUILD42_PROFILE, LabProfile.CUSTOM.name())
                .putBoolean(K_RENDER_CHUNK_DEPTH_UPLOAD, value);
        if (!value) editor.putBoolean(K_RENDER_CHUNK_DEPTH_LOOKUP, false);
        commit(editor);
    }
    public void setRenderChunkDepthLookup(boolean value) {
        SharedPreferences.Editor editor = prefs.edit()
                .putString(K_BUILD42_PROFILE, LabProfile.CUSTOM.name())
                .putBoolean(K_RENDER_CHUNK_DEPTH_LOOKUP, value);
        if (value) editor.putBoolean(K_RENDER_CHUNK_DEPTH_UPLOAD, true);
        commit(editor);
    }
    public void setRenderRingEmptyClear(boolean value) {
        editBuild42(K_RENDER_RING_EMPTY_CLEAR, value);
    }

    public void setRenderCp62SafeProfile(boolean enabled) {
        commit(prefs.edit()
                .putString(K_BUILD42_PROFILE, LabProfile.CUSTOM.name())
                .putBoolean(K_RENDER_CHUNK_DEPTH_UPLOAD, enabled)
                .putBoolean(K_RENDER_CHUNK_DEPTH_LOOKUP, enabled)
                .putBoolean(K_RENDER_RING_EMPTY_CLEAR, enabled));
    }

    public void setChunkStableProfile() {
        commit(prefs.edit()
                .putString(K_BUILD42_PROFILE, LabProfile.CUSTOM.name())
                .putBoolean(K_CHUNK_FORAGE, false)
                .putBoolean(K_CHUNK_NEIGHBOUR_WORKER, true)
                .putBoolean(K_CHUNK_NEIGHBOUR_MAIN, true)
                .putBoolean(K_CHUNK_GRID_LOAD, false)
                .putBoolean(K_CHUNK_VEHICLES, true)
                .putBoolean(K_CHUNK_BUILDINGS, false)
                .putBoolean(K_CHUNK_LUA, false)
                .putBoolean(K_CHUNK_WORLDGEN, false));
    }

    public void setChunkAllProduction(boolean enabled) {
        commit(prefs.edit()
                .putString(K_BUILD42_PROFILE, LabProfile.CUSTOM.name())
                .putBoolean(K_CHUNK_FORAGE, enabled)
                .putBoolean(K_CHUNK_NEIGHBOUR_WORKER, enabled)
                .putBoolean(K_CHUNK_NEIGHBOUR_MAIN, enabled)
                .putBoolean(K_CHUNK_GRID_LOAD, enabled)
                .putBoolean(K_CHUNK_VEHICLES, enabled)
                .putBoolean(K_CHUNK_BUILDINGS, enabled)
                .putBoolean(K_CHUNK_LUA, enabled)
                // Experimental CP2C remains fully independent from production presets.
                .putBoolean(K_CHUNK_WORLDGEN, enabled));
    }

    /** Clears every production Build 42 mechanism while preserving Experimental CP2C. */
    public void setBuild42ProductionAllOff() {
        SharedPreferences.Editor editor = prefs.edit()
                .putString(K_BUILD42_PROFILE, LabProfile.CUSTOM.name())
                .putBoolean(K_PACING, false)
                .putBoolean(K_STREAM_WAKE, false)
                .putBoolean(K_STREAM_QUEUE_FAST, false)
                .putBoolean(K_STREAM_VELOCITY_ETA, false)
                .putBoolean(K_FBO_DIRTY_DEDUP, false)
                .putBoolean(K_FBO_FRAME_BUDGET, false)
                .putBoolean(K_STREAM_FBO_COORDINATOR, false)
                .putBoolean(K_CHUNK_FORAGE, false)
                .putBoolean(K_CHUNK_NEIGHBOUR_WORKER, false)
                .putBoolean(K_CHUNK_NEIGHBOUR_MAIN, false)
                .putBoolean(K_CHUNK_GRID_LOAD, false)
                .putBoolean(K_CHUNK_VEHICLES, false)
                .putBoolean(K_CHUNK_BUILDINGS, false)
                .putBoolean(K_CHUNK_LUA, false)
                .putBoolean(K_CHUNK_WORLDGEN, false)
                .putBoolean(K_RENDER_CHUNK_DEPTH_UPLOAD, false)
                .putBoolean(K_RENDER_CHUNK_DEPTH_LOOKUP, false)
                .putBoolean(K_RENDER_RING_EMPTY_CLEAR, false);
        for (OptLabFeatureRegistry.Feature feature : OptLabFeatureRegistry.Feature.values()) {
            if (feature.category == OptLabFeatureRegistry.Category.BUILD42
                    && feature.preferenceKey != null) {
                editor.putBoolean(feature.preferenceKey, false);
            }
        }
        commit(editor);
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
                + " chunk=" + chunkOptimizationEnabledCount() + "/8"
                + " render=" + renderOptimizationEnabledCount()
                + " total=" + build42FeatureEnabledCount();
    }

    public String chunkOptimizationSummary() {
        return chunkOptimizationEnabledCount() + "/8 active"
                + " · 42.20/42.20.3 VERIFIED"
                + " · hotfixuri B42: PROBE";
    }

    public int chunkOptimizationEnabledCount() {
        int count = 0;
        if (isChunkForage()) count++;
        if (isChunkNeighbourWorker()) count++;
        if (isChunkNeighbourMain()) count++;
        if (isChunkGridLoad()) count++;
        if (isChunkVehicles()) count++;
        if (isChunkRandomizedBuildings()) count++;
        if (isChunkLuaMapObjects()) count++;
        if (isChunkWorldgenBiome()) count++;
        return count;
    }

    public String renderOptimizationSummary() {
        return renderOptimizationEnabledCount() + "/" + renderOptimizationFeatureCount()
                + " active"
                + " · CP6.1/CP6.2"
                + " · 42.20/42.20.3 VERIFIED";
    }

    public int renderOptimizationEnabledCount() {
        int count = 0;
        for (OptLabFeatureRegistry.Feature feature : OptLabFeatureRegistry.Feature.values()) {
            if (isRenderModule(feature.module)
                    && feature.isVisible() && isFeatureEnabled(feature)) count++;
        }
        return count;
    }

    public int renderOptimizationFeatureCount() {
        int count = 0;
        for (OptLabFeatureRegistry.Feature feature : OptLabFeatureRegistry.Feature.values()) {
            if (isRenderModule(feature.module) && feature.isVisible()) count++;
        }
        return count;
    }

    public int experimentalBuild42EnabledCount() {
        int count = 0;
        for (OptLabFeatureRegistry.Feature feature : OptLabFeatureRegistry.Feature.values()) {
            if (feature.category == OptLabFeatureRegistry.Category.BUILD42
                    && feature.maturity == OptLabFeatureRegistry.Maturity.EXPERIMENTAL
                    && feature.isVisible() && isFeatureEnabled(feature)) count++;
        }
        return count;
    }

    public int build42FeatureEnabledCount() {
        int count = 0;
        for (OptLabFeatureRegistry.Feature feature : OptLabFeatureRegistry.Feature.values()) {
            if (feature.category == OptLabFeatureRegistry.Category.BUILD42
                    && feature.isVisible() && isFeatureEnabled(feature)) count++;
        }
        return count;
    }

    public String summary() {
        if (isSafeModeEnabled()) return "SAFE MODE · setări păstrate";
        if (!isMasterEnabled()) return "ALL OFF / legacy";
        return getProfile() + " · quiet=" + bool(isQuietRuntime())
                + " box64=" + getBox64Policy().name()
                + " surface=" + getSurfaceMode().name()
                + " input=" + getInputQueueMode().name();
    }

    public String machineReadable() {
        OptLabCustomPresetStore.Preset activePreset = getActiveCustomBuild42Preset();
        return "schema=" + SCHEMA
                + " safeMode=" + bool(isSafeModeEnabled())
                + " generalProfile=" + getGeneralProfile().name()
                + " build42Profile=" + getBuild42LabProfile().name()
                + " advancedControls=" + bool(isAdvancedControls())
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
                + " chunkForage=" + bool(isChunkForage())
                + " chunkNeighbourWorker=" + bool(isChunkNeighbourWorker())
                + " chunkNeighbourMain=" + bool(isChunkNeighbourMain())
                + " chunkGridLoad=" + bool(isChunkGridLoad())
                + " chunkVehicles=" + bool(isChunkVehicles())
                + " chunkRandomizedBuildings=" + bool(isChunkRandomizedBuildings())
                + " chunkLuaMapObjects=" + bool(isChunkLuaMapObjects())
                + " chunkWorldgenBiome=" + bool(isChunkWorldgenBiome())
                + " chunkCp2cDirtyClear=" + bool(isChunkCp2cDirtyClear())
                + " renderChunkDepthUpload=" + bool(isRenderChunkDepthUpload())
                + " renderChunkDepthLookup=" + bool(isRenderChunkDepthLookup())
                + " renderRingEmptyClear=" + bool(isRenderRingEmptyClear())
                + " onlyBuild42=" + bool(isOnlyBuild42())
                + " customPresetSchema=" + OptLabCustomPresetStore.SCHEMA
                + " customPresetCount=" + getCustomBuild42Presets().size()
                + " customPreset=" + (activePreset == null ? "none" : activePreset.getId())
                + " customPresetModified="
                + bool(activePreset != null && isActiveCustomBuild42PresetModified())
                + featureMachineReadable();
    }

    private String featureMachineReadable() {
        StringBuilder output = new StringBuilder();
        for (OptLabFeatureRegistry.Feature feature : OptLabFeatureRegistry.Feature.values()) {
            if (feature.category == OptLabFeatureRegistry.Category.BUILD42
                    && feature.isVisible()) {
                output.append(' ').append(feature.id).append('=')
                        .append(bool(isFeatureEnabled(feature)));
            }
        }
        return output.toString();
    }

    private void setBuild42Profile(Profile profile) {
        boolean stream = profile == Profile.STREAM_ALL || profile == Profile.FULL_CANDIDATE;
        boolean fbo = profile == Profile.FBO_ALL || profile == Profile.FULL_CANDIDATE;
        commit(prefs.edit()
                .putString(K_BUILD42_PROFILE, LabProfile.CUSTOM.name())
                .putBoolean(K_PACING, false)
                .putBoolean(K_STREAM_WAKE, stream)
                .putBoolean(K_STREAM_QUEUE_FAST, stream)
                .putBoolean(K_STREAM_VELOCITY_ETA, stream)
                .putBoolean(K_FBO_DIRTY_DEDUP, fbo)
                .putBoolean(K_FBO_FRAME_BUDGET, fbo)
                .putBoolean(K_STREAM_FBO_COORDINATOR,
                        profile == Profile.FULL_CANDIDATE));
    }

    private void editBuild42(String key, boolean value) {
        commit(prefs.edit().putBoolean(key, value)
                .putString(K_BUILD42_PROFILE, LabProfile.CUSTOM.name()));
    }

    private void editExperimental(String key, boolean value) {
        commit(prefs.edit().putBoolean(key, value));
    }

    private static boolean isRenderModule(OptLabFeatureRegistry.Module module) {
        return module == OptLabFeatureRegistry.Module.FBO_RENDER_CELL
                || module == OptLabFeatureRegistry.Module.RENDER_HOTPATH
                || module == OptLabFeatureRegistry.Module.MODEL_RINGBUFFER
                || module == OptLabFeatureRegistry.Module.SHADER_UNIFORMS;
    }

    private static void enableWithDependencies(SharedPreferences.Editor editor,
                                               OptLabFeatureRegistry.Feature feature,
                                               EnumSet<OptLabFeatureRegistry.Feature> seen) {
        if (!seen.add(feature)) return;
        if (feature.preferenceKey != null) editor.putBoolean(feature.preferenceKey, true);
        for (OptLabFeatureRegistry.Feature dependency : feature.dependencies) {
            enableWithDependencies(editor, dependency, seen);
        }
    }

    private static void disableWithDependents(SharedPreferences.Editor editor,
                                              OptLabFeatureRegistry.Feature feature,
                                              EnumSet<OptLabFeatureRegistry.Feature> seen) {
        if (!seen.add(feature)) return;
        if (feature.preferenceKey != null) editor.putBoolean(feature.preferenceKey, false);
        for (OptLabFeatureRegistry.Feature candidate
                : OptLabFeatureRegistry.Feature.values()) {
            if (candidate.dependencies.contains(feature)) {
                disableWithDependents(editor, candidate, seen);
            }
        }
    }

    private void editCustom(String key, boolean value) {
        commit(prefs.edit().putBoolean(key, value)
                .putString(K_PROFILE, Profile.CUSTOM.name())
                .putString(K_GENERAL_PROFILE, LabProfile.CUSTOM.name()));
    }

    private void editCustom(String key, String value) {
        commit(prefs.edit().putString(key, value)
                .putString(K_PROFILE, Profile.CUSTOM.name())
                .putString(K_GENERAL_PROFILE, LabProfile.CUSTOM.name()));
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

    private LabProfile migratedLabProfile(String key) {
        String legacy = prefs.getString(K_LAB_PROFILE, LabProfile.CUSTOM.name());
        String value = prefs.getString(key, legacy);
        try {
            return LabProfile.valueOf(value);
        } catch (IllegalArgumentException | NullPointerException ignored) {
            return LabProfile.CUSTOM;
        }
    }

    private boolean enabled(String key, boolean fallback) {
        return !isSafeModeEnabled() && prefs.getBoolean(key, fallback);
    }

    private static int bool(boolean value) { return value ? 1 : 0; }
}
