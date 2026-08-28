package com.zomdroid;

import android.util.Log;

import androidx.annotation.NonNull;

import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;

/**
 * Launcher-side source of truth for OPT-LAB feature ownership and compatibility.
 *
 * <p>This registry intentionally separates a user's persisted request from the state that can be
 * activated for the selected game instance.  Build-independent controls remain available when a
 * Project Zomboid identity changes, while bytecode patches are handed to the agent as B42 probes.
 * The agent performs the final per-class and per-shape decision.</p>
 */
public final class OptLabFeatureRegistry {
    private static final String LOG_TAG = "ZD-OPT-COMPAT";

    public enum Category {
        GENERAL,
        BUILD42,
        NATIVE
    }

    public enum Compatibility {
        VERIFIED,
        COMPATIBLE,
        PROBE,
        UNSUPPORTED
    }

    public enum Feature {
        QUIET_RUNTIME("QUIET_RUNTIME", Category.GENERAL, false, true),
        BUFFERED_STDIO("BUFFERED_STDIO", Category.GENERAL, false, true),
        SQLITE_ANDROID_NATIVE("SQLITE_ANDROID_NATIVE", Category.GENERAL, false, true),
        BOX64_POLICY("BOX64_POLICY", Category.GENERAL, false, true),
        SURFACE_GENERATION_ACK("SURFACE_GENERATION_ACK", Category.GENERAL, false, true),
        DISPLAY_FPS_HINT("DISPLAY_FPS_HINT", Category.GENERAL, false, true),
        INPUT_MUTEX_SAFE("INPUT_MUTEX_SAFE", Category.GENERAL, false, true),
        INPUT_ANALOG_FILTER("INPUT_ANALOG_FILTER", Category.GENERAL, false, true),
        INPUT_COALESCE("INPUT_COALESCE", Category.GENERAL, false, true),
        MOBILEGL_FILE_LOG("MOBILEGL_FILE_LOG", Category.GENERAL, false, true),

        PACING("PACING", Category.BUILD42, false, true),
        STREAM_WAKE("STREAM_WAKE", Category.BUILD42, false, true),
        STREAM_QUEUE_FAST("STREAM_QUEUE_FAST", Category.BUILD42, false, true),
        STREAM_VELOCITY_ETA("STREAM_VELOCITY_ETA", Category.BUILD42, false, true),
        FBO_DIRTY_DEDUP("FBO_DIRTY_DEDUP", Category.BUILD42, false, true),
        FBO_FRAME_BUDGET("FBO_FRAME_BUDGET", Category.BUILD42, false, true),
        STREAM_FBO_COORDINATOR("STREAM_FBO_COORDINATOR", Category.BUILD42, false, true),

        LIGHTING64_NATIVE("LIGHTING64_NATIVE", Category.NATIVE, true, true),
        PZCLIPPER_NATIVE("PZCLIPPER_NATIVE", Category.NATIVE, true, true),
        PATHFINDING_NATIVE("PATHFINDING_NATIVE", Category.NATIVE, false, true),
        POPMAN_NATIVE("POPMAN_NATIVE", Category.NATIVE, false, true);

        public final String id;
        public final Category category;
        public final boolean defaultEnabled;
        public final boolean restartRequired;

        Feature(String id, Category category, boolean defaultEnabled,
                boolean restartRequired) {
            this.id = id;
            this.category = category;
            this.defaultEnabled = defaultEnabled;
            this.restartRequired = restartRequired;
        }
    }

    public static final class Entry {
        public final Feature feature;
        public final boolean requested;
        public final Compatibility compatibility;
        public final String reason;

        private Entry(Feature feature, boolean requested, Compatibility compatibility,
                      String reason) {
            this.feature = feature;
            this.requested = requested;
            this.compatibility = compatibility;
            this.reason = reason;
        }

        public String machineReadable() {
            return "feature=" + feature.id
                    + " category=" + feature.category
                    + " requested=" + bit(requested)
                    + " compatibility=" + compatibility
                    + " restartRequired=" + bit(feature.restartRequired)
                    + " defaultEnabled=" + bit(feature.defaultEnabled)
                    + " reason=" + token(reason);
        }
    }

    public static final class Snapshot {
        private final String buildFamily;
        private final String versionHint;
        private final String jarSha256;
        private final boolean knownJar;
        private final boolean onlyBuild42;
        private final Map<Feature, Entry> entries;

        private Snapshot(String buildFamily, String versionHint, String jarSha256,
                         boolean knownJar, boolean onlyBuild42,
                         EnumMap<Feature, Entry> entries) {
            this.buildFamily = buildFamily;
            this.versionHint = versionHint;
            this.jarSha256 = jarSha256;
            this.knownJar = knownJar;
            this.onlyBuild42 = onlyBuild42;
            this.entries = Collections.unmodifiableMap(entries);
        }

