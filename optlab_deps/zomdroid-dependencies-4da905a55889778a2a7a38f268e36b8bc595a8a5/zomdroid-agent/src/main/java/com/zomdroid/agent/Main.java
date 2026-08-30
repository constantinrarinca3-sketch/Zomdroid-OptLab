package com.zomdroid.agent;

import com.zomdroid.agent.decorators.ShaderUnit;
import com.zomdroid.agent.optimization.ChunkOptimizationRuntime;
import com.zomdroid.agent.optimization.FeatureCompatibility;
import com.zomdroid.agent.optimization.FboInnerLoopRuntime;
import com.zomdroid.agent.optimization.FboRuntime;
import com.zomdroid.agent.optimization.HotPathOptimizationRuntime;
import com.zomdroid.agent.optimization.PacingRuntime;
import com.zomdroid.agent.optimization.PathfindingRuntime;
import com.zomdroid.agent.optimization.PopManRuntime;
import com.zomdroid.agent.optimization.ProofRuntime;
import com.zomdroid.agent.optimization.RenderOptimizationRuntime;
import com.zomdroid.agent.optimization.SameProgramBindRuntime;
import com.zomdroid.agent.optimization.StreamCoreRuntime;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.agent.builder.AgentBuilder;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.field.FieldDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.loading.ClassReloadingStrategy;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.implementation.Implementation;
import net.bytebuddy.implementation.bytecode.assign.Assigner;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.matcher.ElementMatcher;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.JavaModule;

import java.io.InputStream;
import java.lang.instrument.Instrumentation;
import java.security.MessageDigest;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.isStatic;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

public final class Main {
    private static final String MAIN_THREAD_SHA256 =
            "c7ee1d1d3026185ad49cd80edbf9ddb6f59c0cd7faf2ced4ebbe50f2dada9c0f";
    private static final String GAME_WINDOW_SHA256 =
            "34c9927f595ecd524e5ed5524ede1b4789c9be462ea1a04a0f5d1b57dd1d5c95";
    private static final String WORLD_STREAMER_SHA256 =
            "c921fec2b95951e372176ce2a00cb461104c74521fcabcc9657d6061e1d8090d";
    private static final String WORLD_COMPARATOR_SHA256 =
            "76d8dabe08d1224be5a9d24542cfe2bb86dab1c7ecc0eb4aafe030d82b3eeeb7";
    private static final String FBO_MANAGER_SHA256 =
            "f94aa259d6a371f4fd22f3e86e48aac47b4c823b0a13ebcccdbb9fa69671c7aa";
    private static final String FBO_NLEVELS_SHA256 =
            "0f78747d927cd4f86ee3be38bd4afdf6220b7666c9b2af2c59d57ff75636056b";
    private static final String ZONE_GENERATOR_SHA256 =
            "10ed8c0fef67cdbf3a0f355f2142bf35e401df82e0578b473206f3baccafba29";
    private static final String BIOME_MAP_SHA256 =
            "88de846d99f7b0d91bc9765e86f8b829b610aef83cb8afc41edcb57127879f07";
    private static final String ISO_CHUNK_4220_SHA256 =
            "2e12c5a39b467423a00b33415ac01c6205bdd19f9e3baa90f355674c420f9c72";
    private static final String ISO_CHUNK_42203_SHA256 =
            "68431ace471b30c842ff7c2a6e706d8ba48d7a84ae07f876484153c0d62a794b";
    private static final String ISO_GRID_SQUARE_4220_SHA256 =
            "552c13fbfda06612be082faeb98b89a65f30832dbae669e0920d8a09888fa86a";
    private static final String ISO_GRID_SQUARE_42203_SHA256 =
            "cf5ef9005829d258f6ef28394a9f514350fa8fa1ea5f6209cc7836a656f0b0eb";
    private static final String LOAD_GRID_WORKAROUND_SHA256 =
            "a4fe7b5b8f0fbc4d1a10deafc46e8dc20e53e1a6b10ba0c83ba6cf3b34190ab4";
    private static final String MAP_OBJECTS_SHA256 =
            "e4b4ccfb0225ba2bb3c4e1313d571235a5fbdef9489a01b9a82526674bc1e715";
    private static final String WORLD_GEN_CHUNK_SHA256 =
            "e64f53da69877398e1715b79df4cefc881fa7571844887569cfce3b4a64daea3";
    private static final String VEHICLE_ZONE_SHA256 =
            "6bd45f0a288b6a179201b2e93d0a8258425b943d00835a29886e65eed18f0bd7";
    // Identical in the exact 42.20 and 42.20.3 jars supplied for this checkpoint.
    private static final String DEFAULT_SHADER_4220_42203_SHA256 =
            "2a4231d4059687d285a6d460eff78643f18a7d596186ba3f6fa64b52e0e04273";
    private static final String SHADER_PROGRAM_4220_42203_SHA256 =
            "062a78360dab7245283099312d88d09c216da5ae5c0b4deb5edd3f4a08afb621";
    private static final String SHADER_UNIFORM_4220_42203_SHA256 =
            "f5f17a0bfe4510a7b9964410facaadc083ff14c0316796e8ec277061aa8595c9";
    private static final String RING_BUFFER_4220_42203_SHA256 =
            "c50a46274331398ba2168a33f9b87d2d164301cc239c238830f6bd8588dfc8c3";
    private static final String MODEL_4220_42203_SHA256 =
            "3dd5e7b618cecf3c27e216a386e07937b1be694a7fc083b1bc2f7c993cbee3d9";
    // CP6.3-CP6.13.4 target classes are byte-identical in the supplied 42.20/42.20.3 jars.
    private static final String SHADER_PROGRAMS_4220_42203_SHA256 =
            "9826b46cacc1bb3608ecbbf9869c20c69ea5ec16a4630e5767048ba7531adb3d";
    private static final String VERTEX_BUFFER_OBJECT_4220_42203_SHA256 =
            "9548415619074b6ebba619c2b5ce546b3f0e0812a5e3b6155c5a0892511b53a1";
    private static final String SPRITE_RENDERER_4220_42203_SHA256 =
            "12735b2809ddab37c96b9614e54d2d38639baa8f342546baedeb8562c617f9be";
    private static final String TEXTURE_4220_42203_SHA256 =
            "842ab8dc207885302ae11aacd5dc1a9d1d76ba2d9ce695978d27a3329234070d";
    private static final String GAME_PROFILER_4220_42203_SHA256 =
            "0667a2e92f487973dbb83f50b63b4168d23e591988eb389310eeff757777ce8c";
    private static final String PERFORMANCE_PROBE_4220_42203_SHA256 =
            "5dc7480fd8b7e06143d4f9129cd809ca1eec191fcaa59f10b94f8a6264ae7405";
    private static final String FBO_RENDER_CELL_4220_42203_SHA256 =
            "11beef2829618190eaad0ae78f61740f94d9c751f0a083cf49f9a16ebe01294b";
    private static final String SHADER_HELPER_4220_42203_SHA256 =
            "0ec33b613b6580759d913dff8b093b7f92fc791b6538327d90a15ac7f97b2d2e";

    private Main() {}

