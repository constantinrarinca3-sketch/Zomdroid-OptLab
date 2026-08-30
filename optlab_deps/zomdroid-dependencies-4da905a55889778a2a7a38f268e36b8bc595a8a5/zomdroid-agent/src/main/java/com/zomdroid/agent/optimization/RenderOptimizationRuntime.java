package com.zomdroid.agent.optimization;

import gnu.trove.map.hash.TObjectIntHashMap;
import org.lwjgl.opengl.GL20;
import zombie.core.DefaultShader;
import zombie.core.opengl.ShaderProgram;

/**
 * Direct APK port of the CP6.1/CP6.2 render-thread optimizations.
 *
 * <p>The hot path keeps its state in a ThreadLocal: no per-call atomics, locks, reflection or
 * allocation. A shader compile advances a rare global epoch only after the vanilla compile hook
 * returns successfully. Any linkage/shape failure disables only ChunkDepth and returns control to
 * the original PZ method.</p>
 */
public final class RenderOptimizationRuntime {
    private static final Object COMPILE_LOCK = new Object();
    private static final ThreadLocal<ChunkDepthCache> CHUNK_DEPTH =
            ThreadLocal.withInitial(ChunkDepthCache::new);

    private static volatile boolean chunkDepthEnabled;
    private static volatile boolean chunkDepthLookupEnabled;
    private static volatile boolean ringRenderClearEnabled;
    private static volatile boolean chunkDepthFailed;
    private static volatile long compileEpoch = 1L;

    // Render-thread counters are deliberately plain longs. They are diagnostic snapshots, not
    // synchronization primitives, and therefore do not add CAS traffic to the hot path.
    private static long ringClearSkipped;
    private static long ringClearExecuted;

    private RenderOptimizationRuntime() {}

    public static void configure(boolean chunkDepth, boolean lookup, boolean ringRenderClear) {
        chunkDepthEnabled = chunkDepth;
        chunkDepthLookupEnabled = chunkDepth && lookup;
        ringRenderClearEnabled = ringRenderClear;
        chunkDepthFailed = false;
        CHUNK_DEPTH.remove();
        ProofRuntime.state("CP6_2_RENDER", "CONFIGURED",
                "chunk_depth=" + bit(chunkDepthEnabled)
                        + " lookup=" + bit(chunkDepthLookupEnabled)
                        + " ring_empty_clear=" + bit(ringRenderClearEnabled)
                        + " ring_bulk_pack=0_experimental_not_integrated");
    }

    public static void disableChunkDepth(String reason) {
        chunkDepthEnabled = false;
        chunkDepthLookupEnabled = false;
        FeatureCompatibility.fallback("RTHREAD_CHUNK_DEPTH_UPLOAD", reason);
        FeatureCompatibility.fallback("RTHREAD_CHUNK_DEPTH_LOOKUP", reason);
    }

    public static void disableChunkDepthLookup(String reason) {
        chunkDepthLookupEnabled = false;
        FeatureCompatibility.fallback("RTHREAD_CHUNK_DEPTH_LOOKUP", reason);
    }

    public static void disableRingRenderClear(String reason) {
        ringRenderClearEnabled = false;
        FeatureCompatibility.fallback("RTHREAD_RING_RENDER_CLEAR", reason);
    }

