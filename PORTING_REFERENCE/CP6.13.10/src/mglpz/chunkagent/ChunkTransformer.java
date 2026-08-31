package mglpz.chunkagent;

import java.lang.instrument.ClassFileTransformer;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * CP6.13.3 STUTTER CLEANUP + CP4.1 cumulative target map for exact Project Zomboid Build 42.20.3.
 *
 * CP4.1 keeps CP2C and CP3B deep attribution, then adds bounded fastpaths for the
 * CPU-real bottlenecks proven on-device. Each fastpath is independently gated and
 * falls back to the renamed vanilla method when structural prerequisites are absent.
 */
public final class ChunkTransformer implements ClassFileTransformer {
    private static final Map<String, List<ClassPatcher.Target>> TARGETS = new HashMap<String, List<ClassPatcher.Target>>();

    static {
        // WorldStreamer lifecycle / top-level timing.
        add("zombie/iso/WorldStreamer", "addJob", "(Lzombie/iso/IsoChunk;IIZ)V", "WS.addJob", 1);
        add("zombie/iso/WorldStreamer", "DoChunk", "(Lzombie/iso/IsoChunk;Ljava/nio/ByteBuffer;)V", "WS.DoChunk", 1);
        add("zombie/iso/WorldStreamer", "DoChunkAlways", "(Lzombie/iso/IsoChunk;Ljava/nio/ByteBuffer;)V", "WS.DoChunkAlways", 1);
        add("zombie/iso/WorldStreamer", "updateMain", "()V", "WS.updateMain", -1);
        add("zombie/iso/WorldStreamer", "addJobInstant", "(Lzombie/iso/IsoChunk;IIII)V", "WS.addJobInstant", 1);

        // IsoChunk top-level stages retained from CP2C / CP3A.
        add("zombie/iso/IsoChunk", "LoadFromDisk", "()V", "ISO.disk", 0);
        add("zombie/iso/IsoChunk", "loadInWorldStreamerThread", "()V", "ISO.worker", 0);
        add("zombie/iso/IsoChunk", "loadInMainThread", "()V", "ISO.main", 0);
        add("zombie/iso/IsoChunk", "doLoadGridsquare", "()V", "ISO.grid", 0);
        add("zombie/iso/IsoChunk", "LoadChunk", "(IILjava/nio/ByteBuffer;)Z", "ISO.loadChunk", 0);
        add("zombie/iso/IsoChunk", "LoadOrCreate", "(IILjava/nio/ByteBuffer;)Z", "LOAD.loadOrCreate", 0);
        add("zombie/iso/IsoChunk", "LoadFromBuffer", "(IILjava/nio/ByteBuffer;)Z", "LOAD.buffer", 0);
        add("zombie/iso/IsoChunk", "LoadBrandNew", "(II)Z", "LOAD.brandNew", 0);
        add("zombie/iso/IsoChunk", "LoadFromDiskOrBuffer", "(Ljava/nio/ByteBuffer;)V", "LOAD.diskOuter", 0);
        add("zombie/iso/IsoChunk", "LoadFromDiskOrBufferInternal", "(Ljava/nio/ByteBuffer;)V", "LOAD.diskInternal", 0);
        add("zombie/iso/IsoChunk", "ensureNotNull3x3", "(III)V", "SUB.ensure3x3", 0);

        // Deep LOAD / DISK helpers inside IsoChunk.
        add("zombie/iso/IsoChunk", "SafeRead", "(IILjava/nio/ByteBuffer;)Ljava/nio/ByteBuffer;", "DS.disk.safeRead", -1);
        add("zombie/iso/IsoChunk", "readFlags", "(Ljava/nio/ByteBuffer;I)[Z", "DS.disk.readFlags", 0);
        add("zombie/iso/IsoChunk", "getFromPool", "()Lzombie/iso/IsoChunk;", "DS.brand.poolGet", -1);
        add("zombie/iso/IsoChunk", "resetForStore", "()V", "DS.brand.poolReset", 0);

        // Deep MAIN helpers inside IsoChunk.
        add("zombie/iso/IsoChunk", "EnsureSurroundNotNullX", "(III)V", "DS.main.ensureX", 0);
        add("zombie/iso/IsoChunk", "EnsureSurroundNotNullY", "(III)V", "DS.main.ensureY", 0);
        add("zombie/iso/IsoChunk", "EnsureSurroundNotNull", "(III)V", "DS.main.ensureAround", 0);
        add("zombie/iso/IsoChunk", "RecalcAllWithNeighbour", "(Lzombie/iso/IsoGridSquare;Lzombie/iso/IsoDirections;I)V", "DS.main.recalcNeighbour", 0);
        add("zombie/iso/IsoChunk", "fixObjectAmbientEmittersOnAdjacentChunks", "(Lzombie/iso/IsoChunk;Lzombie/iso/IsoChunk;)V", "DS.main.ambientFix", 0);

        // Deep GRID helpers inside IsoChunk.
        add("zombie/iso/IsoChunk", "AddVehicles", "()V", "DS.grid.addVehicles", 0);
        add("zombie/iso/IsoChunk", "AddZombieZoneStory", "()V", "DS.grid.zoneStory", 0);
        add("zombie/iso/IsoChunk", "AddRanchAnimals", "()V", "DS.grid.ranchAnimals", 0);
        add("zombie/iso/IsoChunk", "CheckGrassRegrowth", "()V", "DS.grid.grassRegrowth", 0);
        add("zombie/iso/IsoChunk", "addSurvivorInHorde", "(Z)V", "DS.grid.survivorHorde", 0);
        add("zombie/iso/IsoChunk", "update", "()V", "DS.grid.chunkUpdate", 0);
        add("zombie/iso/IsoChunk", "addRagdollControllers", "()V", "DS.grid.ragdoll", 0);
        add("zombie/iso/IsoChunk", "AddCorpses", "(II)V", "DS.grid.addCorpses", 0);
        add("zombie/iso/IsoChunk", "AddBlood", "(II)V", "DS.grid.addBlood", 0);
        add("zombie/iso/IsoChunk", "addRatsAfterLoading", "(Lzombie/iso/IsoGridSquare;)V", "DS.grid.rats", 0);
        add("zombie/iso/IsoChunk", "randomizeBuildingsEtc", "(Ljava/util/ArrayList;)V", "DS.grid.randomizeBuildings", 0);
        add("zombie/iso/IsoChunk", "loadGridSquareIfNeeded", "(Lzombie/iso/IsoGridSquare;)V", "DS.grid.loadSquareIfNeeded", 0);
        add("zombie/LoadGridsquarePerformanceWorkaround", "LoadGridsquare", "(Lzombie/iso/IsoGridSquare;)V", "DS.grid.loadGridWorkaround", 0);
        add("zombie/iso/IsoChunk", "checkAdjacentChunks", "()V", "DS.grid.checkAdjacent", 0);

        // CP2C semantic fix + deep neighbour internals + disk square parsing.
        add("zombie/iso/IsoGridSquare", "RecalcProperties", "()V", "SUB.recalcProps", 0);
        add("zombie/iso/IsoGridSquare", "RecalcAllWithNeighbours", "(ZLzombie/iso/IsoGridSquare$GetSquare;)V", "SUB.recalcNeighbours", 0);
        add("zombie/iso/IsoGridSquare", "RecalcAllWithNeighbours", "(Z)V", "DS.main.recalcNeighboursMain", 0);
        add("zombie/iso/IsoGridSquare", "ReCalculateAll", "(Lzombie/iso/IsoGridSquare;)V", "DS.worker.recalculateAll1", 0);
        add("zombie/iso/IsoGridSquare", "ReCalculateAll", "(Lzombie/iso/IsoGridSquare;Lzombie/iso/IsoGridSquare$GetSquare;)V", "DS.worker.recalculateAll2", 0);
        add("zombie/iso/IsoGridSquare", "ReCalculateAll", "(ZLzombie/iso/IsoGridSquare;Lzombie/iso/IsoGridSquare$GetSquare;)V", "DS.worker.recalculateAll3", 0);
        add("zombie/iso/IsoGridSquare", "doGridNav", "(Lzombie/iso/IsoGridSquare$GetSquare;)Lzombie/iso/IsoGridSquare;", "DS.square.gridNav", 0);
        add("zombie/iso/IsoGridSquare", "load", "(Ljava/nio/ByteBuffer;IZ)V", "DS.disk.squareLoad", 0);
        add("zombie/iso/IsoGridSquare", "FixStackableObjects", "()V", "DS.disk.fixStackable", 0);
        add("zombie/iso/IsoGridSquare", "ResetIsoWorldRegion", "()V", "DS.disk.resetRegion", 0);

        // Existing vehicle stage.
        add("zombie/vehicles/VehiclesDB2", "loadChunk", "(Lzombie/iso/IsoChunk;)V", "VEH.loadChunk", 1);

        // CP3A foraging proof retained.
        add("zombie/iso/worldgen/zones/ZoneGenerator", "genForaging", "(II)V", "FORAGE.genForaging", 0);
        add("zombie/iso/worldgen/maps/BiomeMap", "getZones", "(IILzombie/iso/worldgen/maps/BiomeMap$Type;)[I", "FSUB.biomeGetZones", 0);

        // LoadOrCreate filename/header work. java.io.File.exists itself is deliberately not patched;
        // residual wall-vs-CPU around this probe identifies filesystem/scheduler stalls safely.
        add("zombie/ChunkMapFilenames", "getFilename", "(II)Ljava/io/File;", "DS.load.filename", 0);
        add("zombie/ChunkMapFilenames", "getHeader", "(II)Ljava/lang/String;", "DS.load.header", 0);

        // LoadBrandNew / CellLoader decomposition.
        add("zombie/iso/CellLoader", "LoadCellBinaryChunk", "(Lzombie/iso/IsoCell;IILzombie/iso/IsoChunk;)Z", "DS.brand.cellBinary", 3);
        add("zombie/iso/CellLoader", "DoTileObjectCreation", "(Lzombie/iso/sprite/IsoSprite;Lzombie/iso/SpriteDetails/IsoObjectType;Lzombie/iso/IsoGridSquare;Lzombie/iso/IsoCell;IIILjava/lang/String;)V", "DS.brand.tileCreate", 2);
        add("zombie/iso/IsoLot", "get", "(Lzombie/iso/MapFiles;IIIILzombie/iso/IsoChunk;)Lzombie/iso/IsoLot;", "DS.brand.isoLotGet", 5);
        add("zombie/iso/IsoLot", "put", "(Lzombie/iso/IsoLot;)V", "DS.brand.isoLotPut", -1);
        add("zombie/iso/IsoCell", "PlaceLot", "(Lzombie/iso/IsoLot;IIILzombie/iso/IsoChunk;II[Z)I", "DS.brand.placeLot", 0);
        add("zombie/iso/MapFiles", "hasCell", "(II)Z", "DS.brand.mapHasCell", 0);

        // Worldgen branch inside LoadBrandNew and later grid work.
        add("zombie/iso/worldgen/WorldGenChunk", "generateChunks", "(Lzombie/iso/worldgen/ChunksCache;)V", "DS.brand.worldgen", 0);
        add("zombie/iso/worldgen/WorldGenChunk", "genRandomChunk", "(Lzombie/iso/IsoCell;Lzombie/iso/worldgen/ChunksCache;Lzombie/iso/IsoChunk;Ljava/util/EnumMap;)V", "DS.worldgen.randomChunk", 0);
        add("zombie/iso/worldgen/WorldGenChunk", "genMapChunk", "(Lzombie/iso/IsoCell;Lzombie/iso/worldgen/ChunksCache;Lzombie/iso/IsoChunk;Ljava/util/EnumMap;)V", "DS.worldgen.mapChunk", 0);
        add("zombie/iso/worldgen/WorldGenChunk", "replaceTiles", "(Lzombie/iso/IsoCell;Lzombie/iso/worldgen/ChunksCache;Lzombie/iso/IsoChunk;II)V", "DS.worldgen.replaceTiles", 0);
        add("zombie/iso/worldgen/WorldGenChunk", "addZombieToSquare", "(Lzombie/iso/IsoGridSquare;)V", "DS.grid.worldgenZombie", 0);
        add("zombie/iso/worldgen/WorldGenChunk", "getMapBiome", "(IILjava/lang/String;)Lzombie/iso/worldgen/biomes/IBiome;", "OPT.worldgen.mapBiome", 0);
        add("zombie/basements/Basements", "onNewChunkLoaded", "(Lzombie/iso/IsoChunk;)V", "DS.brand.basements", 1);

        // Disk decode / object materialization.
        add("zombie/iso/IsoObject", "factoryFromFileInput", "(Lzombie/iso/IsoCell;Ljava/nio/ByteBuffer;)Lzombie/iso/IsoObject;", "DS.disk.objectFactory", -1);
        add("zombie/iso/IsoObject", "load", "(Ljava/nio/ByteBuffer;IZ)V", "DS.disk.objectLoad", 0);
        add("zombie/erosion/ErosionData$Chunk", "load", "(Ljava/nio/ByteBuffer;I)V", "DS.disk.erosionLoad", 0);

        // MAIN tail.
        add("zombie/iso/objects/IsoLightSwitch", "chunkLoaded", "(Lzombie/iso/IsoChunk;)V", "DS.main.lightSwitch", 0);
        add("zombie/iso/fboRenderChunk/FBORenderCutaways$ChunkLevelsData", "recreateLevel", "(I)V", "DS.main.cutawayRecreate", 0);

        // GRID / chunk integration tail: high-level operations only, all DS.* probes are ignored
        // when no deep parent is active, so normal gameplay gets only the tiny wrapper call cost.
        add("zombie/iso/CorpseCount", "chunkLoaded", "(Lzombie/iso/IsoChunk;)V", "DS.grid.corpseCount", 1);
        add("zombie/FliesSound", "chunkLoaded", "(Lzombie/iso/IsoChunk;)V", "DS.grid.fliesSound", 1);
        add("zombie/iso/NearestWalls", "chunkLoaded", "(Lzombie/iso/IsoChunk;)V", "DS.grid.nearestWalls", 0);
        add("zombie/erosion/ErosionMain", "LoadGridsquare", "(Lzombie/iso/IsoGridSquare;)V", "DS.grid.erosionSquare", 0);
        add("zombie/erosion/ErosionMain", "ChunkLoaded", "(Lzombie/iso/IsoChunk;)V", "DS.grid.erosionChunk", 0);
        add("zombie/Lua/MapObjects", "newGridSquare", "(Lzombie/iso/IsoGridSquare;)V", "DS.grid.luaNewSquare", 0);
        add("zombie/Lua/MapObjects", "loadGridSquare", "(Lzombie/iso/IsoGridSquare;)V", "DS.grid.luaLoadSquare", 0);
        add("zombie/globalObjects/SGlobalObjects", "chunkLoaded", "(II)V", "DS.grid.sGlobalObjects", -1);
        add("zombie/ReanimatedPlayers", "addReanimatedPlayersToChunk", "(Lzombie/iso/IsoChunk;)V", "DS.grid.reanimated", 1);
        add("zombie/MapCollisionData", "addChunkToWorld", "(Lzombie/iso/IsoChunk;)V", "DS.grid.collision", 1);
        add("zombie/characters/animals/AnimalPopulationManager", "addChunkToWorld", "(Lzombie/iso/IsoChunk;)V", "DS.grid.animals", 1);
        add("zombie/popman/ZombiePopulationManager", "addChunkToWorld", "(Lzombie/iso/IsoChunk;)V", "DS.grid.zombiePop", 1);
        add("zombie/pathfind/nativeCode/PathfindNative", "addChunkToWorld", "(Lzombie/iso/IsoChunk;)V", "DS.grid.pathNative", 1);
        add("zombie/pathfind/PolygonalMap2", "addChunkToWorld", "(Lzombie/iso/IsoChunk;)V", "DS.grid.polygonMap", 1);
        add("zombie/iso/objects/IsoGenerator", "chunkLoaded", "(Lzombie/iso/IsoChunk;)V", "DS.grid.generators", 0);
        add("zombie/LootRespawn", "chunkLoaded", "(Lzombie/iso/IsoChunk;)V", "DS.grid.lootRespawn", 0);
        add("zombie/vispoly/VisibilityPolygon2", "addChunkToWorld", "(Lzombie/iso/IsoChunk;)V", "DS.grid.visibility", 1);

        // CP6.2 RTHREAD batch:
        // 1) chunkDepth: skip redundant upload and, under compile-epoch guard, the HashMap lookup.
        // 2) compile hook: epoch invalidation after successful DefaultShader recompilation.
        // 3) RingBuffer.add: common glDraw path bulk-packs 36 floats + 6 shorts with vanilla fallback.
        add("zombie/core/DefaultShader", "setChunkDepth", "(F)V", "OPT.rthread.chunkDepth", 0);
        add("zombie/core/DefaultShader", "onCompileSuccess", "(Lzombie/core/opengl/ShaderProgram;)V", "OPT.rthread.defaultShaderCompile", 0);
        add("zombie/core/SpriteRenderer$RingBuffer", "render", "()V", "OPT.rthread.ringRender", -1);

        // CP6.3 render hotpaths: avoid repeated shader-registry lookup and move the MVP unchanged
        // test ahead of uniform lookup/matrix copies. Changed MVP is uploaded with one uniform
        // lookup instead of vanilla's two. Registry cache is invalidated after register/unregister.
        add("zombie/core/opengl/ShaderPrograms", "getProgramByID", "(I)Lzombie/core/opengl/ShaderProgram;", "OPT.rthread.shaderProgramLookup", -1);
        add("zombie/core/opengl/ShaderPrograms", "registerProgram", "(Lzombie/core/opengl/ShaderProgram;)V", "OPT.rthread.shaderRegistryMutation", -1);
        add("zombie/core/opengl/ShaderPrograms", "unregisterProgram", "(Lzombie/core/opengl/ShaderProgram;)V", "OPT.rthread.shaderRegistryMutation", -1);
        add("zombie/core/skinnedmodel/model/VertexBufferObject", "setModelViewProjection", "(Lzombie/core/opengl/ShaderProgram;)V", "OPT.rthread.mvp", -1);
        // CP6.8 repeated render-avalanche: cut the per-draw ShaderHelper registry chain.
        add("zombie/core/ShaderHelper", "setModelViewProjection", "()V", "OPT.rthread.shaderHelperMvp", -1);
        // CP6.13.10: authoritative same-program bind fastpath. The wrapper compares the requested
        // id against ShaderHelper.currentlyBound, so it cannot drift from external shader routes.
        add("zombie/core/ShaderHelper", "glUseProgramObjectARB", "(I)V", "OPT.rthread.sameShaderBind", -1);
        // CP6.8 common StateRun.render route: remove idle draw-probe/private-call overhead.
        add("zombie/core/SpriteRenderer$RingBuffer$StateRun", "render", "()V", "OPT.rthread.stateRunRender", -1);
        // CP6.4: attack actual StateRun fragmentation. The optimized comparator keeps vanilla
        // break order but treats distinct ready Texture wrappers that reference the same positive
        // live GL texture as equivalent. Draw census is aggregate-only; no per-draw log lines.
        add("zombie/core/SpriteRenderer$RingBuffer", "isStateChanged", "(Lzombie/core/textures/TextureDraw;Lzombie/core/textures/TextureDraw;Lzombie/core/Styles/Style;Lzombie/core/textures/Texture;Lzombie/core/textures/Texture;Lzombie/core/textures/Texture;B)Z", "OPT.rthread.stateRunTexture", -1);
        // CP6.7: strict same-state early return before isStateChanged and non-draw bypass in build loop.

        // CP6.6 mega-hotpath batch. All are independently guarded and fall back to renamed vanilla.
        add("zombie/core/SpriteRenderer", "buildDrawBuffer", "([Lzombie/core/textures/TextureDraw;[Lzombie/core/Styles/Style;I)V", "OPT.rthread.buildLoop", -1);
        add("zombie/GameProfiler", "profile", "(Ljava/lang/String;)Lzombie/GameProfiler$ProfileArea;", "OPT.rthread.gameProfiler", -1);
        // CP6.7 movingObjects/movingPost: specialize the scheduler's repeated IsoZombie cast.
        add("zombie/util/Type", "tryCastTo", "(Ljava/lang/Object;Ljava/lang/Class;)Ljava/lang/Object;", "OPT.java.typeCast", -1);

        // Remove PZ PerformanceProfileProbe overhead for an exact whitelist of the eight measured
        // hotpath probes only while GameProfiler is idle. Active profiling remains exact vanilla.
        add("zombie/core/profiling/AbstractPerformanceProfileProbe", "profile", "()Lzombie/core/profiling/AbstractPerformanceProfileProbe;", "OPT.rthread.drawProbe", -1);

        // CP6.13 keeps only two zero-allocation lifecycle wrappers so the new fixes stay OFF during
        // loading and the first 5 seconds of gameplay. GameLoadingState remains completely untouched.
        add("zombie/gameStates/IngameState", "enter", "()V", "Z69.entry.total", -1);
        add("zombie/gameStates/IngameState", "update", "()Lzombie/gameStates/GameStateMachine$StateAction;", "Z69.ingame.update", -1);
        add("zombie/iso/LightingJNI", "update", "()V", "CP613.lighting.update", -1);

        // CP6.13 production FBO/physics/moving/ragdoll fixes. No CP6.11/12 gameplay profiler roots remain.
        add("zombie/iso/fboRenderChunk/FBORenderCell", "prepareChunksForUpdating", "(I)V", "CP613.fbo.preparePass", -1);
        add("zombie/iso/fboRenderChunk/FBORenderCell", "prepareChunkForUpdating", "(ILzombie/iso/IsoChunk;I)V", "CP6134.fbo.prepareChunkFast", -1);

        add("zombie/core/physics/WorldSimulation", "updatePhysic", "()V", "CP613.physics.core", -1);

        add("zombie/MovingObjectUpdateSchedulerUpdateBucket", "update", "(I)V", "CP613.moving.bucketUpdate", -1);
        add("zombie/MovingObjectUpdateSchedulerUpdateBucket", "postupdate", "(I)V", "CP613.moving.bucketPost", -1);

        add("zombie/core/physics/RagdollController", "update", "(FLorg/lwjgl/util/vector/Vector3f;Lorg/lwjgl/util/vector/Quaternion;)V", "CP613.ragdoll.update", -1);
        add("zombie/core/physics/RagdollController", "postUpdate", "(F)V", "CP613.ragdoll.post", -1);


    }