        public boolean isBuild42() {
            return "42".equals(buildFamily);
        }

        public boolean isKnownJar() {
            return knownJar;
        }

        public boolean isOnlyBuild42() {
            return onlyBuild42;
        }

        @NonNull
        public Entry get(@NonNull Feature feature) {
            Entry entry = entries.get(feature);
            if (entry == null) throw new IllegalArgumentException("Unknown feature " + feature);
            return entry;
        }

        public String identityMachineReadable() {
            return "PZ_VERSION_HINT=" + token(versionHint)
                    + " PZ_BUILD_FAMILY=" + token(buildFamily)
                    + " PZ_SHA256=" + token(jarSha256)
                    + " PZ_SHA256_KNOWN=" + bit(knownJar)
                    + " ONLY_BUILD_42=" + bit(onlyBuild42);
        }

        public void log() {
            Log.i(LOG_TAG, identityMachineReadable());
            for (Feature feature : Feature.values()) {
                Log.i(LOG_TAG, get(feature).machineReadable());
            }
        }

        public void addAgentIdentityProperties(@NonNull java.util.List<String> jvmArgs) {
            jvmArgs.add("-Dzomdroid.optlab.pz.build.family=" + propertyValue(buildFamily));
            jvmArgs.add("-Dzomdroid.optlab.pz.version.hint=" + propertyValue(versionHint));
            jvmArgs.add("-Dzomdroid.optlab.jar.sha256=" + propertyValue(jarSha256));
            jvmArgs.add("-Dzomdroid.optlab.jar.known=" + bit(knownJar));
            jvmArgs.add("-Dzomdroid.optlab.only.build42=" + bit(onlyBuild42));
        }

        public String proofLines(String session) {
            StringBuilder output = new StringBuilder(2048);
            output.append("[ZD-OPT-PROOF] session=").append(token(session))
                    .append(" mechanism=PZ_IDENTITY state=")
                    .append(isBuild42() ? "COMPATIBLE"
                            : (onlyBuild42 ? "UNSUPPORTED" : "PROBE"))
                    .append(" detail=").append(identityMachineReadable().replace(' ', '_'))
                    .append('\n');
            for (Feature feature : Feature.values()) {
                Entry entry = get(feature);
                output.append("[ZD-OPT-PROOF] session=").append(token(session))
                        .append(" mechanism=").append(feature.id)
                        .append(" state=").append(entry.compatibility)
                        .append(" detail=category_").append(feature.category)
                        .append("_requested_").append(bit(entry.requested))
                        .append("_reason_").append(token(entry.reason))
                        .append('\n');
            }
            return output.toString();
        }
    }

    private OptLabFeatureRegistry() {}

    @NonNull
    public static Snapshot evaluate(@NonNull GameInstance gameInstance,
                                    @NonNull OptLabPreferences preferences,
                                    @NonNull NativeModulesPreferences nativePreferences,
                                    @NonNull String jarSha256,
                                    boolean lightingActive,
                                    boolean clipperActive,
                                    boolean pathfindingActive) {
        String family = token(gameInstance.getBuildVersion());
        String version = detectVersionHint(gameInstance);
        boolean build42 = "42".equals(family);
        boolean knownJar = OptLabPreferences.EXPECTED_PZ_JAR_SHA256.equals(jarSha256);
        boolean onlyBuild42 = preferences.isOnlyBuild42();
        EnumMap<Feature, Entry> entries = new EnumMap<>(Feature.class);

        for (Feature feature : Feature.values()) {
            boolean requested = requested(feature, preferences, nativePreferences, build42);
            Compatibility compatibility;
            String reason;
            switch (feature.category) {
                case GENERAL:
                    compatibility = Compatibility.COMPATIBLE;
                    reason = "BUILD_INDEPENDENT";
                    break;
                case BUILD42:
                    if (!build42 && onlyBuild42) {
                        compatibility = Compatibility.UNSUPPORTED;
                        reason = "ONLY_BUILD_42_POLICY";
                    } else if (build42 && knownJar) {
                        compatibility = Compatibility.VERIFIED;
                        reason = "KNOWN_PZ_JAR";
                    } else {
                        compatibility = Compatibility.PROBE;
                        reason = build42 ? "AGENT_CLASS_PROBE_REQUIRED"
                                : "CROSS_FAMILY_AGENT_PROBE_REQUIRED";
                    }
                    break;
                case NATIVE:
                    if (feature == Feature.POPMAN_NATIVE) {
                        compatibility = Compatibility.UNSUPPORTED;
                        reason = "CHECKPOINT_2_NOT_IMPLEMENTED";
                    } else if (!build42) {
                        compatibility = Compatibility.UNSUPPORTED;
                        reason = "BUILD_FAMILY_NOT_42";
                    } else if (!requested) {
                        compatibility = Compatibility.COMPATIBLE;
                        reason = "USER_DISABLED";
                    } else {
                        boolean active;
                        switch (feature) {
                            case LIGHTING64_NATIVE:
                                active = lightingActive;
                                break;
                            case PZCLIPPER_NATIVE:
                                active = clipperActive;
                                break;
                            case PATHFINDING_NATIVE:
                                active = pathfindingActive;
                                break;
                            default:
                                active = false;
                                break;
                        }
                        compatibility = active
                                ? Compatibility.VERIFIED : Compatibility.UNSUPPORTED;
                        reason = active ? "AUDITED_ARM64_PAYLOAD_ACTIVE"
                                : "NATIVE_PREFLIGHT_FAILED";
                    }
                    break;
                default:
                    throw new AssertionError(feature.category);
            }
            entries.put(feature, new Entry(feature, requested, compatibility, reason));
        }
        return new Snapshot(family, version, jarSha256, knownJar, onlyBuild42, entries);
    }