    /**
     * Returns true only when this method fully replaces DefaultShader.setChunkDepth(float).
     * False means Byte Buddy must execute the untouched PZ implementation.
     */
    public static boolean handleChunkDepth(Object receiver, float value) {
        if (!chunkDepthEnabled || chunkDepthFailed) return false;

        ChunkDepthCache cache = CHUNK_DEPTH.get();
        cache.calls++;
        try {
            DefaultShader shader = (DefaultShader) receiver;
            ShaderProgram program = shader.getProgram();
            if (program == null) return false; // Preserve vanilla NPE/failure behavior.

            long epoch = compileEpoch;
            boolean generationValid = cache.program == program && cache.epoch == epoch;
            ShaderProgram.Uniform uniform;
            if (chunkDepthLookupEnabled && generationValid && cache.uniform != null) {
                uniform = cache.uniform;
                cache.lookupSkips++;
                ProofRuntime.appliedOnce("RTHREAD_CHUNK_DEPTH_LOOKUP",
                        "cp6_2_program_and_compile_epoch_cache_hit");
            } else {
                uniform = program.getUniform("chunkDepth", 5126); // GL_FLOAT
                cache.uniformLookups++;
                cache.program = program;
                cache.epoch = epoch;
                cache.uniform = uniform;
            }

            // ShaderProgram.setValue(String,float) is a no-op for an absent uniform.
            if (uniform == null) return true;

            int bits = Float.floatToRawIntBits(value);
            if (generationValid && cache.lastUploadedUniform == uniform
                    && cache.bitsValid && cache.bits == bits) {
                cache.uploadSkips++;
                ProofRuntime.appliedOnce("RTHREAD_CHUNK_DEPTH_UPLOAD",
                        "cp6_1_redundant_gluniform1f_elided");
                return true;
            }

            // Commit cache state only after the GL call succeeds.
            GL20.glUniform1f(uniform.loc, value);
            cache.uploads++;
            cache.program = program;
            cache.epoch = epoch;
            cache.uniform = uniform;
            cache.lastUploadedUniform = uniform;
            cache.bits = bits;
            cache.bitsValid = true;
            return true;
        } catch (Throwable error) {
            chunkDepthFailed = true;
            String reason = "runtime_guard=" + error.getClass().getSimpleName();
            disableChunkDepth(reason);
            ProofRuntime.state("RTHREAD_CHUNK_DEPTH", "FALLBACK",
                    "original_game_code " + reason);
            return false;
        }
    }

    /** Called by Advice only after DefaultShader.onCompileSuccess completed without throwing. */
    public static void afterDefaultShaderCompile() {
        if (!chunkDepthEnabled) return;
        synchronized (COMPILE_LOCK) {
            compileEpoch++;
        }
        ProofRuntime.appliedOnce("RTHREAD_CHUNK_DEPTH_INVALIDATION",
                "successful_shader_compile_epoch_advanced");
    }

    /**
     * Replaces only Model.modelDrawCounts.clear() at the tail of RingBuffer.render(). The rest of
     * the game method remains byte-for-byte controlled by PZ.
     */
    public static void clearModelDrawCountsIfNotEmpty(TObjectIntHashMap<?> map) {
        if (!ringRenderClearEnabled) {
            map.clear();
            return;
        }
        if (map.isEmpty()) {
            ringClearSkipped++;
            ProofRuntime.appliedOnce("RTHREAD_RING_RENDER_CLEAR",
                    "cp6_2_empty_model_draw_counts_clear_elided");
        } else {
            map.clear();
            ringClearExecuted++;
        }
    }

    public static String diagnosticSnapshot() {
        ChunkDepthCache cache = CHUNK_DEPTH.get();
        return "chunk_calls=" + cache.calls
                + " chunk_lookups=" + cache.uniformLookups
                + " chunk_lookup_skips=" + cache.lookupSkips
                + " chunk_uploads=" + cache.uploads
                + " chunk_upload_skips=" + cache.uploadSkips
                + " compile_epoch=" + compileEpoch
                + " ring_clear_skipped=" + ringClearSkipped
                + " ring_clear_executed=" + ringClearExecuted;
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }

    private static final class ChunkDepthCache {
        ShaderProgram program;
        ShaderProgram.Uniform uniform;
        ShaderProgram.Uniform lastUploadedUniform;
        long epoch;
        int bits;
        boolean bitsValid;
        long calls;
        long uniformLookups;
        long lookupSkips;
        long uploads;
        long uploadSkips;
    }
}
