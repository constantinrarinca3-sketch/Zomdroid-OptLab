import android.content.Context;
import android.content.SharedPreferences;

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

        require(OptLabPreferences.SCHEMA == 11, "schema 11");
        require(OptLabPreferences.isKnownPzJarSha256(
                OptLabPreferences.EXPECTED_PZ_42203_JAR_SHA256), "42.20.3 known");
        require(prefs.machineReadable().contains("generalProfile=")
                        && prefs.machineReadable().contains("build42Profile="),
                "separate profiles missing from diagnostics");
        System.out.println("OPTLAB_PREFERENCES_INDEPENDENCE_UNIT PASS schema=11 safe_mode=1"
                + " profiles=independent");
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
