package com.zomdroid.agent.optimization;

import zombie.GameProfiler;

/**
 * Low-overhead production runtime for the CP6.3-CP6.13.4 render hotpaths.
 *
 * <p>The device-validation pack intentionally collected detailed counters. The integrated APK
 * keeps only bounded first-hit proof and per-thread cache state: no atomics, reflection, periodic
 * logging or allocation on the steady-state hot paths.</p>
 */
public final class HotPathOptimizationRuntime {
    private static final Object SHADER_EPOCH_LOCK = new Object();
    private static final ThreadLocal<ShaderLookupCache> SHADER_LOOKUP =
            ThreadLocal.withInitial(ShaderLookupCache::new);

    private static volatile boolean shaderLookupEnabled;
    private static volatile boolean mvpEnabled;
    private static volatile boolean stateRunTextureEnabled;
    private static volatile boolean textureBindEnabled;
    private static volatile boolean gameProfilerIdleEnabled;
    private static volatile boolean renderStyleProbeEnabled;
    private static volatile boolean buildLoopEnabled;
    private static volatile boolean extendedProbeEnabled;
    private static volatile long shaderRegistryEpoch = 1L;

    private static boolean shaderProof;
    private static boolean mvpProof;
    private static boolean stateRunProof;
    private static boolean textureBindProof;
    private static boolean gameProfilerProof;
    private static boolean renderStyleProof;
    private static boolean buildLoopProof;
    private static boolean extendedProbeProof;

    private HotPathOptimizationRuntime() {}

    public static void configure(boolean shaderLookup, boolean mvp,
                                 boolean stateRunTexture, boolean textureBind,
                                 boolean gameProfilerIdle, boolean renderStyleProbe,
                                 boolean buildLoop, boolean extendedProbe) {
        shaderLookupEnabled = shaderLookup;
        mvpEnabled = mvp;
        stateRunTextureEnabled = stateRunTexture;
        textureBindEnabled = textureBind;
        gameProfilerIdleEnabled = gameProfilerIdle;
        renderStyleProbeEnabled = renderStyleProbe;
        buildLoopEnabled = buildLoop;
        extendedProbeEnabled = extendedProbe;
        SHADER_LOOKUP.remove();
        ProofRuntime.state("CP6_6_HOTPATH", "CONFIGURED",
                "shader_lookup=" + bit(shaderLookup)
                        + " mvp=" + bit(mvp)
                        + " staterun_texture=" + bit(stateRunTexture)
                        + " texture_bind=" + bit(textureBind)
                        + " game_profiler_idle=" + bit(gameProfilerIdle)
                        + " render_style_probe=" + bit(renderStyleProbe)
                        + " build_loop=" + bit(buildLoop)
                        + " extended_probes=" + bit(extendedProbe)
                        + " telemetry=bounded_first_hit_only");
    }

    public static boolean isMvpEnabled() { return mvpEnabled; }
    public static boolean isStateRunTextureEnabled() { return stateRunTextureEnabled; }
    public static boolean isTextureBindEnabled() { return textureBindEnabled; }
    public static boolean isBuildLoopEnabled() { return buildLoopEnabled; }

    public static Object cachedShaderProgramById(Object owner, int id) {
        if (!shaderLookupEnabled) return null;
        ShaderLookupCache cache = SHADER_LOOKUP.get();
        long epoch = shaderRegistryEpoch;
        if (cache.owner == owner && cache.id == id && cache.program != null
                && cache.epoch == epoch) {
            if (!shaderProof) {
                shaderProof = true;
                ProofRuntime.appliedOnce("RTHREAD_SHADER_LOOKUP",
                        "cp6_3_last_id_registry_cache_hit");
            }
            return cache.program;
        }
        return null;
    }

    public static void afterShaderProgramLookup(Object owner, int id, Object program) {
        if (!shaderLookupEnabled) return;
        ShaderLookupCache cache = SHADER_LOOKUP.get();
        cache.id = id;
        cache.epoch = shaderRegistryEpoch;
        if (program == null) {
            cache.owner = null;
            cache.program = null;
        } else {
            cache.owner = owner;
            cache.program = program;
        }
    }

    public static void afterShaderRegistryMutation() {
        if (!shaderLookupEnabled) return;
        synchronized (SHADER_EPOCH_LOCK) {
            shaderRegistryEpoch++;
        }
    }

    public static boolean skipIdleGameProfilerArea() {
        if (!gameProfilerIdleEnabled) return false;
        try {
            if (GameProfiler.isRunning()) return false;
            if (!gameProfilerProof) {
                gameProfilerProof = true;
                ProofRuntime.appliedOnce("RTHREAD_GAME_PROFILER_IDLE",
                        "cp6_6_profile_string_idle_null_fastpath");
            }
            return true;
        } catch (Throwable error) {
            disableGameProfiler("runtime_guard=" + error.getClass().getSimpleName());
            return false;
        }
    }