    private static void add(String cls, String name, String desc, String stage, int chunkLocal) {
        List<ClassPatcher.Target> list = TARGETS.get(cls);
        if (list == null) {
            list = new ArrayList<ClassPatcher.Target>();
            TARGETS.put(cls, list);
        }
        list.add(new ClassPatcher.Target(name, desc, stage, chunkLocal));
    }

    public static Set<String> targetClassNames() { return TARGETS.keySet(); }

    public static boolean isTargetClassName(String binaryOrInternalName) {
        if (binaryOrInternalName == null) return false;
        return TARGETS.containsKey(binaryOrInternalName.replace('.', '/'));
    }

    @Override
    public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                            ProtectionDomain protectionDomain, byte[] classfileBuffer) {
        List<ClassPatcher.Target> targets = TARGETS.get(className);
        if (targets == null) return null;
        try {
            ClassPatcher.Result r = ClassPatcher.patch(classfileBuffer, className, targets);
            Profiler.note("MGLPZ_CHUNK_INSTRUMENT cp=CP6.13.4-HOTPATH-CPU-ALLOC baseline=CP4.1 class=" + className + " wrapped=" + r.wrapped);
            return r.wrapped == 0 ? null : r.bytes;
        } catch (Throwable t) {
            Profiler.note("MGLPZ_CHUNK_INSTRUMENT_FAIL cp=CP6.13.4-HOTPATH-CPU-ALLOC baseline=CP4.1 class=" + className + " error="
                    + t.getClass().getName() + ":" + safe(t.getMessage()));
            return null;
        }
    }

    private static String safe(String s) {
        if (s == null) return "";
        return s.replace('\n',' ').replace('\r',' ');
    }
}