    public static void premain(String args, Instrumentation instrumentation) {
        ProofRuntime.configureFromProperties();
        FeatureCompatibility.configureFromProperties();
        PathfindingRuntime.configureFromProperties();
        PopManRuntime.configureFromProperties();
        System.out.println("[ZD-OPT-LAB-AGENT] version=15 schema=14 loaded");

        String renderer = System.getProperty("zomdroid.renderer", "");
        if ("GL4ES".equals(renderer)) installLegacyShaderPatch(instrumentation);

        boolean pacingRequested = flag("zomdroid.optlab.pacing");
        boolean wakeRequested = flag("zomdroid.optlab.stream.wake");
        boolean queueRequested = flag("zomdroid.optlab.stream.queue.fast");
        boolean lookaheadRequested = flag("zomdroid.optlab.stream.velocity.eta");
        boolean dirtyDedupRequested = flag("zomdroid.optlab.fbo.dirty.dedup");
        boolean fboBudgetRequested = flag("zomdroid.optlab.fbo.frame.budget");
        boolean coordinatorRequested = flag("zomdroid.optlab.stream.fbo.coordinator");
        boolean fboInnerLoopRequested = flag("zomdroid.optlab.fbo.inner.loop");
        boolean chunkForageRequested = flag("zomdroid.optlab.chunk.forage");
        boolean chunkNeighbourWorkerRequested = flag(
                "zomdroid.optlab.chunk.neighbour.worker");
        boolean chunkNeighbourMainRequested = flag(
                "zomdroid.optlab.chunk.neighbour.main");
        boolean chunkGridRequested = flag("zomdroid.optlab.chunk.grid.load");
        boolean chunkVehiclesRequested = flag("zomdroid.optlab.chunk.vehicles");
        boolean chunkBuildingsRequested = flag(
                "zomdroid.optlab.chunk.randomized.buildings");
        boolean chunkLuaRequested = flag("zomdroid.optlab.chunk.lua.mapobjects");
        boolean chunkWorldgenRequested = flag("zomdroid.optlab.chunk.worldgen.biome");
        boolean chunkCp2cRequested = flag("zomdroid.optlab.chunk.cp2c.dirty.clear");
        boolean chunkDepthRequested = flag("zomdroid.optlab.render.chunk.depth.upload");
        boolean chunkDepthLookupRequested = flag("zomdroid.optlab.render.chunk.depth.lookup");
        boolean ringRenderClearRequested = flag("zomdroid.optlab.render.ring.empty.clear");
        boolean shaderLookupRequested = flag("zomdroid.optlab.render.shader.lookup");
        boolean mvpRequested = flag("zomdroid.optlab.render.mvp");
        boolean stateRunTextureRequested = flag("zomdroid.optlab.render.staterun.texture");
        boolean textureBindRequested = flag("zomdroid.optlab.render.texture.bind");
        boolean gameProfilerIdleRequested = flag("zomdroid.optlab.render.profiler.idle");
        boolean renderStyleProbeRequested = flag(
                "zomdroid.optlab.render.probe.style.idle");
        boolean buildLoopRequested = flag("zomdroid.optlab.render.build.loop");
        boolean sameProgramBindRequested = flag(
                "zomdroid.optlab.render.same.program.bind");
        boolean extendedProbesRequested = flag(
                "zomdroid.optlab.render.probe.extended.idle");
        boolean pathfindingProofRequested = PathfindingRuntime.isProofEnabled();
        boolean popManProofRequested = PopManRuntime.isProofEnabled();
        boolean anyRequested = pacingRequested || wakeRequested || queueRequested
                || lookaheadRequested || dirtyDedupRequested || fboBudgetRequested
                || coordinatorRequested || fboInnerLoopRequested || chunkForageRequested
                || chunkNeighbourWorkerRequested || chunkNeighbourMainRequested
                || chunkGridRequested || chunkVehiclesRequested || chunkBuildingsRequested
                || chunkLuaRequested || chunkWorldgenRequested || chunkCp2cRequested
                || chunkDepthRequested || chunkDepthLookupRequested
                || ringRenderClearRequested
                || shaderLookupRequested || mvpRequested || stateRunTextureRequested
                || textureBindRequested || gameProfilerIdleRequested
                || renderStyleProbeRequested || buildLoopRequested
                || sameProgramBindRequested
                || extendedProbesRequested
                || pathfindingProofRequested || popManProofRequested;

        if (!anyRequested) {
            ProofRuntime.state("PACK", "ALL_OFF", "no_transformers_installed");
            return;
        }

        String mainThreadHash = resourceSha256("zombie/MainThread.class");
        String gameWindowHash = resourceSha256("zombie/GameWindow.class");
        String worldHash = resourceSha256("zombie/iso/WorldStreamer.class");
        String comparatorHash = resourceSha256("zombie/iso/WorldStreamer$ChunkComparator.class");
        String fboManagerHash = resourceSha256(
                "zombie/iso/fboRenderChunk/FBORenderChunkManager.class");
        String fboNLevelsHash = resourceSha256(
                "zombie/iso/fboRenderChunk/FBORenderLevels$NLevels.class");
        String fboRenderCellHash = resourceSha256(
                "zombie/iso/fboRenderChunk/FBORenderCell.class");
        String zoneGeneratorHash = resourceSha256(
                "zombie/iso/worldgen/zones/ZoneGenerator.class");
        String biomeMapHash = resourceSha256("zombie/iso/worldgen/maps/BiomeMap.class");
        String isoChunkHash = resourceSha256("zombie/iso/IsoChunk.class");
        String isoGridSquareHash = resourceSha256("zombie/iso/IsoGridSquare.class");
        String loadGridWorkaroundHash = resourceSha256(
                "zombie/LoadGridsquarePerformanceWorkaround.class");
        String mapObjectsHash = resourceSha256("zombie/Lua/MapObjects.class");
        String worldGenChunkHash = resourceSha256(
                "zombie/iso/worldgen/WorldGenChunk.class");
        String vehicleZoneHash = resourceSha256("zombie/iso/zones/VehicleZone.class");
        String defaultShaderHash = resourceSha256("zombie/core/DefaultShader.class");
        String shaderProgramHash = resourceSha256("zombie/core/opengl/ShaderProgram.class");
        String shaderUniformHash = resourceSha256(
                "zombie/core/opengl/ShaderProgram$Uniform.class");
        String ringBufferHash = resourceSha256("zombie/core/SpriteRenderer$RingBuffer.class");
        String modelHash = resourceSha256("zombie/core/skinnedmodel/model/Model.class");
        String shaderProgramsHash = resourceSha256(
                "zombie/core/opengl/ShaderPrograms.class");
        String vertexBufferObjectHash = resourceSha256(
                "zombie/core/skinnedmodel/model/VertexBufferObject.class");
        String spriteRendererHash = resourceSha256("zombie/core/SpriteRenderer.class");
        String textureHash = resourceSha256("zombie/core/textures/Texture.class");
        String gameProfilerHash = resourceSha256("zombie/GameProfiler.class");
        String performanceProbeHash = resourceSha256(
                "zombie/core/profiling/AbstractPerformanceProfileProbe.class");
        String shaderHelperHash = resourceSha256("zombie/core/ShaderHelper.class");

        FeatureCompatibility.Requirement mainThread = requirement(
                "main_thread", MAIN_THREAD_SHA256, mainThreadHash);
        FeatureCompatibility.Requirement gameWindow = requirement(
                "game_window", GAME_WINDOW_SHA256, gameWindowHash);
        FeatureCompatibility.Requirement worldStreamer = requirement(
                "world_streamer", WORLD_STREAMER_SHA256, worldHash);
        FeatureCompatibility.Requirement comparator = requirement(
                "chunk_comparator", WORLD_COMPARATOR_SHA256, comparatorHash);
        FeatureCompatibility.Requirement fboManager = requirement(
                "fbo_manager", FBO_MANAGER_SHA256, fboManagerHash);
        FeatureCompatibility.Requirement fboNLevels = requirement(
                "fbo_nlevels", FBO_NLEVELS_SHA256, fboNLevelsHash);
        FeatureCompatibility.Requirement fboRenderCell = requirement(
                "fbo_render_cell", FBO_RENDER_CELL_4220_42203_SHA256,
                fboRenderCellHash);
        FeatureCompatibility.Requirement zoneGenerator = requirement(
                "zone_generator", ZONE_GENERATOR_SHA256, zoneGeneratorHash);
        FeatureCompatibility.Requirement biomeMap = requirement(
                "biome_map", BIOME_MAP_SHA256, biomeMapHash);
        FeatureCompatibility.Requirement isoChunk = requirementAny(
                "iso_chunk", isoChunkHash, ISO_CHUNK_4220_SHA256,
                ISO_CHUNK_42203_SHA256);
        FeatureCompatibility.Requirement isoGridSquare = requirementAny(
                "iso_grid_square", isoGridSquareHash, ISO_GRID_SQUARE_4220_SHA256,
                ISO_GRID_SQUARE_42203_SHA256);
        FeatureCompatibility.Requirement loadGridWorkaround = requirement(
                "load_grid_workaround", LOAD_GRID_WORKAROUND_SHA256,
                loadGridWorkaroundHash);
        FeatureCompatibility.Requirement mapObjects = requirement(
                "map_objects", MAP_OBJECTS_SHA256, mapObjectsHash);
        FeatureCompatibility.Requirement worldGenChunk = requirement(
                "world_gen_chunk", WORLD_GEN_CHUNK_SHA256, worldGenChunkHash);
        FeatureCompatibility.Requirement vehicleZone = requirement(
                "vehicle_zone", VEHICLE_ZONE_SHA256, vehicleZoneHash);
        FeatureCompatibility.Requirement defaultShader = requirement(
                "default_shader", DEFAULT_SHADER_4220_42203_SHA256, defaultShaderHash);
        FeatureCompatibility.Requirement shaderProgram = requirement(
                "shader_program", SHADER_PROGRAM_4220_42203_SHA256, shaderProgramHash);
        FeatureCompatibility.Requirement shaderUniform = requirement(
                "shader_uniform", SHADER_UNIFORM_4220_42203_SHA256, shaderUniformHash);
        FeatureCompatibility.Requirement ringBuffer = requirement(
                "ring_buffer", RING_BUFFER_4220_42203_SHA256, ringBufferHash);
        FeatureCompatibility.Requirement model = requirement(
                "model", MODEL_4220_42203_SHA256, modelHash);
        FeatureCompatibility.Requirement shaderPrograms = requirement(
                "shader_programs", SHADER_PROGRAMS_4220_42203_SHA256,
                shaderProgramsHash);
        FeatureCompatibility.Requirement vertexBufferObject = requirement(
                "vertex_buffer_object", VERTEX_BUFFER_OBJECT_4220_42203_SHA256,
                vertexBufferObjectHash);
        FeatureCompatibility.Requirement spriteRenderer = requirement(
                "sprite_renderer", SPRITE_RENDERER_4220_42203_SHA256,
                spriteRendererHash);
        FeatureCompatibility.Requirement texture = requirement(
                "texture", TEXTURE_4220_42203_SHA256, textureHash);
        FeatureCompatibility.Requirement gameProfiler = requirement(
                "game_profiler", GAME_PROFILER_4220_42203_SHA256,
                gameProfilerHash);
        FeatureCompatibility.Requirement performanceProbe = requirement(
                "performance_probe", PERFORMANCE_PROBE_4220_42203_SHA256,
                performanceProbeHash);
        FeatureCompatibility.Requirement shaderHelper = requirement(
                "shader_helper", SHADER_HELPER_4220_42203_SHA256,
                shaderHelperHash);

        final FeatureCompatibility.Decision pacing = FeatureCompatibility.evaluate(
                "PACING", pacingRequested, 2, mainThread, gameWindow);
        final FeatureCompatibility.Decision wake = FeatureCompatibility.evaluate(
                "STREAM_WAKE", wakeRequested, 2, worldStreamer);
        final FeatureCompatibility.Decision queueFast = FeatureCompatibility.evaluate(
                "STREAM_QUEUE_FAST", queueRequested, 1, worldStreamer);
        final FeatureCompatibility.Decision lookahead = FeatureCompatibility.evaluate(
                "STREAM_VELOCITY_ETA", lookaheadRequested, 1, comparator);
        final FeatureCompatibility.Decision dirtyDedup = FeatureCompatibility.evaluate(
                "FBO_DIRTY_DEDUP", dirtyDedupRequested, 1, fboNLevels);
        final FeatureCompatibility.Decision fboBudget = FeatureCompatibility.evaluate(
                "FBO_FRAME_BUDGET", fboBudgetRequested, 1, fboManager);
        final FeatureCompatibility.Decision coordinator = fboBudgetRequested
                ? FeatureCompatibility.evaluate("STREAM_FBO_COORDINATOR",
                        coordinatorRequested, 2, worldStreamer, fboManager)
                : FeatureCompatibility.unsupported("STREAM_FBO_COORDINATOR",
                        coordinatorRequested, "requires_fbo_frame_budget");
        final FeatureCompatibility.Decision fboInnerLoop = FeatureCompatibility.evaluate(
                "FBO_INNER_LOOP", fboInnerLoopRequested, 1, fboRenderCell);
        final FeatureCompatibility.Decision chunkForage = FeatureCompatibility.evaluate(
                "CHUNK_FORAGING", chunkForageRequested, 1, zoneGenerator, biomeMap);
        final FeatureCompatibility.Decision chunkNeighbourWorker = FeatureCompatibility.evaluate(
                "CHUNK_NEIGHBOUR_WORKER", chunkNeighbourWorkerRequested, 2,
                isoChunk, isoGridSquare);
        final FeatureCompatibility.Decision chunkNeighbourMain = FeatureCompatibility.evaluate(
                "CHUNK_NEIGHBOUR_MAIN", chunkNeighbourMainRequested, 2,
                isoChunk, isoGridSquare);
        final FeatureCompatibility.Decision chunkGrid = FeatureCompatibility.evaluate(
                "CHUNK_GRID_LOAD", chunkGridRequested, 1, loadGridWorkaround);
        final FeatureCompatibility.Decision chunkVehicles = FeatureCompatibility.evaluate(
                "CHUNK_VEHICLE_INDEX", chunkVehiclesRequested, 1, isoChunk, vehicleZone);
        final FeatureCompatibility.Decision chunkBuildings = FeatureCompatibility.evaluate(
                "CHUNK_RANDOMIZED_BUILDINGS", chunkBuildingsRequested, 1, isoChunk);
        final FeatureCompatibility.Decision chunkLua = FeatureCompatibility.evaluate(
                "CHUNK_LUA_MAPOBJECTS", chunkLuaRequested, 1, mapObjects);
        final FeatureCompatibility.Decision chunkWorldgen = FeatureCompatibility.evaluate(
                "CHUNK_WORLDGEN_BIOME", chunkWorldgenRequested, 2, worldGenChunk);
        final FeatureCompatibility.Decision chunkCp2c = FeatureCompatibility.evaluate(
                "CHUNK_CP2C_DIRTY_CLEAR", chunkCp2cRequested, 3,
                isoChunk, isoGridSquare);
        final FeatureCompatibility.Decision chunkDepth = FeatureCompatibility.evaluate(
                "RTHREAD_CHUNK_DEPTH_UPLOAD", chunkDepthRequested, 2,
                defaultShader, shaderProgram, shaderUniform);
        final FeatureCompatibility.Decision chunkDepthLookup = chunkDepthRequested
                ? FeatureCompatibility.evaluate("RTHREAD_CHUNK_DEPTH_LOOKUP",
                        chunkDepthLookupRequested, 2,
                        defaultShader, shaderProgram, shaderUniform)
                : FeatureCompatibility.unsupported("RTHREAD_CHUNK_DEPTH_LOOKUP",
                        chunkDepthLookupRequested, "requires_chunk_depth_upload");
        final FeatureCompatibility.Decision ringRenderClear = FeatureCompatibility.evaluate(
                "RTHREAD_RING_RENDER_CLEAR", ringRenderClearRequested, 1,
                ringBuffer, model);
        final FeatureCompatibility.Decision shaderLookup = FeatureCompatibility.evaluate(
                "RTHREAD_SHADER_LOOKUP", shaderLookupRequested, 3, shaderPrograms);
        final FeatureCompatibility.Decision mvp = FeatureCompatibility.evaluate(
                "RTHREAD_MVP", mvpRequested, 1,
                vertexBufferObject, shaderProgram);
        final FeatureCompatibility.Decision stateRunTexture = FeatureCompatibility.evaluate(
                "RTHREAD_STATERUN_TEXTURE", stateRunTextureRequested, 1,
                ringBuffer, texture);
        final FeatureCompatibility.Decision textureBind = FeatureCompatibility.evaluate(
                "RTHREAD_TEXTURE_BIND", textureBindRequested, 1, texture);
        final FeatureCompatibility.Decision gameProfilerIdle = FeatureCompatibility.evaluate(
                "RTHREAD_GAME_PROFILER_IDLE", gameProfilerIdleRequested, 1,
                gameProfiler);
        final FeatureCompatibility.Decision renderStyleProbe = FeatureCompatibility.evaluate(
                "RTHREAD_RENDER_STYLE_PROBE", renderStyleProbeRequested, 1,
                performanceProbe, gameProfiler);
        final FeatureCompatibility.Decision buildLoop = FeatureCompatibility.evaluate(
                "RTHREAD_BUILD_LOOP", buildLoopRequested, 1,
                spriteRenderer, ringBuffer);
        final FeatureCompatibility.Decision sameProgramBind = FeatureCompatibility.evaluate(
                "RTHREAD_SAME_PROGRAM_BIND", sameProgramBindRequested, 1,
                shaderHelper);
        final FeatureCompatibility.Decision extendedProbes = FeatureCompatibility.evaluate(
                "RTHREAD_EXTENDED_PROBES", extendedProbesRequested, 1,
                performanceProbe, gameProfiler);

        // Unknown B42 hashes may proceed only after an out-of-load structural inspection. This
        // also validates the exact jars without loading game classes early from premain.
        if ((chunkDepth.isCandidate() || chunkDepthLookup.isCandidate())
                && !shaderUniformSupportShape()) {
            chunkDepth.fallback("shader_program_or_uniform_structural_probe_failed");
            chunkDepthLookup.fallback("shader_program_or_uniform_structural_probe_failed");
        }
        if (ringRenderClear.isCandidate() && !modelDrawCountsSupportShape()) {
            ringRenderClear.fallback("model_draw_counts_structural_probe_failed");
        }
        if (shaderLookup.isCandidate() && !shaderProgramsSupportShape()) {
            shaderLookup.fallback("shader_programs_structural_probe_failed");
        }
        if (mvp.isCandidate() && !mvpSupportShape()) {
            mvp.fallback("mvp_structural_probe_failed");
        }
        if (stateRunTexture.isCandidate() && !stateRunSupportShape()) {
            stateRunTexture.fallback("staterun_structural_probe_failed");
        }
        if (textureBind.isCandidate() && !textureBindSupportShape()) {
            textureBind.fallback("texture_bind_structural_probe_failed");
        }
        if (gameProfilerIdle.isCandidate() && !gameProfilerSupportShape()) {
            gameProfilerIdle.fallback("game_profiler_structural_probe_failed");
        }
        if ((renderStyleProbe.isCandidate() || extendedProbes.isCandidate())
                && !performanceProbeSupportShape()) {
            renderStyleProbe.fallback("performance_probe_structural_probe_failed");
            extendedProbes.fallback("performance_probe_structural_probe_failed");
        }
        if (buildLoop.isCandidate() && !buildLoopSupportShape()) {
            buildLoop.fallback("build_loop_structural_probe_failed");
        }
        if (fboInnerLoop.isCandidate() && !fboInnerLoopSupportShape()) {
            fboInnerLoop.fallback("fbo_inner_loop_structural_probe_failed");
        }
        if (sameProgramBind.isCandidate() && !sameProgramBindSupportShape()) {
            sameProgramBind.fallback("same_program_bind_structural_probe_failed");
        }

        if (!(pacing.isCandidate() || wake.isCandidate() || queueFast.isCandidate()
                || lookahead.isCandidate() || dirtyDedup.isCandidate()
                || fboBudget.isCandidate() || coordinator.isCandidate()
                || fboInnerLoop.isCandidate()
                || chunkForage.isCandidate() || chunkNeighbourWorker.isCandidate()
                || chunkNeighbourMain.isCandidate() || chunkGrid.isCandidate()
                || chunkVehicles.isCandidate() || chunkBuildings.isCandidate()
                || chunkLua.isCandidate() || chunkWorldgen.isCandidate()
                || chunkCp2c.isCandidate() || chunkDepth.isCandidate()
                || chunkDepthLookup.isCandidate() || ringRenderClear.isCandidate()
                || shaderLookup.isCandidate() || mvp.isCandidate()
                || stateRunTexture.isCandidate() || textureBind.isCandidate()
                || gameProfilerIdle.isCandidate() || renderStyleProbe.isCandidate()
                || buildLoop.isCandidate() || extendedProbes.isCandidate()
                || sameProgramBind.isCandidate()
                || pathfindingProofRequested || popManProofRequested)) return;

        try {
            if (pacing.isCandidate()) PacingRuntime.configureFromProperties();
            StreamCoreRuntime.configure(wake.isCandidate(), queueFast.isCandidate(),
                    lookahead.isCandidate());
            FboRuntime.configure(dirtyDedup.isCandidate(), fboBudget.isCandidate(),
                    coordinator.isCandidate());
            FboInnerLoopRuntime.configure(fboInnerLoop.isCandidate());
            SameProgramBindRuntime.configure(sameProgramBind.isCandidate());
            ChunkOptimizationRuntime.configure(chunkForage.isCandidate(),
                    chunkNeighbourWorker.isCandidate(), chunkNeighbourMain.isCandidate(),
                    chunkGrid.isCandidate(), chunkVehicles.isCandidate(),
                    chunkBuildings.isCandidate(), chunkLua.isCandidate(),
                    chunkWorldgen.isCandidate(), chunkCp2c.isCandidate());
            RenderOptimizationRuntime.configure(chunkDepth.isCandidate(),
                    chunkDepthLookup.isCandidate(), ringRenderClear.isCandidate());
            HotPathOptimizationRuntime.configure(shaderLookup.isCandidate(), mvp.isCandidate(),
                    stateRunTexture.isCandidate(), textureBind.isCandidate(),
                    gameProfilerIdle.isCandidate(), renderStyleProbe.isCandidate(),
                    buildLoop.isCandidate(), extendedProbes.isCandidate());

            AgentBuilder builder = new AgentBuilder.Default()
                    .disableClassFormatChanges()
                    .with(new TransformFailureListener());
            if (pacing.isCandidate()) {
                builder = builder
                        .type(named("zombie.MainThread"))
                        .transform((target, type, loader, module, domain) -> {
                            int mainLoops = methodCount(type, "mainLoop", "()V");
                            int queueMethods = methodCount(type, "queueInvokeOnMainThread", null);
                            if (mainLoops != 1 || queueMethods < 1) {
                                PacingRuntime.disable();
                                pacing.fallback("main_thread_shape mainLoop=" + mainLoops
                                        + " queueInvoke=" + queueMethods);
                                return target;
                            }
                            return target
                                    .visit(new AsmVisitorWrapper.ForDeclaredMethods().method(
                                            named("mainLoop").and(takesArguments(0))
                                                    .and(returns(void.class)),
                                            new YieldReplacement(pacing)))
                                    .visit(Advice.to(QueueInvokeAdvice.class)
                                            .on(named("queueInvokeOnMainThread")));
                        })
                        .type(named("zombie.GameWindow"))
                        .transform((target, type, loader, module, domain) -> {
                            int frameSteps = methodCount(type, "frameStep", null);
                            if (frameSteps != 1) {
                                PacingRuntime.disable();
                                pacing.fallback("game_window_shape frameStep=" + frameSteps);
                                return target;
                            }
                            pacing.passPart("game_window_frame_step");
                            return target.visit(Advice.to(FrameStepAdvice.class)
                                    .on(named("frameStep")));
                        });
            }
            if (wake.isCandidate() || queueFast.isCandidate() || coordinator.isCandidate()) {
                builder = builder.type(named("zombie.iso.WorldStreamer"))
                        .transform((target, type, loader, module, domain) -> {
                            int loops = methodCount(type, "threadLoop", null);
                            int signals = signalMethodCount(type);
                            boolean applyWake = wake.isCandidate() && loops == 1 && signals > 0;
                            boolean applyQueue = queueFast.isCandidate() && loops == 1;
                            boolean applyCoordinator = coordinator.isCandidate() && signals > 0;
                            if (wake.isCandidate() && !applyWake) {
                                StreamCoreRuntime.disableWakeShape(-1);
                                wake.fallback("world_streamer_shape threadLoop=" + loops
                                        + " signals=" + signals);
                            }
                            if (queueFast.isCandidate() && !applyQueue) {
                                StreamCoreRuntime.disableQueueShape(-1);
                                queueFast.fallback("world_streamer_shape threadLoop=" + loops);
                            }
                            if (coordinator.isCandidate() && !applyCoordinator) {
                                FboRuntime.disableCoordinator();
                                coordinator.fallback("world_streamer_shape signals=" + signals);
                            }
                            if (applyWake || applyQueue) {
                                target = target.visit(new AsmVisitorWrapper.ForDeclaredMethods()
                                        .method(named("threadLoop"),
                                                new WorldStreamerLoopVisitor(applyWake, applyQueue,
                                                        wake, queueFast)));
                            }
                            if (applyWake || applyCoordinator) {
                                target = target.visit(Advice.to(StreamSignalAdvice.class).on(
                                        streamSignalMatcher()));
                                if (applyWake) wake.passPart("world_streamer_signal");
                                if (applyCoordinator) {
                                    coordinator.passPart("world_streamer_signal");
                                }
                            }
                            return target;
                        });
            }
            if (lookahead.isCandidate()) {
                builder = builder.type(named("zombie.iso.WorldStreamer$ChunkComparator"))
                        .transform((target, type, loader, module, domain) -> {
                            int initMethods = methodCount(type, "init", null);
                            if (initMethods != 1) {
                                StreamCoreRuntime.disableLookahead();
                                lookahead.fallback("chunk_comparator_shape init=" + initMethods);
                                return target;
                            }
                            lookahead.passPart("chunk_comparator_init");
                            return target.visit(Advice.to(StreamLookaheadAdvice.class)
                                    .on(named("init")));
                        });
            }
            if (fboBudget.isCandidate() || coordinator.isCandidate()) {
                builder = builder
                        .type(named("zombie.iso.fboRenderChunk.FBORenderChunkManager"))
                        .transform((target, type, loader, module, domain) -> {
                            int beginMethods = methodCount(type, "beginRenderChunkLevel", null);
                            if (beginMethods != 1) {
                                FboRuntime.disableBudgetShape(-1);
                                fboBudget.fallback("fbo_manager_shape beginRenderChunkLevel="
                                        + beginMethods);
                                coordinator.fallback("fbo_manager_shape beginRenderChunkLevel="
                                        + beginMethods);
                                return target;
                            }
                            return target.visit(new AsmVisitorWrapper.ForDeclaredMethods().method(
                                    named("beginRenderChunkLevel"),
                                    new FboDirtyGateVisitor(fboBudget, coordinator)));
                        });
            }
            if (dirtyDedup.isCandidate()) {
                builder = builder
                        .type(named("zombie.iso.fboRenderChunk.FBORenderLevels$NLevels"))
                        .transform((target, type, loader, module, domain) -> {
                            int setDirty = methodCount(type, "setDirty", "(J)V");
                            if (setDirty != 1) {
                                FboRuntime.disableDirtyDedup();
                                dirtyDedup.fallback("fbo_nlevels_shape setDirty_long=" + setDirty);
                                return target;
                            }
                            dirtyDedup.passPart("fbo_nlevels_set_dirty");
                            return target
                                // Skip only a redundant dirty-bit OR. NLevels.invalidate() also
                                // clears live cached-square lists, so skipping that outer method
                                // would be observably unsafe even when the dirty bits match.
                                .visit(Advice.to(FboSetDirtyAdvice.class)
                                        .on(named("setDirty").and(takesArguments(long.class))
                                                .and(returns(void.class))));
                        });
            }
            if (fboInnerLoop.isCandidate()) {
                builder = builder
                        .type(named("zombie.iso.fboRenderChunk.FBORenderCell"))
                        .transform((target, type, loader, module, domain) -> {
                            int methods = methodCount(type, "prepareChunkForUpdating",
                                    "(ILzombie/iso/IsoChunk;I)V");
                            if (methods != 1) {
                                FboInnerLoopRuntime.disable(
                                        "fbo_render_cell_shape_prepareChunkForUpdating="
                                                + methods);
                                return target;
                            }
                            fboInnerLoop.passPart(
                                    "fbo_render_cell_prepare_chunk_cp6134_direct");
                            return target.visit(Advice.to(FboInnerLoopAdvice.class).on(
                                    named("prepareChunkForUpdating")
                                            .and(takesArguments(3))
                                            .and(returns(void.class))));
                        });
            }
            if (chunkDepth.isCandidate() || chunkDepthLookup.isCandidate()) {
                builder = builder.type(named("zombie.core.DefaultShader"))
                        .transform((target, type, loader, module, domain) -> {
                            int setters = methodCount(type, "setChunkDepth", "(F)V");
                            int compileHooks = methodCount(type, "onCompileSuccess",
                                    "(Lzombie/core/opengl/ShaderProgram;)V");
                            if (setters != 1 || compileHooks != 1) {
                                RenderOptimizationRuntime.disableChunkDepth(
                                        "default_shader_shape_setChunkDepth=" + setters
                                                + " onCompileSuccess=" + compileHooks);
                                return target;
                            }

                            target = target
                                    .visit(Advice.to(ChunkDepthAdvice.class).on(
                                            named("setChunkDepth")
                                                    .and(takesArguments(float.class))
                                                    .and(returns(void.class))))
                                    .visit(Advice.to(DefaultShaderCompileAdvice.class).on(
                                            named("onCompileSuccess").and(takesArguments(1))
                                                    .and(returns(void.class))));
                            chunkDepth.passPart("default_shader_set_chunk_depth");
                            chunkDepth.passPart("default_shader_compile_invalidation");

                            if (chunkDepthLookup.isCandidate()) {
                                chunkDepthLookup.passPart(
                                        "default_shader_set_chunk_depth");
                                chunkDepthLookup.passPart(
                                        "default_shader_compile_invalidation");
                            }
                            return target;
                        });
            }
            if (ringRenderClear.isCandidate() || stateRunTexture.isCandidate()) {
                builder = builder.type(named("zombie.core.SpriteRenderer$RingBuffer"))
                        .transform((target, type, loader, module, domain) -> {
                            if (ringRenderClear.isCandidate()) {
                                int renders = methodCount(type, "render", "()V");
                                if (renders != 1) {
                                    RenderOptimizationRuntime.disableRingRenderClear(
                                            "ring_buffer_shape_render=" + renders);
                                } else {
                                    target = target.visit(
                                            new AsmVisitorWrapper.ForDeclaredMethods().method(
                                                    named("render").and(takesArguments(0))
                                                            .and(returns(void.class)),
                                                    new RingRenderClearVisitor(ringRenderClear)));
                                }
                            }
                            if (stateRunTexture.isCandidate()) {
                                String descriptor = "(Lzombie/core/textures/TextureDraw;"
                                        + "Lzombie/core/textures/TextureDraw;"
                                        + "Lzombie/core/Styles/Style;"
                                        + "Lzombie/core/textures/Texture;"
                                        + "Lzombie/core/textures/Texture;"
                                        + "Lzombie/core/textures/Texture;B)Z";
                                int methods = methodCount(type, "isStateChanged", descriptor);
                                if (methods != 1) {
                                    HotPathOptimizationRuntime.disableStateRunTexture(
                                            "ring_buffer_shape_isStateChanged=" + methods);
                                } else {
                                    stateRunTexture.passPart("ring_buffer_is_state_changed");
                                    target = target.visit(Advice.to(StateRunTextureAdvice.class).on(
                                            named("isStateChanged").and(takesArguments(7))
                                                    .and(returns(boolean.class))));
                                }
                            }
                            return target;
                        });
            }
            if (shaderLookup.isCandidate()) {
                builder = builder.type(named("zombie.core.opengl.ShaderPrograms"))
                        .transform((target, type, loader, module, domain) -> {
                            int lookups = methodCount(type, "getProgramByID",
                                    "(I)Lzombie/core/opengl/ShaderProgram;");
                            int registers = methodCount(type, "registerProgram",
                                    "(Lzombie/core/opengl/ShaderProgram;)V");
                            int unregisters = methodCount(type, "unregisterProgram",
                                    "(Lzombie/core/opengl/ShaderProgram;)V");
                            if (lookups != 1 || registers != 1 || unregisters != 1) {
                                HotPathOptimizationRuntime.disableShaderLookup(
                                        "shader_programs_shape lookup=" + lookups
                                                + " register=" + registers
                                                + " unregister=" + unregisters);
                                return target;
                            }
                            shaderLookup.passPart("shader_programs_lookup");
                            shaderLookup.passPart("shader_programs_register_invalidation");
                            shaderLookup.passPart("shader_programs_unregister_invalidation");
                            return target
                                    .visit(Advice.to(ShaderLookupAdvice.class).on(
                                            named("getProgramByID").and(takesArguments(int.class))))
                                    .visit(Advice.to(ShaderRegistryMutationAdvice.class).on(
                                            named("registerProgram").or(named("unregisterProgram"))
                                                    .and(takesArguments(1))));
                        });
            }
            if (mvp.isCandidate()) {
                builder = builder.type(named(
                                "zombie.core.skinnedmodel.model.VertexBufferObject"))
                        .transform((target, type, loader, module, domain) -> {
                            int methods = methodCount(type, "setModelViewProjection",
                                    "(Lzombie/core/opengl/ShaderProgram;)V");
                            if (methods != 1) {
                                HotPathOptimizationRuntime.disableMvp(
                                        "vertex_buffer_object_shape_mvp=" + methods);
                                return target;
                            }
                            mvp.passPart("vertex_buffer_object_mvp");
                            return target.visit(Advice.to(MvpAdvice.class).on(
                                    named("setModelViewProjection").and(isStatic())
                                            .and(takesArguments(1))));
                        });
            }
            if (buildLoop.isCandidate()) {
                builder = builder.type(named("zombie.core.SpriteRenderer"))
                        .transform((target, type, loader, module, domain) -> {
                            int methods = methodCount(type, "buildDrawBuffer",
                                    "([Lzombie/core/textures/TextureDraw;"
                                            + "[Lzombie/core/Styles/Style;I)V");
                            if (methods != 1) {
                                HotPathOptimizationRuntime.disableBuildLoop(
                                        "sprite_renderer_shape_buildDrawBuffer=" + methods);
                                return target;
                            }
                            buildLoop.passPart("sprite_renderer_build_draw_buffer_cp6134_direct");
                            return target.visit(Advice.to(BuildLoopAdvice.class).on(
                                    named("buildDrawBuffer").and(takesArguments(3))
                                            .and(returns(void.class))));
                        });
            }
            if (sameProgramBind.isCandidate()) {
                builder = builder.type(named("zombie.core.ShaderHelper"))
                        .transform((target, type, loader, module, domain) -> {
                            int methods = methodCount(type, "glUseProgramObjectARB", "(I)V");
                            int fields = fieldCount(type, "currentlyBound", "I");
                            if (methods != 1 || fields != 1) {
                                SameProgramBindRuntime.disable(
                                        "shader_helper_shape_glUseProgramObjectARB=" + methods
                                                + " currentlyBound=" + fields);
                                return target;
                            }
                            sameProgramBind.passPart(
                                    "shader_helper_same_program_bind_cp61310");
                            return target.visit(Advice.to(SameProgramBindAdvice.class).on(
                                    named("glUseProgramObjectARB").and(isStatic())
                                            .and(takesArguments(int.class))
                                            .and(returns(void.class))));
                        });
            }
            if (textureBind.isCandidate()) {
                builder = builder.type(named("zombie.core.textures.Texture"))
                        .transform((target, type, loader, module, domain) -> {
                            int methods = methodCount(type, "bind", "(I)V");
                            if (methods != 1) {
                                HotPathOptimizationRuntime.disableTextureBind(
                                        "texture_shape_bind_int=" + methods);
                                return target;
                            }
                            textureBind.passPart("texture_bind_int");
                            return target.visit(Advice.to(TextureBindAdvice.class).on(
                                    named("bind").and(takesArguments(int.class))
                                            .and(returns(void.class))));
                        });
            }
            if (gameProfilerIdle.isCandidate()) {
                builder = builder.type(named("zombie.GameProfiler"))
                        .transform((target, type, loader, module, domain) -> {
                            int methods = methodCount(type, "profile",
                                    "(Ljava/lang/String;)Lzombie/GameProfiler$ProfileArea;");
                            if (methods != 1) {
                                HotPathOptimizationRuntime.disableGameProfiler(
                                        "game_profiler_shape_profile=" + methods);
                                return target;
                            }
                            gameProfilerIdle.passPart("game_profiler_profile_string");
                            return target.visit(Advice.to(GameProfilerIdleAdvice.class).on(
                                    named("profile").and(takesArguments(String.class))));
                        });
            }
            if (renderStyleProbe.isCandidate() || extendedProbes.isCandidate()) {
                builder = builder.type(named(
                                "zombie.core.profiling.AbstractPerformanceProfileProbe"))
                        .transform((target, type, loader, module, domain) -> {
                            int methods = methodCount(type, "profile",
                                    "()Lzombie/core/profiling/AbstractPerformanceProfileProbe;");
                            if (methods != 1) {
                                HotPathOptimizationRuntime.disableRenderStyleProbe(
                                        "performance_probe_shape_profile=" + methods);
                                HotPathOptimizationRuntime.disableExtendedProbes(
                                        "performance_probe_shape_profile=" + methods);
                                return target;
                            }
                            renderStyleProbe.passPart("performance_probe_profile");
                            extendedProbes.passPart("performance_probe_profile");
                            return target.visit(Advice.to(PerformanceProbeIdleAdvice.class).on(
                                    named("profile").and(takesArguments(0))));
                        });
            }
            if (chunkForage.isCandidate()) {
                builder = builder.type(named("zombie.iso.worldgen.zones.ZoneGenerator"))
                        .transform((target, type, loader, module, domain) -> {
                            int methods = methodCount(type, "genForaging", "(II)V");
                            if (methods != 1) {
                                ChunkOptimizationRuntime.disable("CHUNK_FORAGING");
                                chunkForage.fallback("zone_generator_shape genForaging=" + methods);
                                return target;
                            }
                            chunkForage.passPart("zone_generator_gen_foraging");
                            return target.visit(Advice.to(ChunkForageAdvice.class).on(
                                    named("genForaging").and(takesArguments(int.class, int.class))
                                            .and(returns(void.class))));
                        });
            }
            if (chunkGrid.isCandidate()) {
                builder = builder.type(named("zombie.LoadGridsquarePerformanceWorkaround"))
                        .transform((target, type, loader, module, domain) -> {
                            int methods = methodCount(type, "LoadGridsquare",
                                    "(Lzombie/iso/IsoGridSquare;)V");
                            if (methods != 1) {
                                ChunkOptimizationRuntime.disable("CHUNK_GRID_LOAD");
                                chunkGrid.fallback("load_grid_shape LoadGridsquare=" + methods);
                                return target;
                            }
                            chunkGrid.passPart("load_grid_square");
                            return target.visit(Advice.to(ChunkGridLoadAdvice.class).on(
                                    named("LoadGridsquare").and(isStatic())
                                            .and(takesArguments(1)).and(returns(void.class))));
                        });
            }
            if (chunkLua.isCandidate()) {
                builder = builder.type(named("zombie.Lua.MapObjects"))
                        .transform((target, type, loader, module, domain) -> {
                            int methods = methodCount(type, "newGridSquare",
                                    "(Lzombie/iso/IsoGridSquare;)V");
                            if (methods != 1) {
                                ChunkOptimizationRuntime.disable("CHUNK_LUA_MAPOBJECTS");
                                chunkLua.fallback("map_objects_shape newGridSquare=" + methods);
                                return target;
                            }
                            chunkLua.passPart("map_objects_new_grid_square");
                            return target.visit(Advice.to(ChunkLuaAdvice.class).on(
                                    named("newGridSquare").and(isStatic())
                                            .and(takesArguments(1)).and(returns(void.class))));
                        });
            }
            if (chunkWorldgen.isCandidate()) {
                builder = builder.type(named("zombie.iso.worldgen.WorldGenChunk"))
                        .transform((target, type, loader, module, domain) -> {
                            int scopes = methodCount(type, "generateChunks",
                                    "(Lzombie/iso/worldgen/ChunksCache;)V");
                            int lookups = methodCount(type, "getMapBiome",
                                    "(IILjava/lang/String;)"
                                            + "Lzombie/iso/worldgen/biomes/IBiome;");
                            if (scopes != 1 || lookups != 1) {
                                ChunkOptimizationRuntime.disable("CHUNK_WORLDGEN_BIOME");
                                chunkWorldgen.fallback("worldgen_shape generateChunks=" + scopes
                                        + " getMapBiome=" + lookups);
                                return target;
                            }
                            chunkWorldgen.passPart("worldgen_scope");
                            chunkWorldgen.passPart("worldgen_map_biome");
                            return target
                                    .visit(Advice.to(ChunkWorldgenScopeAdvice.class).on(
                                            named("generateChunks").and(takesArguments(1))
                                                    .and(returns(void.class))))
                                    .visit(Advice.to(ChunkMapBiomeAdvice.class).on(
                                            named("getMapBiome").and(takesArguments(3))));
                        });
            }
            if (chunkNeighbourWorker.isCandidate() || chunkNeighbourMain.isCandidate()
                    || chunkVehicles.isCandidate() || chunkBuildings.isCandidate()
                    || chunkCp2c.isCandidate()) {
                builder = builder.type(named("zombie.iso.IsoChunk"))
                        .transform((target, type, loader, module, domain) -> {
                            int workerScopes = methodCount(type,
                                    "loadInWorldStreamerThread", "()V");
                            int mainScopes = methodCount(type, "loadInMainThread", "()V");
                            int addVehicles = methodCount(type, "AddVehicles", "()V");
                            int randomize = methodCount(type, "randomizeBuildingsEtc",
                                    "(Ljava/util/ArrayList;)V");

                            boolean workerNeeded = chunkNeighbourWorker.isCandidate()
                                    || chunkCp2c.isCandidate();
                            if (workerNeeded && workerScopes != 1) {
                                if (chunkNeighbourWorker.isCandidate()) {
                                    chunkNeighbourWorker.fallback(
                                            "iso_chunk_worker_scope=" + workerScopes);
                                    ChunkOptimizationRuntime.disable(
                                            "CHUNK_NEIGHBOUR_WORKER");
                                }
                                if (chunkCp2c.isCandidate()) {
                                    chunkCp2c.fallback("iso_chunk_worker_scope=" + workerScopes);
                                    ChunkOptimizationRuntime.disable(
                                            "CHUNK_CP2C_DIRTY_CLEAR");
                                }
                            } else if (workerNeeded) {
                                target = target.visit(Advice.to(ChunkWorkerScopeAdvice.class).on(
                                        named("loadInWorldStreamerThread")
                                                .and(takesArguments(0))
                                                .and(returns(void.class))));
                                if (chunkNeighbourWorker.isCandidate()) {
                                    chunkNeighbourWorker.passPart("iso_chunk_worker_scope");
                                }
                                if (chunkCp2c.isCandidate()) {
                                    chunkCp2c.passPart("iso_chunk_worker_scope");
                                }
                            }

                            if (chunkNeighbourMain.isCandidate()) {
                                if (mainScopes != 1) {
                                    chunkNeighbourMain.fallback(
                                            "iso_chunk_main_scope=" + mainScopes);
                                    ChunkOptimizationRuntime.disable("CHUNK_NEIGHBOUR_MAIN");
                                } else {
                                    target = target.visit(Advice.to(ChunkMainScopeAdvice.class).on(
                                            named("loadInMainThread").and(takesArguments(0))
                                                    .and(returns(void.class))));
                                    chunkNeighbourMain.passPart("iso_chunk_main_scope");
                                }
                            }

                            if (chunkVehicles.isCandidate()) {
                                if (addVehicles != 1 || !vehicleHelperShape(type)) {
                                    chunkVehicles.fallback("iso_chunk_vehicle_shape addVehicles="
                                            + addVehicles + " helpers=" + vehicleHelperShape(type));
                                    ChunkOptimizationRuntime.disable("CHUNK_VEHICLE_INDEX");
                                } else {
                                    target = target.visit(Advice.to(ChunkVehiclesAdvice.class).on(
                                            named("AddVehicles").and(takesArguments(0))
                                                    .and(returns(void.class))));
                                    chunkVehicles.passPart("iso_chunk_add_vehicles");
                                }
                            }

                            if (chunkBuildings.isCandidate()) {
                                if (randomize != 1) {
                                    chunkBuildings.fallback(
                                            "iso_chunk_randomize_shape=" + randomize);
                                    ChunkOptimizationRuntime.disable(
                                            "CHUNK_RANDOMIZED_BUILDINGS");
                                } else {
                                    target = target.visit(Advice.to(ChunkBuildingsAdvice.class).on(
                                            named("randomizeBuildingsEtc").and(takesArguments(1))
                                                    .and(returns(void.class))));
                                    chunkBuildings.passPart("iso_chunk_randomized_buildings");
                                }
                            }
                            return target;
                        });
            }
            if (chunkNeighbourWorker.isCandidate() || chunkNeighbourMain.isCandidate()
                    || chunkCp2c.isCandidate()) {
                builder = builder.type(named("zombie.iso.IsoGridSquare"))
                        .transform((target, type, loader, module, domain) -> {
                            int recalc2 = methodCount(type, "ReCalculateAll",
                                    "(Lzombie/iso/IsoGridSquare;"
                                            + "Lzombie/iso/IsoGridSquare$GetSquare;)V");
                            int recalc3 = methodCount(type, "ReCalculateAll",
                                    "(ZLzombie/iso/IsoGridSquare;"
                                            + "Lzombie/iso/IsoGridSquare$GetSquare;)V");
                            int recalcProperties = methodCount(type, "RecalcProperties", "()V");
                            int recalcNeighbours = methodCount(type, "RecalcAllWithNeighbours",
                                    "(ZLzombie/iso/IsoGridSquare$GetSquare;)V");
                            boolean pairShape = recalc2 == 1 && recalc3 == 1
                                    && recalcProperties == 1;

                            if ((chunkNeighbourWorker.isCandidate()
                                    || chunkNeighbourMain.isCandidate()) && !pairShape) {
                                String reason = "iso_grid_pair_shape recalc2=" + recalc2
                                        + " recalc3=" + recalc3
                                        + " recalcProperties=" + recalcProperties;
                                if (chunkNeighbourWorker.isCandidate()) {
                                    chunkNeighbourWorker.fallback(reason);
                                    ChunkOptimizationRuntime.disable(
                                            "CHUNK_NEIGHBOUR_WORKER");
                                }
                                if (chunkNeighbourMain.isCandidate()) {
                                    chunkNeighbourMain.fallback(reason);
                                    ChunkOptimizationRuntime.disable("CHUNK_NEIGHBOUR_MAIN");
                                }
                            } else if (pairShape && (chunkNeighbourWorker.isCandidate()
                                    || chunkNeighbourMain.isCandidate())) {
                                target = target
                                        .visit(Advice.to(ChunkRecalc2Advice.class).on(
                                                named("ReCalculateAll").and(takesArguments(2))))
                                        .visit(Advice.to(ChunkRecalc3Advice.class).on(
                                                named("ReCalculateAll").and(takesArguments(3))));
                                if (chunkNeighbourWorker.isCandidate()) {
                                    chunkNeighbourWorker.passPart("iso_grid_pair_fast_path");
                                }
                                if (chunkNeighbourMain.isCandidate()) {
                                    chunkNeighbourMain.passPart("iso_grid_pair_fast_path");
                                }
                            }

                            boolean needsPropertiesAdvice = pairShape
                                    && (chunkNeighbourWorker.isCandidate()
                                            || chunkNeighbourMain.isCandidate());
                            if (chunkCp2c.isCandidate()) {
                                if (recalcProperties != 1 || recalcNeighbours != 1) {
                                    chunkCp2c.fallback("cp2c_shape recalcProperties="
                                            + recalcProperties + " recalcNeighbours="
                                            + recalcNeighbours);
                                    ChunkOptimizationRuntime.disable(
                                            "CHUNK_CP2C_DIRTY_CLEAR");
                                } else {
                                    needsPropertiesAdvice = true;
                                    target = target.visit(
                                            Advice.to(ChunkRecalcNeighboursScopeAdvice.class).on(
                                                    named("RecalcAllWithNeighbours")
                                                            .and(takesArguments(2))
                                                            .and(returns(void.class))));
                                    chunkCp2c.passPart("iso_grid_recalc_neighbours_scope");
                                    chunkCp2c.passPart("iso_grid_recalc_properties");
                                }
                            }
                            if (needsPropertiesAdvice) {
                                target = target.visit(Advice.to(ChunkRecalcPropertiesAdvice.class)
                                        .on(named("RecalcProperties").and(takesArguments(0))
                                                .and(returns(void.class))));
                            }
                            return target;
                        });
            }
            if (pathfindingProofRequested) {
                builder = builder
                        .type(named("zombie.pathfind.nativeCode.PathfindNative"))
                        .transform((target, type, loader, module, domain) -> {
                            int wrappers = methodCount(type, "findPath",
                                    "(Lzombie/pathfind/nativeCode/PathFindRequest;"
                                            + "Ljava/nio/ByteBuffer;Z)I");
                            if (wrappers != 1) {
                                ProofRuntime.state("PATHFINDING_NATIVE_FALLBACK", "FALLBACK",
                                        "pathfind_wrapper_shape=" + wrappers);
                                return target;
                            }
                            return target.visit(Advice.to(PathfindingRequestAdvice.class).on(
                                    named("findPath").and(takesArguments(3))
                                            .and(returns(int.class))));
                        })
                        .type(named("zombie.pathfind.nativeCode.PathfindNativeThread"))
                        .transform((target, type, loader, module, domain) -> {
                            int loops = methodCount(type, "runInner", "()V");
                            if (loops != 1) {
                                ProofRuntime.state("PATHFINDING_NATIVE_FALLBACK", "FALLBACK",
                                        "pathfind_thread_shape=" + loops);
                                return target;
                            }
                            return target.visit(Advice.to(PathfindingThreadAdvice.class).on(
                                    named("runInner").and(takesArguments(0))
                                            .and(returns(void.class))));
                        });
            }
            if (popManProofRequested) {
                builder = builder
                        .type(named("zombie.popman.ZombiePopulationManager"))
                        .transform((target, type, loader, module, domain) -> {
                            int initMethods = methodCount(type, "init", "()V");
                            int saveMethods = methodCount(type, "writeCellSnapshot",
                                    "(Lzombie/popman/ZombiePopulationManager$PendingCellSave;)V");
                            if (initMethods != 1 || saveMethods != 1) {
                                ProofRuntime.state("POPMAN_NATIVE_FALLBACK", "FALLBACK",
                                        "popman_shape init=" + initMethods
                                                + " writeCellSnapshot=" + saveMethods);
                                return target;
                            }
                            return target
                                    .visit(Advice.to(PopManInitAdvice.class).on(
                                            named("init").and(isStatic())
                                                    .and(takesArguments(0))
                                                    .and(returns(void.class))))
                                    .visit(Advice.to(PopManSaveCellAdvice.class).on(
                                            named("writeCellSnapshot").and(takesArguments(1))
                                                    .and(returns(void.class))));
                        });
            }
            builder.installOn(instrumentation);
            ProofRuntime.state("PACK", "ARMED", "transformers_installed");
        } catch (Throwable error) {
            PacingRuntime.disable();
            StreamCoreRuntime.configure(false, false, false);
            FboRuntime.configure(false, false, false);
            FboInnerLoopRuntime.configure(false);
            SameProgramBindRuntime.configure(false);
            ChunkOptimizationRuntime.configure(false, false, false, false, false,
                    false, false, false, false);
            RenderOptimizationRuntime.configure(false, false, false);
            HotPathOptimizationRuntime.configure(false, false, false, false,
                    false, false, false, false);
            ProofRuntime.state("PACK", "BLOCKED_INSTALL", error.getClass().getSimpleName());
            System.err.println("[ZD-OPT-LAB-AGENT] install failed: " + error);
            error.printStackTrace(System.err);
        }
    }