    public static boolean skipIdleProbe(String name) {
        if (name == null) return false;
        boolean renderStyle = name == "Render Style" || "Render Style".equals(name);
        boolean extended = !renderStyle && isExtendedProbe(name);
        if ((!renderStyle || !renderStyleProbeEnabled)
                && (!extended || !extendedProbeEnabled)) return false;
        try {
            if (GameProfiler.isRunning()) return false;
            if (renderStyle && !renderStyleProof) {
                renderStyleProof = true;
                ProofRuntime.appliedOnce("RTHREAD_RENDER_STYLE_PROBE",
                        "cp6_6_idle_render_style_probe_elided");
            } else if (extended && !extendedProbeProof) {
                extendedProbeProof = true;
                ProofRuntime.appliedOnce("RTHREAD_EXTENDED_PROBES",
                        "cp6_6_idle_extended_probe_whitelist_hit");
            }
            return true;
        } catch (Throwable error) {
            if (renderStyle) disableRenderStyleProbe(
                    "runtime_guard=" + error.getClass().getSimpleName());
            else disableExtendedProbes("runtime_guard=" + error.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean isExtendedProbe(String name) {
        return name == "buildStateDrawBuffer" || name == "buildStateUIDrawBuffer(UI)"
                || name == "IsoWorld.render" || name == "IsoCell.render"
                || name == "IsoCell.renderTiles" || name == "IsoCell.doBuilding"
                || name == "IsoWorld.update" || name == "IsoCell.update"
                || name == "WorldSimulation.update"
                || "buildStateDrawBuffer".equals(name)
                || "buildStateUIDrawBuffer(UI)".equals(name)
                || "IsoWorld.render".equals(name) || "IsoCell.render".equals(name)
                || "IsoCell.renderTiles".equals(name) || "IsoCell.doBuilding".equals(name)
                || "IsoWorld.update".equals(name) || "IsoCell.update".equals(name)
                || "WorldSimulation.update".equals(name);
    }

    public static void noteMvpSkip() {
        if (!mvpProof) {
            mvpProof = true;
            ProofRuntime.appliedOnce("RTHREAD_MVP",
                    "cp6_3_unchanged_mvp_uniform_lookup_and_copy_elided");
        }
    }

    public static void noteStateRunSaved() {
        if (!stateRunProof) {
            stateRunProof = true;
            ProofRuntime.appliedOnce("RTHREAD_STATERUN_TEXTURE",
                    "cp6_4_same_live_gpu_texture_wrapper_run_preserved");
        }
    }

    public static void noteTextureBindSkip() {
        if (!textureBindProof) {
            textureBindProof = true;
            ProofRuntime.appliedOnce("RTHREAD_TEXTURE_BIND",
                    "cp6_6_redundant_safe_texture_bind_elided");
        }
    }

    public static void noteBuildLoopHandled() {
        if (!buildLoopProof) {
            buildLoopProof = true;
            ProofRuntime.appliedOnce("RTHREAD_BUILD_LOOP",
                    "cp6_13_4_direct_scalar_build_draw_buffer");
        }
    }

    public static void disableShaderLookup(String reason) {
        shaderLookupEnabled = false;
        FeatureCompatibility.fallback("RTHREAD_SHADER_LOOKUP", reason);
    }

    public static void disableMvp(String reason) {
        mvpEnabled = false;
        FeatureCompatibility.fallback("RTHREAD_MVP", reason);
    }

    public static void disableStateRunTexture(String reason) {
        stateRunTextureEnabled = false;
        FeatureCompatibility.fallback("RTHREAD_STATERUN_TEXTURE", reason);
    }

    public static void disableTextureBind(String reason) {
        textureBindEnabled = false;
        FeatureCompatibility.fallback("RTHREAD_TEXTURE_BIND", reason);
    }

    public static void disableGameProfiler(String reason) {
        gameProfilerIdleEnabled = false;
        FeatureCompatibility.fallback("RTHREAD_GAME_PROFILER_IDLE", reason);
    }

    public static void disableRenderStyleProbe(String reason) {
        renderStyleProbeEnabled = false;
        FeatureCompatibility.fallback("RTHREAD_RENDER_STYLE_PROBE", reason);
    }

    public static void disableBuildLoop(String reason) {
        buildLoopEnabled = false;
        FeatureCompatibility.fallback("RTHREAD_BUILD_LOOP", reason);
    }

    public static void disableExtendedProbes(String reason) {
        extendedProbeEnabled = false;
        FeatureCompatibility.fallback("RTHREAD_EXTENDED_PROBES", reason);
    }

    private static int bit(boolean value) { return value ? 1 : 0; }

    private static final class ShaderLookupCache {
        Object owner;
        Object program;
        int id;
        long epoch;
    }
}
