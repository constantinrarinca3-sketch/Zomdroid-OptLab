import android.content.Context;
import android.content.SharedPreferences;

import com.zomdroid.OptLabFeatureRegistry;
import com.zomdroid.OptLabPreferences;

import java.util.EnumSet;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/** Guards registry invariants before the table grows to dozens of optimizations. */
public final class OptLabRegistryIntegrityUnit {
    public static void main(String[] args) {
        Set<String> ids = new HashSet<>();
        Set<String> preferenceKeys = new HashSet<>();
        Set<String> properties = new HashSet<>();
        int renderFeatures = 0;
        int archived = 0;

        for (OptLabFeatureRegistry.Feature feature
                : OptLabFeatureRegistry.Feature.values()) {
            require(ids.add(feature.id), "duplicate ID " + feature.id);
            if (feature.preferenceKey != null) {
                require(preferenceKeys.add(feature.preferenceKey),
                        "duplicate preference " + feature.preferenceKey);
            }
            if (feature.agentProperty != null) {
                require(properties.add(feature.agentProperty),
                        "duplicate agent property " + feature.agentProperty);
            }
            if (feature.category == OptLabFeatureRegistry.Category.BUILD42
                    && feature.isVisible()) {
                require(feature.preferenceKey != null,
                        "visible B42 feature without preference " + feature.id);
                require(feature.agentProperty != null,
                        "visible B42 feature without property " + feature.id);
                for (OptLabFeatureRegistry.Feature dependency : feature.dependencies) {
                    require(dependency.category == OptLabFeatureRegistry.Category.BUILD42
                                    && dependency.preferenceKey != null
                                    && dependency.isVisible(),
                            "B42 preset dependency escapes its scope " + feature.id
                                    + " -> " + dependency.id);
                }
            }
            if (feature.maturity == OptLabFeatureRegistry.Maturity.INTERNAL) {
                require(!feature.advancedControl,
                        "internal feature exposed as toggle " + feature.id);
            }
            if (feature.maturity == OptLabFeatureRegistry.Maturity.EXPERIMENTAL) {
                require(!feature.recommendedProfile,
                        "experimental feature in Recommended " + feature.id);
            }
            if (feature.maturity == OptLabFeatureRegistry.Maturity.ARCHIVED) {
                archived++;
                require(!feature.isVisible() && feature.preferenceKey == null
                                && feature.agentProperty == null,
                        "archived feature can launch " + feature.id);
            }
            if (feature.id.startsWith("RTHREAD_") && feature.isVisible()) renderFeatures++;
            visit(feature, EnumSet.noneOf(OptLabFeatureRegistry.Feature.class),
                    EnumSet.noneOf(OptLabFeatureRegistry.Feature.class));
        }

        require(renderFeatures == 12,
                "expected 12 visible render features, got " + renderFeatures);
        require(archived == 5, "expected 5 archived/rejected features, got " + archived);
        require(!ids.contains("FBO_FRAME_BUDGET")
                        && !ids.contains("STREAM_FBO_COORDINATOR"),
                "removed FBO governor mechanisms returned to the registry");
        require(!properties.contains("zomdroid.optlab.fbo.frame.budget")
                        && !properties.contains("zomdroid.optlab.stream.fbo.coordinator"),
                "removed FBO governor properties returned to the launch contract");
        require(OptLabFeatureRegistry.count(
                OptLabFeatureRegistry.Module.MEMORY_ALLOCATION,
                OptLabFeatureRegistry.Maturity.STABLE) == 0,
                "Memory module is reserved, not fabricated");
        OptLabFeatureRegistry.Feature fboInner =
                OptLabFeatureRegistry.Feature.FBO_INNER_LOOP;
        require(fboInner.maturity == OptLabFeatureRegistry.Maturity.EXPERIMENTAL,
                "FBO inner loop must remain Experimental");
        require(fboInner.validation
                        == OptLabFeatureRegistry.Validation.DEVICE_POC_VALIDATED,
                "FBO inner loop validation classification drifted");
        require(!fboInner.defaultEnabled && !fboInner.safeProfile
                        && !fboInner.recommendedProfile && !fboInner.aggressiveProfile,
                "FBO inner loop must remain OFF in every profile");
        OptLabFeatureRegistry.Feature sameProgram =
                OptLabFeatureRegistry.Feature.RTHREAD_SAME_PROGRAM_BIND;
        require(sameProgram.maturity == OptLabFeatureRegistry.Maturity.EXPERIMENTAL,
                "same-program bind must remain Experimental");
        require(sameProgram.validation
                        == OptLabFeatureRegistry.Validation.DEVICE_POC_VALIDATED,
                "same-program bind validation classification drifted");
        require(!sameProgram.defaultEnabled && !sameProgram.safeProfile
                        && !sameProgram.recommendedProfile
                        && !sameProgram.aggressiveProfile,
                "same-program bind must remain OFF in every profile");
        verifyProfiles();
        System.out.println("OPTLAB_REGISTRY_INTEGRITY_UNIT PASS features="
                + OptLabFeatureRegistry.Feature.values().length
                + " render_visible=" + renderFeatures + " archived=" + archived
                + " ids_unique=1 prefs_unique=1 properties_unique=1 cycles=0 profiles=3"
                + " toggle_roundtrip=all");
    }

