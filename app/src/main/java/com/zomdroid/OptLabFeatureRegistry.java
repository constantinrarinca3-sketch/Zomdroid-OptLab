package com.zomdroid;

import android.util.Log;

import androidx.annotation.NonNull;

import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.EnumMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
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
        NATIVE,
        EXPERIMENTAL
    }

    public enum Compatibility {
        VERIFIED,
        COMPATIBLE,
        PROBE,
        UNSUPPORTED
    }

    public enum Module {
        GENERAL_RUNTIME("Runtime / JVM"),
        DISPLAY_INPUT("Display / Input"),
        MAIN_LOOP_PACING("Main Loop & Pacing"),
        WORLD_STREAM_CHUNK("World Stream & Chunk"),
        FBO_RENDER_CELL("FBO & Render Cell"),
        RENDER_HOTPATH("Render Hotpath"),
        MODEL_RINGBUFFER("Model & RingBuffer"),
        SHADER_UNIFORMS("Shader & Uniforms"),
        MEMORY_ALLOCATION("Memory / Allocation"),
        NATIVE_ARM64("Native ARM64"),
        EXPERIMENTAL("Experimental");

        public final String label;
        Module(String label) { this.label = label; }
        @Override public String toString() { return label; }
    }

    public enum Maturity {
        STABLE,
        EXPERIMENTAL,
        INTERNAL,
        ARCHIVED
    }

    public enum Validation {
        HOST_VALIDATED,
        DEVICE_POC_VALIDATED,
        DEVICE_VALIDATED,
        NEEDS_VALIDATION,
        REJECTED
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

        PACING("PACING", Module.MAIN_LOOP_PACING, Maturity.EXPERIMENTAL,
                Validation.HOST_VALIDATED, false, false, false, true, true,
                "mainloop_pacing", "zomdroid.optlab.pacing", "Main-loop pacing",
                "Original frame pacing remains active on fallback"),
        STREAM_WAKE("STREAM_WAKE", Module.WORLD_STREAM_CHUNK, Maturity.STABLE,
                Validation.DEVICE_VALIDATED, false, false, true, true, true,
                "stream_wake", "zomdroid.optlab.stream.wake", "Unparkable stream wait",
                "Original WorldStreamer sleep remains active"),
        STREAM_QUEUE_FAST("STREAM_QUEUE_FAST", Module.WORLD_STREAM_CHUNK, Maturity.STABLE,
                Validation.HOST_VALIDATED, false, false, true, true, true,
                "stream_queue_fast", "zomdroid.optlab.stream.queue.fast",
                "Stable queue selection", "Original stable sort remains active"),
        STREAM_VELOCITY_ETA("STREAM_VELOCITY_ETA", Module.WORLD_STREAM_CHUNK,
                Maturity.EXPERIMENTAL, Validation.NEEDS_VALIDATION,
                false, false, false, true, true, "stream_velocity_eta",
                "zomdroid.optlab.stream.velocity.eta", "Velocity look-ahead",
                "Original comparator remains active"),
        FBO_DIRTY_DEDUP("FBO_DIRTY_DEDUP", Module.FBO_RENDER_CELL, Maturity.STABLE,
                Validation.HOST_VALIDATED, false, true, true, true, true,
                "fbo_dirty_dedup", "zomdroid.optlab.fbo.dirty.dedup",
                "FBO dirty dedup", "Original dirty-bit update remains active"),
        FBO_INNER_LOOP("FBO_INNER_LOOP", Module.FBO_RENDER_CELL,
                Maturity.EXPERIMENTAL, Validation.DEVICE_POC_VALIDATED,
                false, false, false, false, true, "fbo_inner_loop",
                "zomdroid.optlab.fbo.inner.loop", "FBO lighting inner-loop reuse",
                "Vanilla prepareChunkForUpdating remains active"),
        CHUNK_FORAGING("CHUNK_FORAGING", Module.WORLD_STREAM_CHUNK,
                Maturity.EXPERIMENTAL, Validation.NEEDS_VALIDATION,
                false, false, false, true, true, "chunk_forage",
                "zomdroid.optlab.chunk.forage", "Foraging generation",
                "Vanilla generation remains active"),
        CHUNK_NEIGHBOUR_WORKER("CHUNK_NEIGHBOUR_WORKER", Module.WORLD_STREAM_CHUNK,
                Maturity.STABLE, Validation.DEVICE_VALIDATED,
                false, false, true, true, true, "chunk_neighbour_worker",
                "zomdroid.optlab.chunk.neighbour.worker", "Neighbour worker",
                "Vanilla neighbour recalculation remains active"),
        CHUNK_NEIGHBOUR_MAIN("CHUNK_NEIGHBOUR_MAIN", Module.WORLD_STREAM_CHUNK,
                Maturity.STABLE, Validation.DEVICE_VALIDATED,
                false, false, true, true, true, "chunk_neighbour_main",
                "zomdroid.optlab.chunk.neighbour.main", "Neighbour main thread",
                "Vanilla main-thread recalculation remains active"),
        CHUNK_GRID_LOAD("CHUNK_GRID_LOAD", Module.WORLD_STREAM_CHUNK,
                Maturity.EXPERIMENTAL, Validation.NEEDS_VALIDATION,
                false, false, false, true, true, "chunk_grid_load",
                "zomdroid.optlab.chunk.grid.load", "Grid load fastpath",
                "Vanilla grid load remains active"),
        CHUNK_VEHICLE_INDEX("CHUNK_VEHICLE_INDEX", Module.WORLD_STREAM_CHUNK,
                Maturity.EXPERIMENTAL, Validation.NEEDS_VALIDATION,
                false, false, false, true, true, "chunk_vehicles",
                "zomdroid.optlab.chunk.vehicles", "Vehicle zone index",
                "Vanilla vehicle scan remains active"),
        CHUNK_RANDOMIZED_BUILDINGS("CHUNK_RANDOMIZED_BUILDINGS", Module.WORLD_STREAM_CHUNK,
                Maturity.EXPERIMENTAL, Validation.NEEDS_VALIDATION,
                false, false, false, true, true, "chunk_randomized_buildings",
                "zomdroid.optlab.chunk.randomized.buildings", "Randomized buildings",
                "Vanilla building randomization remains active"),
        CHUNK_LUA_MAPOBJECTS("CHUNK_LUA_MAPOBJECTS", Module.WORLD_STREAM_CHUNK,
                Maturity.EXPERIMENTAL, Validation.NEEDS_VALIDATION,
                false, false, false, true, true, "chunk_lua_mapobjects",
                "zomdroid.optlab.chunk.lua.mapobjects", "Lua map objects",
                "Vanilla Lua callback remains active"),
        CHUNK_WORLDGEN_BIOME("CHUNK_WORLDGEN_BIOME", Module.WORLD_STREAM_CHUNK,
                Maturity.EXPERIMENTAL, Validation.NEEDS_VALIDATION,
                false, false, false, true, true, "chunk_worldgen_biome",
                "zomdroid.optlab.chunk.worldgen.biome", "Worldgen biome lookup",
                "Vanilla biome lookup remains active"),
        RTHREAD_CHUNK_DEPTH_UPLOAD("RTHREAD_CHUNK_DEPTH_UPLOAD", Module.SHADER_UNIFORMS,
                Maturity.INTERNAL, Validation.DEVICE_VALIDATED,
                false, true, true, true, false, "render_chunk_depth_upload",
                "zomdroid.optlab.render.chunk.depth.upload", "Chunk-depth upload cache",
                "Vanilla ShaderProgram.setValue remains active"),
        RTHREAD_CHUNK_DEPTH_LOOKUP("RTHREAD_CHUNK_DEPTH_LOOKUP", Module.SHADER_UNIFORMS,
                Maturity.INTERNAL, Validation.DEVICE_VALIDATED,
                false, true, true, true, false, "render_chunk_depth_lookup",
                "zomdroid.optlab.render.chunk.depth.lookup", "Chunk-depth uniform cache",
                "Vanilla uniform lookup remains active", RTHREAD_CHUNK_DEPTH_UPLOAD),
        RTHREAD_RING_RENDER_CLEAR("RTHREAD_RING_RENDER_CLEAR", Module.MODEL_RINGBUFFER,
                Maturity.INTERNAL, Validation.DEVICE_VALIDATED,
                false, true, true, true, false, "render_ring_empty_clear",
                "zomdroid.optlab.render.ring.empty.clear", "Empty model-map clear",
                "Vanilla map clear remains active"),
        RTHREAD_SHADER_LOOKUP("RTHREAD_SHADER_LOOKUP", Module.SHADER_UNIFORMS,
                Maturity.INTERNAL, Validation.DEVICE_POC_VALIDATED,
                false, true, true, true, false, "render_shader_lookup",
                "zomdroid.optlab.render.shader.lookup", "Shader registry last-ID cache",
                "Vanilla registry lookup remains active"),
        RTHREAD_MVP("RTHREAD_MVP", Module.SHADER_UNIFORMS, Maturity.INTERNAL,
                Validation.DEVICE_POC_VALIDATED, false, true, true, true, false,
                "render_mvp", "zomdroid.optlab.render.mvp", "MVP unchanged fastpath",
                "Vanilla MVP method remains active"),
        RTHREAD_STATERUN_TEXTURE("RTHREAD_STATERUN_TEXTURE", Module.MODEL_RINGBUFFER,
                Maturity.STABLE, Validation.DEVICE_POC_VALIDATED,
                false, false, true, true, true, "render_staterun_texture",
                "zomdroid.optlab.render.staterun.texture", "StateRun texture equivalence",
                "Vanilla wrapper-identity boundary remains active"),
        RTHREAD_TEXTURE_BIND("RTHREAD_TEXTURE_BIND", Module.RENDER_HOTPATH,
                Maturity.STABLE, Validation.DEVICE_POC_VALIDATED,
                false, false, true, true, true, "render_texture_bind",
                "zomdroid.optlab.render.texture.bind", "Safe redundant texture bind",
                "Vanilla Texture.bind remains active"),
        RTHREAD_GAME_PROFILER_IDLE("RTHREAD_GAME_PROFILER_IDLE", Module.RENDER_HOTPATH,
                Maturity.INTERNAL, Validation.DEVICE_POC_VALIDATED,
                false, true, true, true, false, "render_profiler_idle",
                "zomdroid.optlab.render.profiler.idle", "Idle GameProfiler fastpath",
                "Vanilla profiling path remains active"),
        RTHREAD_RENDER_STYLE_PROBE("RTHREAD_RENDER_STYLE_PROBE", Module.RENDER_HOTPATH,
                Maturity.INTERNAL, Validation.DEVICE_POC_VALIDATED,
                false, true, true, true, false, "render_probe_style_idle",
                "zomdroid.optlab.render.probe.style.idle", "Idle Render Style probe",
                "Vanilla performance probe remains active"),
        RTHREAD_BUILD_LOOP("RTHREAD_BUILD_LOOP", Module.MODEL_RINGBUFFER,
                Maturity.EXPERIMENTAL, Validation.DEVICE_POC_VALIDATED,
                false, false, false, true, true, "render_build_loop",
                "zomdroid.optlab.render.build.loop", "Direct buildDrawBuffer fastpath",
                "Vanilla RingBuffer.add loop remains active"),
        RTHREAD_SAME_PROGRAM_BIND("RTHREAD_SAME_PROGRAM_BIND", Module.SHADER_UNIFORMS,
                Maturity.EXPERIMENTAL, Validation.DEVICE_POC_VALIDATED,
                false, false, false, false, true, "render_same_program_bind",
                "zomdroid.optlab.render.same.program.bind", "Same-program bind fastpath",
                "Vanilla ShaderHelper bind and debug checks remain active"),
        RTHREAD_EXTENDED_PROBES("RTHREAD_EXTENDED_PROBES", Module.RENDER_HOTPATH,
                Maturity.EXPERIMENTAL, Validation.DEVICE_POC_VALIDATED,
                false, false, false, true, true, "render_probe_extended_idle",
                "zomdroid.optlab.render.probe.extended.idle", "Extended idle probe whitelist",
                "Vanilla performance probes remain active"),
        RTHREAD_RING_BULK_PACK("RTHREAD_RING_BULK_PACK", Module.MODEL_RINGBUFFER,
                Maturity.ARCHIVED, Validation.REJECTED, false, false, false, false,
                false, null, null, "RingBuffer bulk packing", "Archived; never launched"),
        RTHREAD_PROFILE_FAST("RTHREAD_PROFILE_FAST", Module.RENDER_HOTPATH,
                Maturity.ARCHIVED, Validation.REJECTED, false, false, false, false,
                false, null, null, "CP6.0 profile fastpath", "Archived; never launched"),
        RTHREAD_TEX_PARAMETER_CACHE("RTHREAD_TEX_PARAMETER_CACHE", Module.RENDER_HOTPATH,
                Maturity.ARCHIVED, Validation.REJECTED, false, false, false, false,
                false, null, null, "glTexParameteri cache", "Archived; never launched"),
        RTHREAD_MATRIX_STACK_EPOCH("RTHREAD_MATRIX_STACK_EPOCH", Module.SHADER_UNIFORMS,
                Maturity.ARCHIVED, Validation.REJECTED, false, false, false, false,
                false, null, null, "MatrixStack epoch", "Archived; never launched"),
        RTHREAD_STATERUN_AGGRESSIVE("RTHREAD_STATERUN_AGGRESSIVE", Module.MODEL_RINGBUFFER,
                Maturity.ARCHIVED, Validation.REJECTED, false, false, false, false,
                false, null, null, "Aggressive StateRun merge", "Archived; never launched"),
        CHUNK_CP2C_DIRTY_CLEAR("CHUNK_CP2C_DIRTY_CLEAR", Category.EXPERIMENTAL,
                Module.WORLD_STREAM_CHUNK, Maturity.EXPERIMENTAL,
                Validation.NEEDS_VALIDATION,
                false, false, false, false, true, "chunk_cp2c_dirty_clear",
                "zomdroid.optlab.chunk.cp2c.dirty.clear", "CP2C dirty clear",
                "Vanilla dirty clear remains active"),

        LIGHTING64_NATIVE("LIGHTING64_NATIVE", Category.NATIVE, Module.NATIVE_ARM64,
                Maturity.EXPERIMENTAL, Validation.DEVICE_POC_VALIDATED, false, true),
        PZCLIPPER_NATIVE("PZCLIPPER_NATIVE", Category.NATIVE, Module.NATIVE_ARM64,
                Maturity.STABLE, Validation.DEVICE_VALIDATED, true, true),
        PATHFINDING_NATIVE("PATHFINDING_NATIVE", Category.NATIVE, Module.NATIVE_ARM64,
                Maturity.EXPERIMENTAL, Validation.NEEDS_VALIDATION, false, true),
        POPMAN_NATIVE("POPMAN_NATIVE", Category.NATIVE, Module.NATIVE_ARM64,
                Maturity.EXPERIMENTAL, Validation.NEEDS_VALIDATION, false, true);

        public final String id;
        public final Category category;
        public final Module module;
        public final Maturity maturity;
        public final Validation validation;
        public final boolean defaultEnabled;
        public final boolean safeProfile;
        public final boolean recommendedProfile;
        public final boolean aggressiveProfile;
        public final boolean advancedControl;
        public final boolean restartRequired;
        public final String preferenceKey;
        public final String agentProperty;
        public final String title;
        public final String fallback;
        public final List<Feature> dependencies;

        Feature(String id, Category category, boolean defaultEnabled,
                boolean restartRequired) {
            this.id = id;
            this.category = category;
            this.module = category == Category.NATIVE ? Module.NATIVE_ARM64
                    : (category == Category.EXPERIMENTAL ? Module.EXPERIMENTAL
                    : (category == Category.GENERAL ? Module.GENERAL_RUNTIME
                    : Module.WORLD_STREAM_CHUNK));
            this.maturity = category == Category.EXPERIMENTAL
                    ? Maturity.EXPERIMENTAL : Maturity.STABLE;
            this.validation = category == Category.NATIVE
                    ? Validation.DEVICE_VALIDATED : Validation.HOST_VALIDATED;
            this.defaultEnabled = defaultEnabled;
            this.safeProfile = defaultEnabled;
            this.recommendedProfile = defaultEnabled;
            this.aggressiveProfile = defaultEnabled;
            this.advancedControl = true;
            this.restartRequired = restartRequired;
            this.preferenceKey = null;
            this.agentProperty = null;
            this.title = id;
            this.fallback = "Feature-specific fallback";
            this.dependencies = Collections.emptyList();
        }

        Feature(String id, Category category, Module module, Maturity maturity,
                Validation validation, boolean defaultEnabled, boolean restartRequired) {
            this.id = id;
            this.category = category;
            this.module = module;
            this.maturity = maturity;
            this.validation = validation;
            this.defaultEnabled = defaultEnabled;
            this.safeProfile = defaultEnabled;
            this.recommendedProfile = defaultEnabled;
            this.aggressiveProfile = defaultEnabled;
            this.advancedControl = true;
            this.restartRequired = restartRequired;
            this.preferenceKey = null;
            this.agentProperty = null;
            this.title = id;
            this.fallback = "Feature-specific fallback";
            this.dependencies = Collections.emptyList();
        }

        Feature(String id, Module module, Maturity maturity, Validation validation,
                boolean defaultEnabled, boolean safeProfile, boolean recommendedProfile,
                boolean aggressiveProfile, boolean advancedControl, String preferenceKey,
                String agentProperty, String title, String fallback, Feature... dependencies) {
            this(id, Category.BUILD42, module, maturity, validation, defaultEnabled,
                    safeProfile, recommendedProfile, aggressiveProfile, advancedControl,
                    preferenceKey, agentProperty, title, fallback, dependencies);
        }

        Feature(String id, Category category, Module module, Maturity maturity,
                Validation validation, boolean defaultEnabled, boolean safeProfile,
                boolean recommendedProfile, boolean aggressiveProfile,
                boolean advancedControl, String preferenceKey, String agentProperty,
                String title, String fallback, Feature... dependencies) {
            this.id = id;
            this.category = category;
            this.module = module;
            this.maturity = maturity;
            this.validation = validation;
            this.defaultEnabled = defaultEnabled;
            // "Safe" is a real Build 42 ALL-OFF profile.  It must never arm a bytecode
            // transformer merely because that transformer is internal or recommended.
            this.safeProfile = category == Category.BUILD42 ? false : safeProfile;
            this.recommendedProfile = recommendedProfile;
            this.aggressiveProfile = aggressiveProfile;
            this.advancedControl = advancedControl;
            this.restartRequired = true;
            this.preferenceKey = preferenceKey;
            this.agentProperty = agentProperty;
            this.title = title;
            this.fallback = fallback;
            this.dependencies = Collections.unmodifiableList(Arrays.asList(dependencies));
        }

        public boolean isVisible() {
            return maturity != Maturity.ARCHIVED;
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
                    + " module=" + feature.module.name()
                    + " maturity=" + feature.maturity
                    + " validation=" + feature.validation
                    + " requested=" + bit(requested)
                    + " compatibility=" + compatibility
                    + " restartRequired=" + bit(feature.restartRequired)
                    + " defaultEnabled=" + bit(feature.defaultEnabled)
                    + " safeProfile=" + bit(feature.safeProfile)
                    + " recommendedProfile=" + bit(feature.recommendedProfile)
                    + " aggressiveProfile=" + bit(feature.aggressiveProfile)
                    + " advancedControl=" + bit(feature.advancedControl)
                    + " agentProperty=" + token(feature.agentProperty)
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
            OptLabLaunchContract.putProperty(jvmArgs, "zomdroid.optlab.pz.build.family",
                    propertyValue(buildFamily));
            OptLabLaunchContract.putProperty(jvmArgs, "zomdroid.optlab.pz.version.hint",
                    propertyValue(versionHint));
            OptLabLaunchContract.putProperty(jvmArgs, "zomdroid.optlab.jar.sha256",
                    propertyValue(jarSha256));
            OptLabLaunchContract.putProperty(jvmArgs, "zomdroid.optlab.jar.known",
                    Integer.toString(bit(knownJar)));
            OptLabLaunchContract.putProperty(jvmArgs, "zomdroid.optlab.only.build42",
                    Integer.toString(bit(onlyBuild42)));
        }

        /** Emits every feature property from the registry; no launcher-side duplicate table. */
        public void addAgentFeatureProperties(@NonNull java.util.List<String> jvmArgs) {
            for (Feature feature : Feature.values()) {
                if (feature.agentProperty == null) continue;
                Entry entry = get(feature);
                boolean enabled = entry.requested
                        && entry.compatibility != Compatibility.UNSUPPORTED;
                OptLabLaunchContract.putProperty(jvmArgs, feature.agentProperty,
                        Integer.toString(bit(enabled)));
            }
        }

        /** Confirms the exact requested snapshot immediately before JNI starts the game JVM. */
        public void requireAgentFeatureProperties(
                @NonNull java.util.List<String> jvmArgs) {
            for (Feature feature : Feature.values()) {
                if (feature.agentProperty == null) continue;
                Entry entry = get(feature);
                boolean enabled = entry.requested
                        && entry.compatibility != Compatibility.UNSUPPORTED;
                OptLabLaunchContract.requireValue(jvmArgs, feature.agentProperty,
                        Integer.toString(bit(enabled)));
            }
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
                                    boolean pathfindingActive,
                                    boolean popManActive) {
        String family = token(gameInstance.getBuildVersion());
        String version = detectVersionHint(gameInstance);
        boolean build42 = "42".equals(family);
        boolean knownJar = OptLabPreferences.isKnownPzJarSha256(jarSha256);
        boolean onlyBuild42 = preferences.isOnlyBuild42();
        EnumMap<Feature, Entry> entries = new EnumMap<>(Feature.class);

        for (Feature feature : Feature.values()) {
            boolean requested = requested(feature, preferences, nativePreferences, build42);
            Compatibility compatibility;
            String reason;
            Feature missingDependency = null;
            if (requested) {
                for (Feature dependency : feature.dependencies) {
                    if (!requested(dependency, preferences, nativePreferences, build42)) {
                        missingDependency = dependency;
                        break;
                    }
                }
            }
            if (missingDependency != null) {
                entries.put(feature, new Entry(feature, true, Compatibility.UNSUPPORTED,
                        "REQUIRES_" + missingDependency.id));
                continue;
            }
            switch (feature.category) {
                case GENERAL:
                    compatibility = Compatibility.COMPATIBLE;
                    reason = "BUILD_INDEPENDENT";
                    break;
                case BUILD42:
                case EXPERIMENTAL:
                    if (!build42 && onlyBuild42) {
                        compatibility = Compatibility.UNSUPPORTED;
                        reason = "ONLY_BUILD_42_POLICY";
                    } else if (build42 && knownJar) {
                        compatibility = Compatibility.VERIFIED;
                        reason = "KNOWN_PZ_JAR";
                    } else {
                        compatibility = Compatibility.PROBE;
                        reason = build42
                                ? (feature.category == Category.EXPERIMENTAL
                                        ? "EXPERIMENTAL_AGENT_CLASS_PROBE_REQUIRED"
                                        : "AGENT_CLASS_PROBE_REQUIRED")
                                : "CROSS_FAMILY_AGENT_PROBE_REQUIRED";
                    }
                    break;
                case NATIVE:
                    if (!build42) {
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
                            case POPMAN_NATIVE:
                                active = popManActive;
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
        if (feature.category == Category.BUILD42) {
            return prefs.isFeatureEnabled(feature);
        }
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
            case CHUNK_FORAGING: return prefs.isChunkForage();
            case CHUNK_NEIGHBOUR_WORKER: return prefs.isChunkNeighbourWorker();
            case CHUNK_NEIGHBOUR_MAIN: return prefs.isChunkNeighbourMain();
            case CHUNK_GRID_LOAD: return prefs.isChunkGridLoad();
            case CHUNK_VEHICLE_INDEX: return prefs.isChunkVehicles();
            case CHUNK_RANDOMIZED_BUILDINGS: return prefs.isChunkRandomizedBuildings();
            case CHUNK_LUA_MAPOBJECTS: return prefs.isChunkLuaMapObjects();
            case CHUNK_WORLDGEN_BIOME: return prefs.isChunkWorldgenBiome();
            case RTHREAD_CHUNK_DEPTH_UPLOAD: return prefs.isRenderChunkDepthUpload();
            case RTHREAD_CHUNK_DEPTH_LOOKUP: return prefs.isRenderChunkDepthLookup();
            case RTHREAD_RING_RENDER_CLEAR: return prefs.isRenderRingEmptyClear();
            case CHUNK_CP2C_DIRTY_CLEAR: return prefs.isChunkCp2cDirtyClear();
            case LIGHTING64_NATIVE: return build42 && nativePreferences.isLighting64Enabled();
            case PZCLIPPER_NATIVE: return build42 && nativePreferences.isPzClipperEnabled();
            case PATHFINDING_NATIVE: return build42 && nativePreferences.isPathfindingEnabled();
            case POPMAN_NATIVE: return build42 && nativePreferences.isPopManEnabled();
            default:
                throw new AssertionError(feature);
        }
    }

    @NonNull
    public static List<Feature> featuresForModule(@NonNull Module module,
                                                   boolean advanced) {
        ArrayList<Feature> output = new ArrayList<>();
        for (Feature feature : Feature.values()) {
            if (feature.module != module || !feature.isVisible()) continue;
            if (!advanced && (feature.maturity == Maturity.INTERNAL
                    || feature.maturity == Maturity.EXPERIMENTAL)) continue;
            output.add(feature);
        }
        return Collections.unmodifiableList(output);
    }

    public static int count(@NonNull Module module, @NonNull Maturity maturity) {
        int count = 0;
        for (Feature feature : Feature.values()) {
            if (feature.module == module && feature.maturity == maturity
                    && feature.isVisible()) count++;
        }
        return count;
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