    private static boolean flag(String property) {
        return "1".equals(System.getProperty(property, "0"));
    }

    private static FeatureCompatibility.Requirement requirement(
            String label, String expectedSha256, String actualSha256) {
        return new FeatureCompatibility.Requirement(label, expectedSha256, actualSha256);
    }

    private static FeatureCompatibility.Requirement requirementAny(
            String label, String actualSha256, String... expectedSha256) {
        return new FeatureCompatibility.Requirement(label,
                String.join("|", expectedSha256), actualSha256);
    }

    private static int methodCount(TypeDescription type, String name, String descriptor) {
        int count = 0;
        for (MethodDescription.InDefinedShape method : type.getDeclaredMethods()) {
            if (name.equals(method.getName())
                    && (descriptor == null || descriptor.equals(method.getDescriptor()))) {
                count++;
            }
        }
        return count;
    }

    private static boolean shaderUniformSupportShape() {
        try {
            TypePool pool = TypePool.Default.of(Main.class.getClassLoader());
            TypeDescription program = pool.describe(
                    "zombie.core.opengl.ShaderProgram").resolve();
            TypeDescription uniform = pool.describe(
                    "zombie.core.opengl.ShaderProgram$Uniform").resolve();
            if (methodCount(program, "getUniform",
                    "(Ljava/lang/String;I)Lzombie/core/opengl/ShaderProgram$Uniform;") != 1) {
                return false;
            }
            int fields = 0;
            for (FieldDescription.InDefinedShape field : uniform.getDeclaredFields()) {
                if ("loc".equals(field.getName()) && "I".equals(field.getDescriptor())) fields++;
            }
            return fields == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean modelDrawCountsSupportShape() {
        try {
            TypeDescription model = TypePool.Default.of(Main.class.getClassLoader())
                    .describe("zombie.core.skinnedmodel.model.Model").resolve();
            int fields = 0;
            for (FieldDescription.InDefinedShape field : model.getDeclaredFields()) {
                if ("modelDrawCounts".equals(field.getName()) && field.isStatic()
                        && "Lgnu/trove/map/hash/TObjectIntHashMap;"
                                .equals(field.getDescriptor())) fields++;
            }
            return fields == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static int fieldCount(TypeDescription type, String name, String descriptor) {
        int count = 0;
        for (FieldDescription.InDefinedShape field : type.getDeclaredFields()) {
            if (name.equals(field.getName())
                    && (descriptor == null || descriptor.equals(field.getDescriptor()))) count++;
        }
        return count;
    }

    private static boolean fieldsMatch(TypeDescription type, String... nameDescriptorPairs) {
        if ((nameDescriptorPairs.length & 1) != 0) return false;
        for (int i = 0; i < nameDescriptorPairs.length; i += 2) {
            if (fieldCount(type, nameDescriptorPairs[i], nameDescriptorPairs[i + 1]) != 1) {
                return false;
            }
        }
        return true;
    }

    private static boolean shaderProgramsSupportShape() {
        try {
            TypeDescription type = TypePool.Default.of(Main.class.getClassLoader())
                    .describe("zombie.core.opengl.ShaderPrograms").resolve();
            return methodCount(type, "getProgramByID",
                    "(I)Lzombie/core/opengl/ShaderProgram;") == 1
                    && methodCount(type, "registerProgram",
                    "(Lzombie/core/opengl/ShaderProgram;)V") == 1
                    && methodCount(type, "unregisterProgram",
                    "(Lzombie/core/opengl/ShaderProgram;)V") == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean mvpSupportShape() {
        try {
            TypePool pool = TypePool.Default.of(Main.class.getClassLoader());
            TypeDescription vbo = pool.describe(
                    "zombie.core.skinnedmodel.model.VertexBufferObject").resolve();
            TypeDescription program = pool.describe(
                    "zombie.core.opengl.ShaderProgram").resolve();
            return methodCount(vbo, "setModelViewProjection",
                    "(Lzombie/core/opengl/ShaderProgram;)V") == 1
                    && methodCount(program, "isCompiled", "()Z") == 1
                    && methodCount(program, "getUniform",
                    "(Ljava/lang/String;I)Lzombie/core/opengl/ShaderProgram$Uniform;") == 1
                    && methodCount(program, "setTransformMatrix",
                    "(ILorg/joml/Matrix4f;)V") == 1
                    && fieldCount(program, "modelView", "Lorg/joml/Matrix4f;") == 1
                    && fieldCount(program, "projection", "Lorg/joml/Matrix4f;") == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean stateRunSupportShape() {
        try {
            TypePool pool = TypePool.Default.of(Main.class.getClassLoader());
            TypeDescription ring = pool.describe(
                    "zombie.core.SpriteRenderer$RingBuffer").resolve();
            TypeDescription texture = pool.describe("zombie.core.textures.Texture").resolve();
            TypeDescription asset = pool.describe("zombie.asset.Asset").resolve();
            String descriptor = "(Lzombie/core/textures/TextureDraw;"
                    + "Lzombie/core/textures/TextureDraw;Lzombie/core/Styles/Style;"
                    + "Lzombie/core/textures/Texture;Lzombie/core/textures/Texture;"
                    + "Lzombie/core/textures/Texture;B)Z";
            return methodCount(ring, "isStateChanged", descriptor) == 1
                    && fieldCount(ring, "currentRun", null) == 1
                    && fieldCount(ring, "currentTexture0",
                    "Lzombie/core/textures/Texture;") == 1
                    && fieldCount(ring, "currentTexture1",
                    "Lzombie/core/textures/Texture;") == 1
                    && fieldCount(ring, "currentTexture2",
                    "Lzombie/core/textures/Texture;") == 1
                    && fieldCount(ring, "currentUseAttribArray", "B") == 1
                    && fieldCount(ring, "currentStyle", "Lzombie/core/Styles/Style;") == 1
                    && methodCount(texture, "getID", "()I") == 1
                    && methodCount(texture, "isDestroyed", "()Z") == 1
                    && methodCount(texture, "isValid", "()Z") == 1
                    && methodCount(asset, "isReady", "()Z") == 1
                    && fieldCount(texture, "bindAlways", "Z") == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean textureBindSupportShape() {
        try {
            TypePool pool = TypePool.Default.of(Main.class.getClassLoader());
            TypeDescription texture = pool.describe("zombie.core.textures.Texture").resolve();
            TypeDescription asset = pool.describe("zombie.asset.Asset").resolve();
            TypeDescription debug = pool.describe("zombie.debug.DebugOptions").resolve();
            return methodCount(texture, "bind", "(I)V") == 1
                    && methodCount(texture, "getID", "()I") == 1
                    && methodCount(texture, "isDestroyed", "()Z") == 1
                    && methodCount(texture, "isValid", "()Z") == 1
                    && methodCount(asset, "isReady", "()Z") == 1
                    && fieldCount(texture, "lastTextureID", "I") == 1
                    && fieldCount(texture, "bindAlways", "Z") == 1
                    && fieldCount(debug, "instance", "Lzombie/debug/DebugOptions;") == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean gameProfilerSupportShape() {
        try {
            TypeDescription type = TypePool.Default.of(Main.class.getClassLoader())
                    .describe("zombie.GameProfiler").resolve();
            return methodCount(type, "isRunning", "()Z") == 1
                    && methodCount(type, "profile",
                    "(Ljava/lang/String;)Lzombie/GameProfiler$ProfileArea;") == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean performanceProbeSupportShape() {
        try {
            TypeDescription type = TypePool.Default.of(Main.class.getClassLoader())
                    .describe("zombie.core.profiling.AbstractPerformanceProfileProbe").resolve();
            return methodCount(type, "profile",
                    "()Lzombie/core/profiling/AbstractPerformanceProfileProbe;") == 1
                    && fieldCount(type, "name", "Ljava/lang/String;") == 1
                    && gameProfilerSupportShape();
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean buildLoopSupportShape() {
        try {
            TypePool pool = TypePool.Default.of(Main.class.getClassLoader());
            TypeDescription renderer = pool.describe("zombie.core.SpriteRenderer").resolve();
            TypeDescription ring = pool.describe(
                    "zombie.core.SpriteRenderer$RingBuffer").resolve();
            TypeDescription run = pool.describe(
                    "zombie.core.SpriteRenderer$RingBuffer$StateRun").resolve();
            TypeDescription draw = pool.describe("zombie.core.textures.TextureDraw").resolve();
            TypeDescription drawType = pool.describe(
                    "zombie.core.textures.TextureDraw$Type").resolve();
            TypeDescription style = pool.describe("zombie.core.Styles.Style").resolve();
            TypeDescription alpha = pool.describe("zombie.core.Styles.AlphaOp").resolve();
            return methodCount(renderer, "buildDrawBuffer",
                    "([Lzombie/core/textures/TextureDraw;"
                            + "[Lzombie/core/Styles/Style;I)V") == 1
                    && fieldCount(renderer, "ringBuffer",
                    "Lzombie/core/SpriteRenderer$RingBuffer;") == 1
                    && methodCount(ring, "add",
                    "(Lzombie/core/textures/TextureDraw;"
                            + "Lzombie/core/textures/TextureDraw;"
                            + "Lzombie/core/Styles/Style;)V") == 1
                    && fieldsMatch(ring,
                    "bufferSizeInVertices", "J", "indexBufferSize", "J",
                    "currentVertices", "Ljava/nio/FloatBuffer;",
                    "currentIndices", "Ljava/nio/ShortBuffer;",
                    "vertexCursor", "I", "indexCursor", "I", "numRuns", "I",
                    "stateRun", "[Lzombie/core/SpriteRenderer$RingBuffer$StateRun;",
                    "currentRun", "Lzombie/core/SpriteRenderer$RingBuffer$StateRun;",
                    "currentStyle", "Lzombie/core/Styles/Style;",
                    "currentTexture0", "Lzombie/core/textures/Texture;",
                    "currentTexture1", "Lzombie/core/textures/Texture;",
                    "currentTexture2", "Lzombie/core/textures/Texture;",
                    "currentUseAttribArray", "B")
                    && fieldsMatch(run,
                    "z", "F", "chunkDepth", "F",
                    "texture0", "Lzombie/core/textures/Texture;",
                    "texture1", "Lzombie/core/textures/Texture;",
                    "texture2", "Lzombie/core/textures/Texture;",
                    "useAttribArray", "B", "style", "Lzombie/core/Styles/Style;",
                    "start", "I", "length", "I", "indices", "Ljava/nio/ShortBuffer;",
                    "startIndex", "I", "endIndex", "I", "ops", "Ljava/util/ArrayList;")
                    && fieldsMatch(draw,
                    "type", "Lzombie/core/textures/TextureDraw$Type;", "flipped", "Z",
                    "col0", "I", "col1", "I", "col2", "I", "col3", "I",
                    "x0", "F", "x1", "F", "x2", "F", "x3", "F",
                    "y0", "F", "y1", "F", "y2", "F", "y3", "F",
                    "u0", "F", "u1", "F", "u2", "F", "u3", "F",
                    "v0", "F", "v1", "F", "v2", "F", "v3", "F",
                    "z", "F", "chunkDepth", "F",
                    "tex", "Lzombie/core/textures/Texture;",
                    "tex1", "Lzombie/core/textures/Texture;",
                    "tex2", "Lzombie/core/textures/Texture;",
                    "useAttribArray", "B",
                    "tex1U0", "F", "tex1U1", "F", "tex1U2", "F", "tex1U3", "F",
                    "tex1V0", "F", "tex1V1", "F", "tex1V2", "F", "tex1V3", "F",
                    "tex2U0", "F", "tex2U1", "F", "tex2U2", "F", "tex2U3", "F",
                    "tex2V0", "F", "tex2V1", "F", "tex2V2", "F", "tex2V3", "F",
                    "singleCol", "Z")
                    && fieldCount(drawType, "glDraw",
                    "Lzombie/core/textures/TextureDraw$Type;") == 1
                    && fieldCount(drawType, "DrawModel",
                    "Lzombie/core/textures/TextureDraw$Type;") == 1
                    && methodCount(draw, "getColor", "(I)I") == 1
                    && methodCount(style, "getStyleID", "()I") == 1
                    && methodCount(style, "getAlphaOp", "()Lzombie/core/Styles/AlphaOp;") == 1
                    && methodCount(alpha, "op", "(IILjava/nio/FloatBuffer;)V") == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean fboInnerLoopSupportShape() {
        try {
            TypePool pool = TypePool.Default.of(Main.class.getClassLoader());
            TypeDescription cell = pool.describe(
                    "zombie.iso.fboRenderChunk.FBORenderCell").resolve();
            TypeDescription chunk = pool.describe("zombie.iso.IsoChunk").resolve();
            TypeDescription levels = pool.describe(
                    "zombie.iso.fboRenderChunk.FBORenderLevels").resolve();
            TypeDescription cutaways = pool.describe(
                    "zombie.iso.fboRenderChunk.FBORenderCutaways").resolve();
            TypeDescription levelData = pool.describe(
                    "zombie.iso.fboRenderChunk.FBORenderCutaways$ChunkLevelData").resolve();
            TypeDescription square = pool.describe("zombie.iso.IsoGridSquare").resolve();
            TypeDescription camera = pool.describe("zombie.iso.IsoCamera").resolve();
            TypeDescription frameState = pool.describe(
                    "zombie.iso.IsoCamera$FrameState").resolve();
            return methodCount(cell, "prepareChunkForUpdating",
                    "(ILzombie/iso/IsoChunk;I)V") == 1
                    && methodCount(chunk, "getRenderLevels",
                    "(I)Lzombie/iso/fboRenderChunk/FBORenderLevels;") == 1
                    && methodCount(chunk, "getCutawayDataForLevel",
                    "(I)Lzombie/iso/fboRenderChunk/FBORenderCutaways$ChunkLevelData;") == 1
                    && methodCount(chunk, "getGridSquare",
                    "(III)Lzombie/iso/IsoGridSquare;") == 1
                    && methodCount(levels, "isOnScreen", "(I)Z") == 1
                    && methodCount(levels, "getMinLevel", "(I)I") == 1
                    && methodCount(levels, "getMaxLevel", "(I)I") == 1
                    && methodCount(cutaways, "getInstance",
                    "()Lzombie/iso/fboRenderChunk/FBORenderCutaways;") == 1
                    && methodCount(cutaways, "shouldRenderBuildingSquare",
                    "(ILzombie/iso/IsoGridSquare;)Z") == 1
                    && fieldCount(levelData, "squareFlags", "[[B") == 1
                    && fieldCount(square, "lighting", "[Lzombie/iso/IsoGridSquare$ILighting;") == 1
                    && methodCount(square, "getLightInfo",
                    "(I)Lzombie/core/textures/ColorInfo;") == 1
                    && methodCount(square, "cacheLightInfo", "()V") == 1
                    && fieldCount(camera, "frameState", "Lzombie/iso/IsoCamera$FrameState;") == 1
                    && fieldCount(frameState, "playerIndex", "I") == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean sameProgramBindSupportShape() {
        try {
            TypePool pool = TypePool.Default.of(Main.class.getClassLoader());
            TypeDescription helper = pool.describe("zombie.core.ShaderHelper").resolve();
            TypeDescription options = pool.describe("zombie.debug.DebugOptions").resolve();
            TypeDescription checks = pool.describe("zombie.debug.DebugOptions$Checks").resolve();
            TypeDescription booleanOption = pool.describe(
                    "zombie.debug.BooleanDebugOption").resolve();
            return methodCount(helper, "glUseProgramObjectARB", "(I)V") == 1
                    && fieldCount(helper, "currentlyBound", "I") == 1
                    && fieldCount(options, "instance", "Lzombie/debug/DebugOptions;") == 1
                    && fieldCount(options, "checks", "Lzombie/debug/DebugOptions$Checks;") == 1
                    && fieldCount(checks, "boundShader",
                    "Lzombie/debug/BooleanDebugOption;") == 1
                    && methodCount(booleanOption, "getValue", "()Z") == 1;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static boolean vehicleHelperShape(TypeDescription type) {
        String zone = "Lzombie/iso/zones/Zone;";
        String vehicleZone = "Lzombie/iso/zones/VehicleZone;";
        String string = "Ljava/lang/String;";
        return methodCount(type, "AddVehicles_ForTest", "(" + zone + ")V") == 1
                && methodCount(type, "AddVehicles_OnZone",
                        "(" + vehicleZone + string + ")V") == 1
                && methodCount(type, "AddVehicles_OnZonePolyline",
                        "(" + vehicleZone + string + ")V") == 1
                && methodCount(type, "AddVehicles_TrafficJam_W",
                        "(" + zone + string + ")V") == 1
                && methodCount(type, "AddVehicles_TrafficJam_E",
                        "(" + zone + string + ")V") == 1
                && methodCount(type, "AddVehicles_TrafficJam_S",
                        "(" + zone + string + ")V") == 1
                && methodCount(type, "AddVehicles_TrafficJam_N",
                        "(" + zone + string + ")V") == 1
                && methodCount(type, "AddVehicles_TrafficJam_Polyline",
                        "(" + zone + string + ")V") == 1
                && methodCount(type, "addRandomCarCrash", "(" + zone + "Z)V") == 1;
    }

    private static int signalMethodCount(TypeDescription type) {
        int count = 0;
        for (MethodDescription.InDefinedShape method : type.getDeclaredMethods()) {
            String name = method.getName();
            if ("addJob".equals(name) || "addJobInstant".equals(name)
                    || "addJobConvert".equals(name) || "addJobWipe".equals(name)
                    || "receiveChunkPart".equals(name) || "receiveNotRequired".equals(name)
                    || "requestLargeAreaZip".equals(name) || "stop".equals(name)
                    || "quit".equals(name)) {
                count++;
            }
        }
        return count;
    }

    private static ElementMatcher.Junction<MethodDescription> streamSignalMatcher() {
        return named("addJob").or(named("addJobInstant"))
                .or(named("addJobConvert")).or(named("addJobWipe"))
                .or(named("receiveChunkPart")).or(named("receiveNotRequired"))
                .or(named("requestLargeAreaZip")).or(named("stop")).or(named("quit"));
    }

    private static void installLegacyShaderPatch(Instrumentation instrumentation) {
        ClassLoader classLoader = Main.class.getClassLoader();
        TypePool typePool = TypePool.Default.of(classLoader);
        ClassFileLocator locator = ClassFileLocator.ForClassLoader.of(classLoader);
        try {
            new ByteBuddy().with(TypeValidation.DISABLED)
                    .rebase(typePool.describe("zombie.core.opengl.ShaderUnit").resolve(), locator)
                    .visit(Advice.to(ShaderUnit.loadShaderFile.class).on(named("loadShaderFile")))
                    .visit(Advice.to(ShaderUnit.preProcessShaderFile.class)
                            .on(named("preProcessShaderFile")))
                    .visit(Advice.to(ShaderUnit.processIncludeLine.class)
                            .on(named("processIncludeLine")))
                    .make().load(classLoader, ClassReloadingStrategy.of(instrumentation));
        } catch (Throwable error) {
            System.err.println("[ZD-OPT-LAB-AGENT] legacy ShaderUnit patch failed: " + error);
            error.printStackTrace(System.err);
        }
    }

    private static String resourceSha256(String resource) {
        try (InputStream input = Main.class.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) return "MISSING";
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
            StringBuilder result = new StringBuilder(64);
            for (byte value : digest.digest()) {
                result.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
            }
            return result.toString();
        } catch (Throwable error) {
            return "ERROR";
        }
    }

    public static final class FrameStepAdvice {
        @Advice.OnMethodEnter public static void enter() { PacingRuntime.onFrameStep(); }
    }

    public static final class QueueInvokeAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit() { PacingRuntime.onMainThreadWorkQueued(); }
    }

    public static final class StreamSignalAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.This Object worldStreamer) {
            StreamCoreRuntime.signal(worldStreamer);
        }
    }

    public static final class StreamLookaheadAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.This Object comparator) {
            StreamCoreRuntime.adjustLookahead(comparator);
        }
    }

    public static final class FboSetDirtyAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object nLevels,
                                    @Advice.Argument(0) long flags) {
            return FboRuntime.shouldSkipSetDirty(nLevels, flags);
        }
    }

    public static final class ChunkForageAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object receiver,
                                    @Advice.Argument(0) int chunkX,
                                    @Advice.Argument(1) int chunkY) {
            return ChunkOptimizationRuntime.handleGenForaging(receiver, chunkX, chunkY);
        }
    }

    public static final class ChunkGridLoadAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.Argument(0) Object square) {
            return ChunkOptimizationRuntime.handleLoadGridSquare(square);
        }
    }

    public static final class ChunkVehiclesAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object chunk) {
            return ChunkOptimizationRuntime.handleAddVehicles(chunk);
        }
    }

    public static final class ChunkBuildingsAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object chunk,
                                    @Advice.Argument(0) java.util.ArrayList<?> buildings) {
            return ChunkOptimizationRuntime.handleRandomizeBuildings(chunk, buildings);
        }
    }

    public static final class ChunkLuaAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.Argument(0) Object square) {
            return ChunkOptimizationRuntime.handleLuaNewSquare(square);
        }
    }

    public static final class ChunkDepthAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object shader,
                                    @Advice.Argument(0) float value) {
            return RenderOptimizationRuntime.handleChunkDepth(shader, value);
        }
    }

    public static final class DefaultShaderCompileAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Thrown Throwable error) {
            if (error == null) RenderOptimizationRuntime.afterDefaultShaderCompile();
        }
    }