    private static void verifyProfiles() {
        FakeContext context = new FakeContext();
        OptLabPreferences prefs = OptLabPreferences.from(context);
        prefs.setGeneralProfile(OptLabPreferences.LabProfile.RECOMMENDED);
        verifyProfile(prefs, OptLabPreferences.LabProfile.SAFE);
        verifyProfile(prefs, OptLabPreferences.LabProfile.RECOMMENDED);
        verifyProfile(prefs, OptLabPreferences.LabProfile.AGGRESSIVE);
        require(prefs.getGeneralProfile() == OptLabPreferences.LabProfile.RECOMMENDED,
                "Build 42 profiles changed General profile");
        prefs.setAdvancedControls(true);
        require(prefs.isAdvancedControls(), "advanced disclosure not persisted");
        int before = prefs.build42FeatureEnabledCount();
        prefs.setAdvancedControls(false);
        require(before == prefs.build42FeatureEnabledCount(),
                "advanced disclosure changed launch state");
        prefs.setBuild42LabProfile(OptLabPreferences.LabProfile.RECOMMENDED);
        OptLabFeatureRegistry.Feature internal =
                OptLabFeatureRegistry.Feature.RTHREAD_MVP;
        require(prefs.isFeatureEnabled(internal),
                "Recommended did not enable the internal MVP fastpath");
        prefs.setFeatureEnabled(internal, false);
        require(!prefs.isFeatureEnabled(internal),
                "internal feature cannot be disabled individually");
        require(prefs.getBuild42LabProfile() == OptLabPreferences.LabProfile.CUSTOM,
                "internal override did not mark the Build 42 profile Custom");
        prefs.setBuild42ProductionAllOff();
        prefs.setFeatureEnabled(
                OptLabFeatureRegistry.Feature.RTHREAD_CHUNK_DEPTH_LOOKUP, true);
        require(prefs.isFeatureEnabled(
                        OptLabFeatureRegistry.Feature.RTHREAD_CHUNK_DEPTH_UPLOAD),
                "generic enable did not close dependency graph");
        prefs.setFeatureEnabled(
                OptLabFeatureRegistry.Feature.RTHREAD_CHUNK_DEPTH_UPLOAD, false);
        require(!prefs.isFeatureEnabled(
                        OptLabFeatureRegistry.Feature.RTHREAD_CHUNK_DEPTH_LOOKUP),
                "generic disable did not close dependent graph");
        prefs.setChunkCp2cDirtyClear(true);
        prefs.setBuild42ProductionAllOff();
        require(prefs.isChunkCp2cDirtyClear(),
                "Build 42 production ALL OFF cleared Experimental CP2C");

        // Exercise the exact generic API used by every registry-generated UI switch.  Each
        // visible Build 42/Experimental feature must persist ON and then explicit OFF through a
        // new preferences object, including dependency and dependent closure.
        prefs.setSafeModeEnabled(false);
        for (OptLabFeatureRegistry.Feature feature
                : OptLabFeatureRegistry.Feature.values()) {
            if (!feature.isVisible() || feature.preferenceKey == null
                    || (feature.category != OptLabFeatureRegistry.Category.BUILD42
                    && feature.category != OptLabFeatureRegistry.Category.EXPERIMENTAL)) continue;
            prefs.setFeatureEnabled(feature, true);
            prefs = OptLabPreferences.from(context);
            require(prefs.isFeatureEnabled(feature),
                    "toggle did not persist ON after restart: " + feature.id);
            prefs.setFeatureEnabled(feature, false);
            prefs = OptLabPreferences.from(context);
            require(!prefs.isFeatureEnabled(feature),
                    "toggle re-armed after explicit OFF: " + feature.id);
        }
    }

    private static void verifyProfile(OptLabPreferences prefs,
                                      OptLabPreferences.LabProfile profile) {
        prefs.setBuild42LabProfile(profile);
        require(prefs.getBuild42LabProfile() == profile,
                "Build 42 profile identity " + profile);
        for (OptLabFeatureRegistry.Feature feature
                : OptLabFeatureRegistry.Feature.values()) {
            if (feature.category != OptLabFeatureRegistry.Category.BUILD42
                    || !feature.isVisible()) continue;
            boolean expected;
            switch (profile) {
                case SAFE: expected = feature.safeProfile; break;
                case RECOMMENDED: expected = feature.recommendedProfile; break;
                case AGGRESSIVE: expected = feature.aggressiveProfile; break;
                default: throw new AssertionError(profile);
            }
            require(prefs.isFeatureEnabled(feature) == expected,
                    profile + " mismatch " + feature.id);
        }
    }

    private static void visit(OptLabFeatureRegistry.Feature feature,
                              EnumSet<OptLabFeatureRegistry.Feature> visiting,
                              EnumSet<OptLabFeatureRegistry.Feature> visited) {
        if (visited.contains(feature)) return;
        require(visiting.add(feature), "dependency cycle at " + feature.id);
        for (OptLabFeatureRegistry.Feature dependency : feature.dependencies) {
            require(dependency != feature, "self dependency " + feature.id);
            visit(dependency, visiting, visited);
        }
        visiting.remove(feature);
        visited.add(feature);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
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
}