    private static boolean requested(Feature feature, OptLabPreferences prefs,
                                     NativeModulesPreferences nativePreferences,
                                     boolean build42) {
        switch (feature) {
            case QUIET_RUNTIME: return prefs.isQuietRuntime();
            case BUFFERED_STDIO:
                return prefs.getStdioMode() == OptLabPreferences.StdioMode.BUFFERED;
            case SQLITE_ANDROID_NATIVE: return prefs.isSqliteAndroidNative();
            case BOX64_POLICY:
                return prefs.getBox64Policy() != OptLabPreferences.Box64Policy.LEGACY_3_0;
            case SURFACE_GENERATION_ACK:
                return prefs.getSurfaceMode() == OptLabPreferences.SurfaceMode.GEN_ACK;
            case DISPLAY_FPS_HINT:
                return prefs.getDisplayFpsHint() == OptLabPreferences.DisplayFpsHint.NATIVE_REFRESH;
            case INPUT_MUTEX_SAFE:
                return prefs.getInputQueueMode() == OptLabPreferences.InputQueueMode.MUTEX_SAFE;
            case INPUT_ANALOG_FILTER: return prefs.isAnalogFilter();
            case INPUT_COALESCE: return prefs.isInputCoalesce();
            case MOBILEGL_FILE_LOG: return prefs.isMobileGlFileLogEnabled();
            case PACING: return prefs.isMainloopPacing();
            case STREAM_WAKE: return prefs.isStreamWake();
            case STREAM_QUEUE_FAST: return prefs.isStreamQueueFast();
            case STREAM_VELOCITY_ETA: return prefs.isStreamVelocityEta();
            case FBO_DIRTY_DEDUP: return prefs.isFboDirtyDedup();
            case FBO_FRAME_BUDGET: return prefs.isFboFrameBudget();
            case STREAM_FBO_COORDINATOR: return prefs.isStreamFboCoordinator();
            case LIGHTING64_NATIVE: return build42 && nativePreferences.isLighting64Enabled();
            case PZCLIPPER_NATIVE: return build42 && nativePreferences.isPzClipperEnabled();
            case PATHFINDING_NATIVE: return build42 && nativePreferences.isPathfindingEnabled();
            case POPMAN_NATIVE: return false;
            default:
                throw new AssertionError(feature);
        }
    }

    private static String detectVersionHint(GameInstance gameInstance) {
        File jar = new File(gameInstance.getGamePath(), "projectzomboid.jar");
        if (jar.isFile()) {
            try (JarFile jarFile = new JarFile(jar, false)) {
                Manifest manifest = jarFile.getManifest();
                if (manifest != null) {
                    Attributes attributes = manifest.getMainAttributes();
                    String[] keys = {"Project-Zomboid-Version", "Implementation-Version",
                            "Specification-Version", "Bundle-Version"};
                    for (String key : keys) {
                        String value = attributes.getValue(key);
                        if (value != null && !value.trim().isEmpty()) {
                            return value.trim();
                        }
                    }
                }
            } catch (IOException ignored) {
                // The class-level agent probe remains authoritative when metadata is unavailable.
            }
        }
        if (gameInstance.isBuild4220Plus()) return "42.20+_layout";
        String preset = gameInstance.getPresetName();
        return preset == null || preset.trim().isEmpty()
                ? gameInstance.getBuildVersion() : preset.trim();
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }

    private static String propertyValue(String value) {
        return value == null || value.isEmpty() ? "UNKNOWN" : value.replace(' ', '_');
    }

    private static String token(String value) {
        if (value == null || value.trim().isEmpty()) return "UNKNOWN";
        return value.trim().replace('\n', '_').replace('\r', '_').replace(' ', '_');
    }
}