    public static final class ShaderLookupAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static Object enter(@Advice.This Object owner,
                                   @Advice.Argument(0) int id) {
            return HotPathOptimizationRuntime.cachedShaderProgramById(owner, id);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.This Object owner,
                                @Advice.Argument(0) int id,
                                @Advice.Enter Object cached,
                                @Advice.Return(readOnly = false,
                                        typing = Assigner.Typing.DYNAMIC) Object result,
                                @Advice.Thrown Throwable error) {
            if (cached != null) result = cached;
            else if (error == null) {
                HotPathOptimizationRuntime.afterShaderProgramLookup(owner, id, result);
            }
        }
    }

    public static final class ShaderRegistryMutationAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Thrown Throwable error) {
            if (error == null) HotPathOptimizationRuntime.afterShaderRegistryMutation();
        }
    }

    public static final class MvpAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.Argument(0) Object program) {
            return zombie.core.opengl.ZDOptMvpFast.trySet(program);
        }
    }

    public static final class StateRunTextureAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static int enter(@Advice.This Object ring,
                                @Advice.Argument(0) Object draw,
                                @Advice.Argument(1) Object previous,
                                @Advice.Argument(2) Object style,
                                @Advice.Argument(3) Object texture0,
                                @Advice.Argument(4) Object texture1,
                                @Advice.Argument(5) Object texture2,
                                @Advice.Argument(6) byte attrib,
                                @Advice.Local("zdStateRunResult") boolean handledResult) {
            if (!HotPathOptimizationRuntime.isStateRunTextureEnabled()) return 0;
            // Vanilla dereferences style when it differs from a non-null currentStyle.
            // A null argument must therefore execute the untouched method rather than letting
            // the helper invent a different state boundary (or swallow vanilla behavior).
            if (style == null) return 0;
            handledResult = zombie.core.ZDOptStateRunFast.stateChanged(
                    ring, draw, previous, style, texture0, texture1, texture2, attrib);
            return 1;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Enter int handled,
                                @Advice.Local("zdStateRunResult") boolean handledResult,
                                @Advice.Return(readOnly = false) boolean result) {
            if (handled != 0) result = handledResult;
        }
    }

    public static final class BuildLoopAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.Argument(0) Object draws,
                                    @Advice.Argument(1) Object styles,
                                    @Advice.Argument(2) int count) {
            return zombie.core.ZDOptBuildFast.tryBuild(draws, styles, count);
        }
    }

    public static final class FboInnerLoopAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(
                @Advice.This Object self,
                @Advice.Argument(0) int playerIndex,
                @Advice.Argument(1) Object chunk,
                @Advice.Argument(2) int z) {
            return zombie.iso.fboRenderChunk.ZDOptFboPrepareFast.tryPrepare(
                    self, playerIndex, chunk, z);
        }
    }

    public static final class SameProgramBindAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.Argument(0) int requested,
                                    @Advice.FieldValue("currentlyBound") int currentlyBound) {
            return zombie.core.ZDOptSameProgramBindFast.shouldSkip(
                    requested, currentlyBound);
        }
    }

    public static final class TextureBindAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object texture,
                                    @Advice.Argument(0) int target) {
            return zombie.core.textures.ZDOptTextureBindFast.tryAlreadyBound(texture, target);
        }
    }

    public static final class GameProfilerIdleAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter() {
            return HotPathOptimizationRuntime.skipIdleGameProfilerArea();
        }

        @Advice.OnMethodExit
        public static void exit(@Advice.Enter boolean skipped,
                                @Advice.Return(readOnly = false,
                                        typing = Assigner.Typing.DYNAMIC) Object result) {
            if (skipped) result = null;
        }
    }

    public static final class PerformanceProbeIdleAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.FieldValue("name") String name) {
            return HotPathOptimizationRuntime.skipIdleProbe(name);
        }

        @Advice.OnMethodExit
        public static void exit(@Advice.Enter boolean skipped,
                                @Advice.Return(readOnly = false,
                                        typing = Assigner.Typing.DYNAMIC) Object result) {
            if (skipped) result = null;
        }
    }

    public static final class ChunkWorkerScopeAdvice {
        @Advice.OnMethodEnter public static void enter() {
            ChunkOptimizationRuntime.workerEnter();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit() {
            ChunkOptimizationRuntime.workerExit();
        }
    }

    public static final class ChunkMainScopeAdvice {
        @Advice.OnMethodEnter public static void enter() {
            ChunkOptimizationRuntime.mainEnter();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit() {
            ChunkOptimizationRuntime.mainExit();
        }
    }

    public static final class ChunkRecalcPropertiesAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.This Object square,
                                @Advice.Thrown Throwable error) {
            if (error == null) ChunkOptimizationRuntime.onRecalcPropertiesCompleted(square);
        }
    }

    public static final class ChunkRecalcNeighboursScopeAdvice {
        @Advice.OnMethodEnter public static void enter() {
            ChunkOptimizationRuntime.recalcNeighboursEnter();
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class) public static void exit() {
            ChunkOptimizationRuntime.recalcNeighboursExit();
        }
    }

    public static final class ChunkRecalc2Advice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object self,
                                    @Advice.Argument(0) Object other,
                                    @Advice.Argument(1) Object getter) {
            return ChunkOptimizationRuntime.skipRecalc2(self, other, getter);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.This Object self,
                                @Advice.Argument(0) Object other,
                                @Advice.Argument(1) Object getter,
                                @Advice.Enter boolean skipped,
                                @Advice.Thrown Throwable error) {
            if (!skipped && error == null) {
                ChunkOptimizationRuntime.afterRecalc2(self, other, getter);
            }
        }
    }

    public static final class ChunkRecalc3Advice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object self,
                                    @Advice.Argument(0) boolean both,
                                    @Advice.Argument(1) Object other,
                                    @Advice.Argument(2) Object getter) {
            return ChunkOptimizationRuntime.skipRecalc3(self, both, other, getter);
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.This Object self,
                                @Advice.Argument(0) boolean both,
                                @Advice.Argument(1) Object other,
                                @Advice.Argument(2) Object getter,
                                @Advice.Enter boolean skipped,
                                @Advice.Thrown Throwable error) {
            if (!skipped && error == null) {
                ChunkOptimizationRuntime.afterRecalc3(self, both, other, getter);
            }
        }
    }

    public static final class ChunkWorldgenScopeAdvice {
        @Advice.OnMethodEnter public static void enter() {
            ChunkOptimizationRuntime.worldgenScopeEnter();
        }
    }

    public static final class ChunkMapBiomeAdvice {
        @Advice.OnMethodEnter(skipOn = Advice.OnNonDefaultValue.class)
        public static boolean enter(@Advice.This Object owner,
                                    @Advice.Argument(0) int x,
                                    @Advice.Argument(1) int y,
                                    @Advice.Argument(2) String name,
                                    @Advice.Local("cachedBiome") Object cachedBiome) {
            boolean hit = ChunkOptimizationRuntime.mapBiomeHas(owner, x, y, name);
            if (hit) cachedBiome = ChunkOptimizationRuntime.mapBiomeLast();
            return hit;
        }

        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.This Object owner,
                                @Advice.Argument(0) int x,
                                @Advice.Argument(1) int y,
                                @Advice.Argument(2) String name,
                                @Advice.Enter boolean hit,
                                @Advice.Local("cachedBiome") Object cachedBiome,
                                @Advice.Return(readOnly = false,
                                        typing = Assigner.Typing.DYNAMIC) Object result,
                                @Advice.Thrown Throwable error) {
            if (error != null) return;
            if (hit) result = cachedBiome;
            else ChunkOptimizationRuntime.mapBiomePut(owner, x, y, name, result);
        }
    }

    public static final class PathfindingRequestAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Return(readOnly = false) int result,
                                @Advice.Thrown(readOnly = false) Throwable error) {
            if (error == null) {
                PathfindingRuntime.exercised();
                return;
            }
            result = 0;
            error = PathfindingRuntime.fallback(error);
        }
    }

    public static final class PathfindingThreadAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Thrown Throwable error) {
            PathfindingRuntime.threadFailure(error);
        }
    }

    public static final class PopManInitAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Thrown(readOnly = false) Throwable error) {
            error = PopManRuntime.loadSaveCellBridge(error);
        }
    }

    public static final class PopManSaveCellAdvice {
        @Advice.OnMethodExit(onThrowable = Throwable.class)
        public static void exit(@Advice.Thrown(readOnly = false) Throwable error) {
            error = PopManRuntime.saveCellCompleted(error);
        }
    }

    private static final class YieldReplacement
            implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {
        private final FeatureCompatibility.Decision pacing;

        YieldReplacement(FeatureCompatibility.Decision pacing) {
            this.pacing = pacing;
        }

        @Override
        public MethodVisitor wrap(TypeDescription instrumentedType,
                                  MethodDescription instrumentedMethod,
                                  MethodVisitor methodVisitor,
                                  Implementation.Context implementationContext,
                                  TypePool typePool, int writerFlags, int readerFlags) {
            return new MethodVisitor(Opcodes.ASM9, methodVisitor) {
                private int replacements;
                @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                      String descriptor, boolean isInterface) {
                    if (opcode == Opcodes.INVOKESTATIC && "java/lang/Thread".equals(owner)
                            && "yield".equals(name) && "()V".equals(descriptor)) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "com/zomdroid/agent/optimization/PacingRuntime",
                                "yieldOrPark", "()V", false);
                        replacements++;
                    } else super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                }
                @Override public void visitEnd() {
                    if (replacements != 1) {
                        PacingRuntime.disable();
                        pacing.fallback("main_loop_yield_replacements=" + replacements);
                    } else pacing.passPart("main_thread_main_loop");
                    super.visitEnd();
                }
            };
        }
    }

    private static final class WorldStreamerLoopVisitor
            implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {
        private final boolean wake;
        private final boolean queueFast;
        private final FeatureCompatibility.Decision wakeDecision;
        private final FeatureCompatibility.Decision queueDecision;

        WorldStreamerLoopVisitor(boolean wake, boolean queueFast,
                                 FeatureCompatibility.Decision wakeDecision,
                                 FeatureCompatibility.Decision queueDecision) {
            this.wake = wake;
            this.queueFast = queueFast;
            this.wakeDecision = wakeDecision;
            this.queueDecision = queueDecision;
        }
        @Override
        public MethodVisitor wrap(TypeDescription instrumentedType,
                                  MethodDescription instrumentedMethod,
                                  MethodVisitor methodVisitor,
                                  Implementation.Context implementationContext,
                                  TypePool typePool, int writerFlags, int readerFlags) {
            return new MethodVisitor(Opcodes.ASM9, methodVisitor) {
                private int sleepReplacements;
                private int sortReplacements;
                @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                      String descriptor, boolean isInterface) {
                    if (wake && opcode == Opcodes.INVOKESTATIC
                            && "java/lang/Thread".equals(owner) && "sleep".equals(name)
                            && "(J)V".equals(descriptor)) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "com/zomdroid/agent/optimization/StreamCoreRuntime",
                                "idleWait", "(J)V", false);
                        sleepReplacements++;
                    } else if (queueFast && opcode == Opcodes.INVOKESTATIC
                            && "java/util/Collections".equals(owner) && "sort".equals(name)
                            && "(Ljava/util/List;Ljava/util/Comparator;)V".equals(descriptor)) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "com/zomdroid/agent/optimization/StreamCoreRuntime",
                                "sortForStreamer",
                                "(Ljava/util/List;Ljava/util/Comparator;)V", false);
                        sortReplacements++;
                    } else super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                }
                @Override public void visitEnd() {
                    if (wake && sleepReplacements != 4) {
                        StreamCoreRuntime.disableWakeShape(sleepReplacements);
                        wakeDecision.fallback("sleep_replacements=" + sleepReplacements);
                    } else if (wake) wakeDecision.passPart("world_streamer_idle_wait");
                    if (queueFast && sortReplacements != 2) {
                        StreamCoreRuntime.disableQueueShape(sortReplacements);
                        queueDecision.fallback("sort_replacements=" + sortReplacements);
                    } else if (queueFast) queueDecision.passPart("world_streamer_queue_select");
                    super.visitEnd();
                }
            };
        }
    }

    private static final class FboDirtyGateVisitor
            implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {
        private final FeatureCompatibility.Decision budget;
        private final FeatureCompatibility.Decision coordinator;

        FboDirtyGateVisitor(FeatureCompatibility.Decision budget,
                            FeatureCompatibility.Decision coordinator) {
            this.budget = budget;
            this.coordinator = coordinator;
        }

        @Override
        public MethodVisitor wrap(TypeDescription instrumentedType,
                                  MethodDescription instrumentedMethod,
                                  MethodVisitor methodVisitor,
                                  Implementation.Context implementationContext,
                                  TypePool typePool, int writerFlags, int readerFlags) {
            return new MethodVisitor(Opcodes.ASM9, methodVisitor) {
                private int replacements;
                @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                      String descriptor, boolean isInterface) {
                    super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    if (opcode == Opcodes.INVOKEVIRTUAL
                            && "zombie/iso/fboRenderChunk/FBORenderLevels".equals(owner)
                            && "isDirty".equals(name) && "(IF)Z".equals(descriptor)) {
                        super.visitVarInsn(Opcodes.ALOAD, 0);
                        super.visitVarInsn(Opcodes.ALOAD, 1);
                        super.visitVarInsn(Opcodes.ILOAD, 2);
                        super.visitVarInsn(Opcodes.FLOAD, 3);
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "com/zomdroid/agent/optimization/FboRuntime", "allowDirty",
                                "(ZLjava/lang/Object;Ljava/lang/Object;IF)Z", false);
                        replacements++;
                    }
                }
                @Override public void visitEnd() {
                    if (replacements != 1) {
                        FboRuntime.disableBudgetShape(replacements);
                        budget.fallback("dirty_gate_replacements=" + replacements);
                        coordinator.fallback("dirty_gate_replacements=" + replacements);
                    } else {
                        budget.passPart("fbo_manager_dirty_gate");
                        coordinator.passPart("fbo_manager_dirty_gate");
                    }
                    super.visitEnd();
                }
            };
        }
    }

    private static final class RingRenderClearVisitor
            implements AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper {
        private final FeatureCompatibility.Decision decision;

        RingRenderClearVisitor(FeatureCompatibility.Decision decision) {
            this.decision = decision;
        }

        @Override
        public MethodVisitor wrap(TypeDescription instrumentedType,
                                  MethodDescription instrumentedMethod,
                                  MethodVisitor methodVisitor,
                                  Implementation.Context implementationContext,
                                  TypePool typePool, int writerFlags, int readerFlags) {
            return new MethodVisitor(Opcodes.ASM9, methodVisitor) {
                private int replacements;

                @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                      String descriptor, boolean isInterface) {
                    if (opcode == Opcodes.INVOKEVIRTUAL
                            && "gnu/trove/map/hash/TObjectIntHashMap".equals(owner)
                            && "clear".equals(name) && "()V".equals(descriptor)) {
                        super.visitMethodInsn(Opcodes.INVOKESTATIC,
                                "com/zomdroid/agent/optimization/RenderOptimizationRuntime",
                                "clearModelDrawCountsIfNotEmpty",
                                "(Lgnu/trove/map/hash/TObjectIntHashMap;)V", false);
                        replacements++;
                    } else {
                        super.visitMethodInsn(opcode, owner, name, descriptor, isInterface);
                    }
                }

                @Override public void visitEnd() {
                    if (replacements != 1) {
                        RenderOptimizationRuntime.disableRingRenderClear(
                                "ring_render_clear_replacements=" + replacements);
                    } else {
                        decision.passPart("ring_render_model_clear_tail");
                    }
                    super.visitEnd();
                }
            };
        }
    }

    private static final class TransformFailureListener extends AgentBuilder.Listener.Adapter {
        @Override
        public void onError(String typeName, ClassLoader classLoader, JavaModule module,
                            boolean loaded, Throwable throwable) {
            String reason = "transform_error=" + throwable.getClass().getSimpleName();
            if ("zombie.MainThread".equals(typeName) || "zombie.GameWindow".equals(typeName)) {
                PacingRuntime.disable();
                FeatureCompatibility.fallback("PACING", reason);
            } else if ("zombie.iso.WorldStreamer".equals(typeName)) {
                StreamCoreRuntime.disableWake();
                StreamCoreRuntime.disableQueueFast();
                FboRuntime.disableCoordinator();
                FeatureCompatibility.fallback("STREAM_WAKE", reason);
                FeatureCompatibility.fallback("STREAM_QUEUE_FAST", reason);
                FeatureCompatibility.fallback("STREAM_FBO_COORDINATOR", reason);
            } else if ("zombie.iso.WorldStreamer$ChunkComparator".equals(typeName)) {
                StreamCoreRuntime.disableLookahead();
                FeatureCompatibility.fallback("STREAM_VELOCITY_ETA", reason);
            } else if ("zombie.iso.fboRenderChunk.FBORenderChunkManager".equals(typeName)) {
                FboRuntime.disableFrameBudget();
                FboRuntime.disableCoordinator();
                FeatureCompatibility.fallback("FBO_FRAME_BUDGET", reason);
                FeatureCompatibility.fallback("STREAM_FBO_COORDINATOR", reason);
            } else if ("zombie.iso.fboRenderChunk.FBORenderLevels$NLevels".equals(typeName)) {
                FboRuntime.disableDirtyDedup();
                FeatureCompatibility.fallback("FBO_DIRTY_DEDUP", reason);
            } else if ("zombie.iso.fboRenderChunk.FBORenderCell".equals(typeName)) {
                FboInnerLoopRuntime.disable(reason);
            } else if ("zombie.core.DefaultShader".equals(typeName)) {
                RenderOptimizationRuntime.disableChunkDepth(reason);
            } else if ("zombie.core.SpriteRenderer$RingBuffer".equals(typeName)) {
                RenderOptimizationRuntime.disableRingRenderClear(reason);
                HotPathOptimizationRuntime.disableStateRunTexture(reason);
            } else if ("zombie.core.opengl.ShaderPrograms".equals(typeName)) {
                HotPathOptimizationRuntime.disableShaderLookup(reason);
            } else if ("zombie.core.skinnedmodel.model.VertexBufferObject".equals(typeName)) {
                HotPathOptimizationRuntime.disableMvp(reason);
            } else if ("zombie.core.SpriteRenderer".equals(typeName)) {
                HotPathOptimizationRuntime.disableBuildLoop(reason);
            } else if ("zombie.core.ShaderHelper".equals(typeName)) {
                SameProgramBindRuntime.disable(reason);
            } else if ("zombie.core.textures.Texture".equals(typeName)) {
                HotPathOptimizationRuntime.disableTextureBind(reason);
            } else if ("zombie.GameProfiler".equals(typeName)) {
                HotPathOptimizationRuntime.disableGameProfiler(reason);
            } else if ("zombie.core.profiling.AbstractPerformanceProfileProbe"
                    .equals(typeName)) {
                HotPathOptimizationRuntime.disableRenderStyleProbe(reason);
                HotPathOptimizationRuntime.disableExtendedProbes(reason);
            }
            System.err.println("[ZD-OPT-LAB-AGENT] " + typeName + " " + reason);
        }
    }
}
