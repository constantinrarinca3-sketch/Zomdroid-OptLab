import android.content.Context;
import android.content.SharedPreferences;

import com.zomdroid.OptLabFeatureRegistry;
import com.zomdroid.OptLabPreferences;

import java.util.HashMap;
import java.util.Map;

/** Exercises the real preferences class and protects module independence. */
public final class OptLabPreferencesIndependenceUnit {
    public static void main(String[] args) {
        FakeContext context = new FakeContext();
        OptLabPreferences prefs = OptLabPreferences.from(context);

        prefs.setProfile(OptLabPreferences.Profile.RUNTIME_SAFE);
        prefs.setStreamWake(true);
        prefs.setChunkStableProfile();
        prefs.setRenderCp62SafeProfile(true);
        require(prefs.isMasterEnabled(), "general profile enabled");
        require(prefs.isQuietRuntime(), "general runtime active");
        require(prefs.isStreamWake(), "Build 42 active");
        require(prefs.chunkOptimizationEnabledCount() == 3, "stable Chunk profile active");
        require(prefs.renderOptimizationEnabledCount() == 3, "CP6.2 Render profile active");

        prefs.setGeneralProfile(OptLabPreferences.LabProfile.RECOMMENDED);
        require(prefs.getGeneralProfile() == OptLabPreferences.LabProfile.RECOMMENDED,
                "general profile identity");
        require(prefs.isStreamWake() && prefs.isChunkNeighbourMain()
                        && prefs.isRenderChunkDepthLookup(),
                "general profile must not rewrite Build 42");
        boolean quietBeforeBuild42Profile = prefs.isQuietRuntime();
        prefs.setBuild42LabProfile(OptLabPreferences.LabProfile.SAFE);
        require(prefs.getBuild42LabProfile() == OptLabPreferences.LabProfile.SAFE,
                "Build 42 profile identity");
        require(prefs.isQuietRuntime() == quietBeforeBuild42Profile,
                "Build 42 profile must not rewrite General");
        prefs.setBuild42LabProfile(OptLabPreferences.LabProfile.AGGRESSIVE);
        require(prefs.getGeneralProfile() == OptLabPreferences.LabProfile.RECOMMENDED,
                "Build 42 profile preserves General profile identity");

        prefs.setMasterEnabled(false);
        require(!prefs.isMasterEnabled(), "general master disabled");
        require(!prefs.isQuietRuntime(), "general runtime disabled");
        require(prefs.isStreamWake(), "Build 42 survives general OFF");
        require(prefs.isChunkNeighbourMain(), "Chunk survives general OFF");
        require(prefs.isRenderChunkDepthLookup(), "Render survives general OFF");
        require(prefs.isAnyAgentOptimizationEnabled(), "agent follows independent Build 42");

        prefs.setProfile(OptLabPreferences.Profile.FBO_ALL);
        require(!prefs.isMasterEnabled(), "Build 42 preset does not enable general master");
        require(prefs.isFboDirtyDedup() && prefs.isFboFrameBudget(), "FBO preset active");
        require(!prefs.isStreamWake(), "FBO preset isolates stream");
        require(prefs.chunkOptimizationEnabledCount() == 3,
                "Stream/FBO presets preserve Chunk branch");
        require(prefs.renderOptimizationEnabledCount() == 3,
                "Stream/FBO presets preserve Render branch");

        prefs.setRenderChunkDepthUpload(false);
        require(!prefs.isRenderChunkDepthUpload() && !prefs.isRenderChunkDepthLookup(),
                "disabling upload atomically disables dependent lookup");
        prefs.setRenderChunkDepthLookup(true);
        require(prefs.isRenderChunkDepthUpload() && prefs.isRenderChunkDepthLookup(),
                "enabling lookup atomically enables required upload");

        prefs.setChunkCp2cDirtyClear(true);
        prefs.setChunkAllProduction(true);
        require(prefs.chunkOptimizationEnabledCount() == 8, "all production Chunk features");
        require(prefs.isChunkCp2cDirtyClear(), "experimental CP2C remains independent");
        prefs.setChunkAllProduction(false);
        require(prefs.chunkOptimizationEnabledCount() == 0, "Chunk production ALL OFF");
        require(prefs.isChunkCp2cDirtyClear(), "production ALL OFF preserves CP2C state");

        prefs.setProfile(OptLabPreferences.Profile.RUNTIME_SAFE);
        require(prefs.isFboDirtyDedup(), "general profile preserves Build 42");
        require(prefs.getProfile() == OptLabPreferences.Profile.RUNTIME_SAFE,
                "general profile identity preserved");

        prefs.setSafeModeEnabled(true);
        require(prefs.isSafeModeEnabled(), "Safe Mode persisted ON");
        require(!prefs.isMasterEnabled(), "Safe Mode bypasses general master");
        require(!prefs.isFboDirtyDedup(), "Safe Mode bypasses Build 42");
        require(!prefs.isChunkCp2cDirtyClear(), "Safe Mode bypasses Experimental");
        require(!prefs.isAnyAgentOptimizationEnabled(), "Safe Mode requests no agent work");
        require(prefs.summary().contains("SAFE MODE"), "Safe Mode visible in summary");
        prefs.setSafeModeEnabled(false);
        require(prefs.isMasterEnabled(), "general master restored after Safe Mode");
        require(prefs.isFboDirtyDedup(), "Build 42 state restored after Safe Mode");
        require(prefs.isChunkCp2cDirtyClear(), "Experimental state restored after Safe Mode");

        prefs.setBuild42ProductionAllOff();
        require(!prefs.isAnyChunkOptimizationEnabled(), "Build 42 ALL OFF clears Chunk");
        require(!prefs.isAnyRenderOptimizationEnabled(), "Build 42 ALL OFF clears Render");
        require(!prefs.isFboDirtyDedup(), "Build 42 ALL OFF clears FBO");
        require(prefs.isChunkCp2cDirtyClear(), "Build 42 ALL OFF preserves Experimental");

        // Regression reported on device: an old Stream/FBO/Chunk preset must never re-arm a
        // feature after the user explicitly turns it OFF, including through a new preferences
        // object that models a complete application restart.
        prefs.setProfile(OptLabPreferences.Profile.STREAM_ALL);
        prefs.setStreamWake(false);
        prefs.setStreamQueueFast(false);
        prefs.setStreamVelocityEta(false);
        prefs.setProfile(OptLabPreferences.Profile.FBO_ALL);
        prefs.setFboDirtyDedup(false);
        prefs.setFboFrameBudget(false);
        prefs.setStreamFboCoordinator(false);
        prefs.setChunkAllProduction(true);
        prefs.setChunkGridLoad(false);
        OptLabPreferences restarted = OptLabPreferences.from(context);
        require(!restarted.isStreamWake() && !restarted.isStreamQueueFast()
                        && !restarted.isStreamVelocityEta(),
                "Stream Core re-armed after restart");
        require(!restarted.isFboDirtyDedup() && !restarted.isFboFrameBudget()
                        && !restarted.isStreamFboCoordinator(),
                "FBO re-armed after restart");
        require(!restarted.isChunkGridLoad(), "World Chunk re-armed after restart");
        require(restarted.getBuild42LabProfile() == OptLabPreferences.LabProfile.CUSTOM,
                "explicit OFF did not retain Custom profile identity");

        restarted.setBuild42ProductionAllOff();
        OptLabPreferences allOffRestarted = OptLabPreferences.from(context);
        for (OptLabFeatureRegistry.Feature feature
                : OptLabFeatureRegistry.Feature.values()) {
            if (feature.category == OptLabFeatureRegistry.Category.BUILD42
                    && feature.isVisible()) {
                require(!allOffRestarted.isFeatureEnabled(feature),
                        "ALL OFF re-armed " + feature.id + " after restart");
            }
        }

        // Exercise every non-registry Runtime/Display control in both directions through a
        // freshly constructed preferences object.  Registry-owned controls are covered by the
        // exhaustive loop in OptLabRegistryIntegrityUnit.
        FakeContext allSettingsContext = new FakeContext();
        OptLabPreferences allSettings = OptLabPreferences.from(allSettingsContext);
        allSettings.setMasterEnabled(true);
        allSettings.setQuietRuntime(true);
        allSettings.setStdioMode(OptLabPreferences.StdioMode.BUFFERED);
        allSettings.setSqliteAndroidNative(true);
        allSettings.setBox64Policy(OptLabPreferences.Box64Policy.LEVEL1_0);
        allSettings.setSurfaceMode(OptLabPreferences.SurfaceMode.GEN_ACK);
        allSettings.setDisplayFpsHint(OptLabPreferences.DisplayFpsHint.NATIVE_REFRESH);
        allSettings.setInputQueueMode(OptLabPreferences.InputQueueMode.MUTEX_SAFE);
        allSettings.setAnalogFilter(true);
        allSettings.setInputCoalesce(true);
        allSettings.setMobileGlFileLogEnabled(true);
        allSettings.setOnlyBuild42(false);
        allSettings.setAdvancedControls(true);
        allSettings = OptLabPreferences.from(allSettingsContext);
        require(allSettings.isMasterEnabled() && allSettings.isQuietRuntime(),
                "Runtime booleans did not persist ON");
        require(allSettings.getStdioMode() == OptLabPreferences.StdioMode.BUFFERED,
                "stdio did not persist");
        require(allSettings.isSqliteAndroidNative(), "SQLite did not persist ON");
        require(allSettings.getBox64Policy() == OptLabPreferences.Box64Policy.LEVEL1_0,
                "Box64 policy did not persist");
        require(allSettings.getSurfaceMode() == OptLabPreferences.SurfaceMode.GEN_ACK,
                "surface mode did not persist");
        require(allSettings.getDisplayFpsHint()
                        == OptLabPreferences.DisplayFpsHint.NATIVE_REFRESH,
                "display hint did not persist");
        require(allSettings.getInputQueueMode()
                        == OptLabPreferences.InputQueueMode.MUTEX_SAFE,
                "input mode did not persist");
        require(allSettings.isAnalogFilter() && allSettings.isInputCoalesce()
                        && allSettings.isMobileGlFileLogEnabled(),
                "Display/Input booleans did not persist ON");
        require(!allSettings.isOnlyBuild42() && allSettings.isAdvancedControls(),
                "independent disclosure/policy values did not persist");

        allSettings.setQuietRuntime(false);
        allSettings.setStdioMode(OptLabPreferences.StdioMode.LEGACY);
        allSettings.setSqliteAndroidNative(false);
        allSettings.setBox64Policy(OptLabPreferences.Box64Policy.DEFAULT);
        allSettings.setSurfaceMode(OptLabPreferences.SurfaceMode.LEGACY);
        allSettings.setDisplayFpsHint(OptLabPreferences.DisplayFpsHint.OFF);
        allSettings.setInputQueueMode(OptLabPreferences.InputQueueMode.LEGACY);
        allSettings.setAnalogFilter(false);
        allSettings.setInputCoalesce(false);
        allSettings.setMobileGlFileLogEnabled(false);
        allSettings.setOnlyBuild42(true);
        allSettings.setAdvancedControls(false);
        allSettings = OptLabPreferences.from(allSettingsContext);
        require(!allSettings.isQuietRuntime()
                        && allSettings.getStdioMode() == OptLabPreferences.StdioMode.LEGACY
                        && !allSettings.isSqliteAndroidNative()
                        && allSettings.getBox64Policy() == OptLabPreferences.Box64Policy.DEFAULT
                        && allSettings.getSurfaceMode() == OptLabPreferences.SurfaceMode.LEGACY
                        && allSettings.getDisplayFpsHint() == OptLabPreferences.DisplayFpsHint.OFF
                        && allSettings.getInputQueueMode() == OptLabPreferences.InputQueueMode.LEGACY
                        && !allSettings.isAnalogFilter() && !allSettings.isInputCoalesce()
                        && !allSettings.isMobileGlFileLogEnabled()
                        && allSettings.isOnlyBuild42() && !allSettings.isAdvancedControls(),
                "one or more Runtime/Display controls re-armed after explicit OFF");

        require(OptLabPreferences.SCHEMA == 12, "schema 12");
        require(OptLabPreferences.isKnownPzJarSha256(
                OptLabPreferences.EXPECTED_PZ_42203_JAR_SHA256), "42.20.3 known");
        require(prefs.machineReadable().contains("generalProfile=")
                        && prefs.machineReadable().contains("build42Profile="),
                "separate profiles missing from diagnostics");
        System.out.println("OPTLAB_PREFERENCES_INDEPENDENCE_UNIT PASS schema=12 safe_mode=1"
                + " profiles=independent off_restart=all_features general_roundtrip=all");
    }

    private static final class FakeContext extends Context {
        private final FakePreferences preferences = new FakePreferences();
        @Override public Context getApplicationContext() { return this; }
        @Override public SharedPreferences getSharedPreferences(String name, int mode) {
            return preferences;
        }
    }

    private static final class FakePreferences implements SharedPreferences {
        private final Map<String, Object> values = new HashMap<>();
        @Override public boolean getBoolean(String key, boolean fallback) {
            Object value = values.get(key);
            return value instanceof Boolean ? (Boolean) value : fallback;
        }
        @Override public String getString(String key, String fallback) {
            Object value = values.get(key);
            return value instanceof String ? (String) value : fallback;
        }
        @Override public Editor edit() {
            return new Editor() {
                private final Map<String, Object> pending = new HashMap<>();
                @Override public Editor putBoolean(String key, boolean value) {
                    pending.put(key, value);
                    return this;
                }
                @Override public Editor putString(String key, String value) {
                    pending.put(key, value);
                    return this;
                }
                @Override public boolean commit() {
                    values.putAll(pending);
                    return true;
                }
            };
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
