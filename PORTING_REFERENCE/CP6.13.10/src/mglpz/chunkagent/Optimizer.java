package mglpz.chunkagent;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import zombie.GameWindow;
import zombie.GameProfiler;
import zombie.SandboxOptions;
import zombie.LoadGridsquarePerformanceWorkaround;
import zombie.Lua.LuaManager;
import zombie.Lua.MapObjects;
import zombie.VirtualZombieManager;
import zombie.characters.IsoPlayer;
import zombie.core.logger.ExceptionLogger;
import zombie.core.DefaultShader;
import zombie.core.opengl.ShaderProgram;
import zombie.core.opengl.ShaderPrograms;
import zombie.core.skinnedmodel.model.VertexBufferObject;
import zombie.debug.DebugOptions;
import org.lwjgl.opengl.GL20;
import zombie.core.math.PZMath;
import zombie.core.physics.WorldSimulation;
import zombie.core.random.Rand;
import zombie.core.stash.StashSystem;
import zombie.debug.DebugLog;
import zombie.inventory.ItemContainer;
import zombie.inventory.ItemConfigurator;
import zombie.inventory.ItemPickerJava;
import zombie.iso.BuildingDef;
import zombie.iso.ContainerOverlays;
import zombie.iso.IsoChunk;
import zombie.iso.IsoGridSquare;
import zombie.iso.IsoMetaCell;
import zombie.iso.IsoMetaChunk;
import zombie.iso.IsoMetaGrid;
import zombie.iso.IsoObject;
import zombie.iso.IsoWorld;
import zombie.iso.RoomDef;
import zombie.iso.TileOverlays;
import zombie.iso.areas.IsoBuilding;
import zombie.iso.areas.IsoRoom;
import zombie.iso.enums.MetaCellPresence;
import zombie.iso.objects.IsoWorldInventoryObject;
import zombie.iso.sprite.IsoSprite;
import zombie.iso.worldgen.maps.BiomeMap;
import zombie.iso.worldgen.zones.ZoneGenerator;
import zombie.iso.zones.VehicleZone;
import zombie.iso.zones.Zone;
import zombie.network.GameClient;
import zombie.network.GameServer;
import zombie.randomizedWorld.randomizedBuilding.RandomizedBuildingBase;
import zombie.util.StringUtils;
import zombie.util.list.PZArrayList;
import zombie.vehicles.VehicleType;

/**
 * CP4.1 bounded multi-fix implementations for exact Build 42.20.3.
 *
 * Every optimization has an independent gate. Fastpaths either preserve the original operation
 * order/side effects or fail closed to the renamed vanilla method. No async/deferred chunk work is
 * introduced here: CP4.1 only removes allocation/redundant lookup/redundant pair work on the same
 * thread where vanilla already performs it.
 */
public final class Optimizer {
    private static volatile boolean forage = true;
    private static volatile boolean neighbour = true;
    private static volatile boolean grid = true;
    private static volatile boolean vehicles = true;
    private static volatile boolean buildings = true;
    private static volatile boolean lua = true;
    private static volatile boolean worldgen = true;
    // CP6.2 keeps CP6.1 upload suppression and adds a compile-epoch guard so the common path can
    // avoid ShaderProgram.getUniform("chunkDepth", GL_FLOAT) itself. The epoch is bumped only
    // after DefaultShader.onCompileSuccess returns successfully.
    private static volatile boolean rthreadChunkDepthFast = true;
    private static volatile boolean rthreadChunkDepthLookupFast = true;
    // CP6.2 device result: RingPack stays available but defaults OFF until a device A/B proves value.
    private static volatile boolean rthreadRingPackFast = false;
    private static volatile boolean rthreadRingRenderFast = true;
    // CP6.3: shader registry last-ID cache + MVP unchanged/one-lookup path.
    private static volatile boolean rthreadShaderLookupFast = true;
    private static volatile boolean rthreadMvpFast = true;
    // CP6.4: canonicalize safe same-GPU-texture wrappers during StateRun break detection.
    private static volatile boolean rthreadStateRunTextureFast = true;
    // CP6.4: aggregate draw-call census only; never emits per-draw log lines.
    private static volatile boolean rthreadDrawCensus = false;
    // CP6.6: proof-complete performance mode removes high-frequency diagnostic accounting.
    private static volatile boolean rthreadLeanTelemetry = true;
    // CP6.6: when PZ GameProfiler is idle, the unique RingBuffer "Render Style" probe can
    // return null; its sole caller already null-checks before close().
    private static volatile boolean rthreadDrawProbeFast = true;
    // CP6.6 mega-hotpath batch. Each family is independently toggleable for KEEP/DROP.
    private static volatile boolean rthreadBuildLoopFast = true;
    private static volatile boolean rthreadTextureBindFast = true;
    private static volatile boolean rthreadHotProbeFast = true; // umbrella / backward-compatible master
    private static volatile boolean rthreadBuildProbeFast = true;
    private static volatile boolean rthreadRenderWorldProbeFast = true;
    private static volatile boolean rthreadRenderCellProbeFast = true;
    private static volatile boolean rthreadSimWorldProbeFast = true;
    private static volatile boolean rthreadSimCellProbeFast = true;
    private static volatile boolean rthreadSimPhysicsProbeFast = true;
    private static volatile boolean rthreadGameProfilerFast = true;
    // CP6.7 residual Java squeeze.
    private static volatile boolean rthreadPrepareSameFast = false;
    private static volatile boolean rthreadNonDrawFast = true;
    private static volatile boolean javaZombieCastFast = true;
    private static volatile boolean rthreadSameDrawPackFast = false;
    // CP6.8: repeated render-avalanche cuts.
    private static volatile boolean rthreadShaderHelperMvpFast = true;
    private static volatile boolean rthreadStateRunRenderFast = true;
    // CP6.11: move startup zone-entry burst into the loading screen without changing IngameState order.
    private static volatile boolean zoneEntryPrestageFast = false;
    private static volatile boolean zoneChunkPreDrainFast = false;
    private static volatile double zoneChunkBudgetMs = 8.0;
    private static volatile int zoneChunkMaxPerTick = 8;
    private static volatile boolean zoneVehicleMetaPreloadFast = false;
    private static volatile long zoneVehicleMetaTimeoutMs = 15000L;
    private static final AtomicLong ZONE_CHUNK_BATCHES = new AtomicLong();
    private static final AtomicLong ZONE_CHUNK_MOVED = new AtomicLong();
    private static final AtomicLong ZONE_META_EVENTS = new AtomicLong();
    private static final AtomicLong ZONE_GUARD_FAILS = new AtomicLong();
    private static volatile long shaderRegistryEpoch = 1L;
    private static volatile long defaultShaderCompileEpoch = 1L;
    private static final Object DEFAULT_SHADER_COMPILE_LOCK = new Object();
    private static final AtomicBoolean RTHREAD_CHUNK_DEPTH_FAIL = new AtomicBoolean(false);

    // CP6.2 observability: counters are accumulated in the render thread's ThreadLocal and
    // published to atomics only once per 1024 wrapper calls. This avoids turning the measurement
    // itself into a per-StateRun atomic/CAS hotpath. A summary can therefore lag by at most
    // 1023 calls per participating thread; the bound is emitted explicitly.
    private static final int CHUNK_DEPTH_PUBLISH_MASK = 1023;
    private static final AtomicLong CD_CALLS = new AtomicLong();
    private static final AtomicLong CD_ENABLED_CALLS = new AtomicLong();
    private static final AtomicLong CD_CACHE_HITS = new AtomicLong();
    private static final AtomicLong CD_UPLOADS = new AtomicLong();
    private static final AtomicLong CD_UNIFORM_CHANGES = new AtomicLong();
    private static final AtomicLong CD_VALUE_CHANGES = new AtomicLong();
    private static final AtomicLong CD_UNIFORM_NULL = new AtomicLong();
    private static final AtomicLong CD_PROGRAM_NULL = new AtomicLong();
    private static final AtomicLong CD_FALLBACKS = new AtomicLong();
    private static final AtomicLong CD_GUARD_FAILURES = new AtomicLong();
    private static final AtomicLong CD_TOGGLE_OFF = new AtomicLong();
    private static final AtomicLong CD_PUBLISH_BATCHES = new AtomicLong();
    private static final AtomicLong CD_CACHE_THREADS = new AtomicLong();
    private static final AtomicLong CD_UNIFORM_LOOKUPS = new AtomicLong();
    private static final AtomicLong CD_LOOKUP_SKIPS = new AtomicLong();
    private static final AtomicLong CD_COMPILE_INVALIDATIONS = new AtomicLong();
    private static final AtomicLong CD_COMPILE_HOOKS = new AtomicLong();

    // CP6.6 mega-hotpath telemetry. Low-frequency snapshots only; no per-draw atomics.
    private static final AtomicLong HP_BUILD_CALLS = new AtomicLong();
    private static final AtomicLong HP_BUILD_HANDLED = new AtomicLong();
    private static final AtomicLong HP_TEX_BIND_CALLS = new AtomicLong();
    private static final AtomicLong HP_TEX_BIND_HITS = new AtomicLong();
    private static final AtomicLong HP_TEX_BIND_DEBUG_FALLBACK = new AtomicLong();
    private static final AtomicLong HP_TEX_BIND_STATE_FALLBACK = new AtomicLong();
    private static final AtomicLong HP_TEX_BIND_ID_FALLBACK = new AtomicLong();
    private static final AtomicLong HP_GP_IDLE_SKIPS = new AtomicLong();
    private static final AtomicLong HP_PROBE_RENDER_STYLE = new AtomicLong();
    private static final AtomicLong HP_PROBE_BUILD = new AtomicLong();
    private static final AtomicLong HP_PROBE_RENDER_WORLD = new AtomicLong();
    private static final AtomicLong HP_PROBE_RENDER_CELL = new AtomicLong();
    private static final AtomicLong HP_PROBE_SIM_WORLD = new AtomicLong();
    private static final AtomicLong HP_PROBE_SIM_CELL = new AtomicLong();
    private static final AtomicLong HP_PROBE_SIM_PHYSICS = new AtomicLong();
    private static final AtomicLong J67_PREPARE_CALLS = new AtomicLong();
    private static final AtomicLong J67_PREPARE_HITS = new AtomicLong();
    private static final AtomicLong J67_BUILD_NONDRAW_CHECKS = new AtomicLong();
    private static final AtomicLong J67_BUILD_NONDRAW_HITS = new AtomicLong();
    private static final AtomicLong J67_TYPE_CALLS = new AtomicLong();
    private static final AtomicLong J67_TYPE_ZOMBIE_CLASS_CALLS = new AtomicLong();
    private static final AtomicLong J67_TYPE_ZOMBIE_HITS = new AtomicLong();
    private static final AtomicLong J67_SAME_DRAW_CALLS = new AtomicLong();
    private static final AtomicLong J67_SAME_DRAW_HITS = new AtomicLong();
    private static final AtomicLong J67_SAME_DRAW_CAPACITY_FALLBACK = new AtomicLong();
    private static final AtomicLong J68_SHMVP_CALLS = new AtomicLong();
    private static final AtomicLong J68_SHMVP_HITS = new AtomicLong();
    private static final AtomicLong J68_SHMVP_CACHE_HITS = new AtomicLong();
    private static final AtomicLong J68_SHMVP_MISSES = new AtomicLong();
    private static final AtomicLong J68_SHMVP_DEBUG_FALLBACK = new AtomicLong();
    private static final AtomicLong J68_SHMVP_PROGRAM_NOOP = new AtomicLong();
    private static final AtomicLong J68_SR_RENDER_CALLS = new AtomicLong();
    private static final AtomicLong J68_SR_RENDER_HITS = new AtomicLong();
    private static final AtomicLong J68_SR_RENDER_DRAWS = new AtomicLong();
    private static final AtomicLong J68_SR_RENDER_OP_FALLBACK = new AtomicLong();
    private static final AtomicLong J68_SR_RENDER_PROFILER_FALLBACK = new AtomicLong();
    private static final int HOT_PROBE_PUBLISH_MASK = 4095;

    // CP6.2 RingBuffer.add bulk-pack telemetry is published from the render thread only every
    // 4096 calls. Values are absolute snapshots, not per-call atomics.
    private static final AtomicLong RF_CALLS = new AtomicLong();
    private static final AtomicLong RF_HANDLED = new AtomicLong();
    private static final AtomicLong RF_FALLBACK_TOGGLE = new AtomicLong();
    private static final AtomicLong RF_FALLBACK_NON_DRAW = new AtomicLong();
    private static final AtomicLong RF_FALLBACK_CAPACITY = new AtomicLong();
    private static final AtomicLong RF_FALLBACK_PREFLIGHT = new AtomicLong();
    private static final AtomicLong RF_STATE_CHANGES = new AtomicLong();
    private static final AtomicLong RF_SAME_STATE = new AtomicLong();
    private static final AtomicLong RR_CALLS = new AtomicLong();
    private static final AtomicLong RR_HANDLED = new AtomicLong();
    private static final AtomicLong RR_MODEL_CLEAR_SKIPPED = new AtomicLong();
    private static final AtomicLong RR_MODEL_CLEAR_EXECUTED = new AtomicLong();

    // CP6.3 shader registry cache telemetry (ThreadLocal deltas, 4096-call publish cadence).
    private static final int SHADER_LOOKUP_PUBLISH_MASK = 4095;
    private static final AtomicLong SP_CALLS = new AtomicLong();
    private static final AtomicLong SP_CACHE_HITS = new AtomicLong();
    private static final AtomicLong SP_CACHE_MISSES = new AtomicLong();
    private static final AtomicLong SP_CACHE_STORES = new AtomicLong();
    private static final AtomicLong SP_NULL_RESULTS = new AtomicLong();
    private static final AtomicLong SP_TOGGLE_OFF = new AtomicLong();
    private static final AtomicLong SP_REGISTRY_INVALIDATIONS = new AtomicLong();
    private static final AtomicLong SP_CACHE_THREADS = new AtomicLong();

    // CP6.3 MVP helper telemetry. Published as absolute snapshots by MGLPZMvpFast.
    private static final AtomicLong MVP_CALLS = new AtomicLong();
    private static final AtomicLong MVP_HANDLED = new AtomicLong();
    private static final AtomicLong MVP_UNCHANGED_SKIPS = new AtomicLong();
    private static final AtomicLong MVP_CHANGED_UPLOADS = new AtomicLong();
    private static final AtomicLong MVP_UNIFORM_LOOKUPS = new AtomicLong();
    private static final AtomicLong MVP_UNIFORM_NULL = new AtomicLong();
    private static final AtomicLong MVP_PROGRAM_NOOP = new AtomicLong();
    private static final AtomicLong MVP_TOGGLE_FALLBACK = new AtomicLong();

    // CP6.4 StateRun / draw census telemetry. The same-package helper publishes low-frequency
    // absolute snapshots, so there is no atomic increment on each sprite or draw call.
    private static final AtomicLong SR_CALLS = new AtomicLong();
    private static final AtomicLong SR_CHANGED = new AtomicLong();
    private static final AtomicLong SR_SAME = new AtomicLong();
    private static final AtomicLong SR_BREAK_INITIAL = new AtomicLong();
    private static final AtomicLong SR_BREAK_DRAWMODEL = new AtomicLong();
    private static final AtomicLong SR_BREAK_ATTRIB = new AtomicLong();
    private static final AtomicLong SR_BREAK_TEX0 = new AtomicLong();
    private static final AtomicLong SR_BREAK_TEX1 = new AtomicLong();
    private static final AtomicLong SR_BREAK_TEX2 = new AtomicLong();
    private static final AtomicLong SR_BREAK_PREV_MODEL = new AtomicLong();
    private static final AtomicLong SR_BREAK_OP_TRANSITION = new AtomicLong();
    private static final AtomicLong SR_BREAK_STYLE = new AtomicLong();
    private static final AtomicLong SR_TEX_WRAPPER_DIFF0 = new AtomicLong();
    private static final AtomicLong SR_TEX_WRAPPER_DIFF1 = new AtomicLong();
    private static final AtomicLong SR_TEX_WRAPPER_DIFF2 = new AtomicLong();
    private static final AtomicLong SR_TEX_EQUIV0 = new AtomicLong();
    private static final AtomicLong SR_TEX_EQUIV1 = new AtomicLong();
    private static final AtomicLong SR_TEX_EQUIV2 = new AtomicLong();
    private static final AtomicLong SR_RUNS_SAVED = new AtomicLong();
    private static final AtomicLong SR_TOGGLE_FALLBACK = new AtomicLong();
    private static final AtomicLong DRAW_CALLS = new AtomicLong();
    private static final AtomicLong DRAW_SAMPLES = new AtomicLong();
    private static final AtomicLong DRAW_VERTICES = new AtomicLong();
    private static final AtomicLong DRAW_INDICES = new AtomicLong();
    private static final AtomicLong DRAW_V4 = new AtomicLong();
    private static final AtomicLong DRAW_V16 = new AtomicLong();
    private static final AtomicLong DRAW_V64 = new AtomicLong();
    private static final AtomicLong DRAW_V256 = new AtomicLong();
    private static final AtomicLong DRAW_VLARGE = new AtomicLong();

    private static final ThreadLocal<ChunkDepthCache> CHUNK_DEPTH_CACHE = new ThreadLocal<ChunkDepthCache>() {
        @Override protected ChunkDepthCache initialValue() {
            CD_CACHE_THREADS.incrementAndGet();
            return new ChunkDepthCache();
        }
    };

    private static final ThreadLocal<ShaderLookupCache> SHADER_LOOKUP_CACHE = new ThreadLocal<ShaderLookupCache>() {
        @Override protected ShaderLookupCache initialValue() {
            SP_CACHE_THREADS.incrementAndGet();
            return new ShaderLookupCache();
        }
    };

    private static final AtomicBoolean FORAGE_FAIL = new AtomicBoolean(false);
    private static final AtomicBoolean GRID_FAIL = new AtomicBoolean(false);
    private static final AtomicBoolean VEHICLE_FAIL = new AtomicBoolean(false);
    private static final AtomicBoolean BUILDING_FAIL = new AtomicBoolean(false);
    private static final AtomicBoolean LUA_FAIL = new AtomicBoolean(false);
    private static final AtomicBoolean NEIGHBOUR_FAIL = new AtomicBoolean(false);

    private static final AtomicLong FORAGE_HANDLED = new AtomicLong();
    private static final AtomicLong GRID_HANDLED = new AtomicLong();
    private static final AtomicLong VEHICLE_HANDLED = new AtomicLong();
    private static final AtomicLong VEHICLE_INDEX_BUILDS = new AtomicLong();
    private static final AtomicLong VEHICLE_CANDIDATE_CHECKS = new AtomicLong();
    private static final AtomicLong VEHICLE_EXACT_REJECTS = new AtomicLong();
    private static final AtomicLong BUILDING_HANDLED = new AtomicLong();
    private static final AtomicLong LUA_HANDLED = new AtomicLong();
    private static final AtomicLong NEIGHBOUR_SKIP2 = new AtomicLong();
    private static final AtomicLong NEIGHBOUR_SKIP3 = new AtomicLong();
    private static final AtomicLong NEIGHBOUR_VERSION_BUMPS = new AtomicLong();
    private static final AtomicLong NEIGHBOUR_PAIR_RECORDS = new AtomicLong();
    private static final AtomicLong NEIGHBOUR_WORKER_SKIP2 = new AtomicLong();
    private static final AtomicLong NEIGHBOUR_WORKER_SKIP3 = new AtomicLong();
    private static final AtomicLong NEIGHBOUR_MAIN_SKIP2 = new AtomicLong();
    private static final AtomicLong NEIGHBOUR_MAIN_SKIP3 = new AtomicLong();
    private static final AtomicLong WORLDGEN_HIT = new AtomicLong();
    private static final AtomicLong WORLDGEN_MISS = new AtomicLong();

    private static volatile Field zoneGeneratorMapField;
    private static volatile Field propertiesDirtyField;
    private static volatile Field solidFloorCachedField;

    private static final ConcurrentHashMap<String, Boolean> CONTAINER_OVERLAY_BY_SPRITE =
            new ConcurrentHashMap<String, Boolean>();

    private static final ThreadLocal<ArrayList<RoomDef>> ROOM_DEFS = new ThreadLocal<ArrayList<RoomDef>>() {
        @Override protected ArrayList<RoomDef> initialValue() { return new ArrayList<RoomDef>(64); }
    };
    private static final ThreadLocal<IdentityHashMap<IsoBuilding, Boolean>> BUILDING_SEEN =
            new ThreadLocal<IdentityHashMap<IsoBuilding, Boolean>>() {
                @Override protected IdentityHashMap<IsoBuilding, Boolean> initialValue() {
                    return new IdentityHashMap<IsoBuilding, Boolean>(64);
                }
            };

    private static final ThreadLocal<NeighbourCtx> NEIGHBOUR = new ThreadLocal<NeighbourCtx>() {
        @Override protected NeighbourCtx initialValue() { return new NeighbourCtx(); }
    };

    private static final ThreadLocal<MapBiomeCache> MAP_BIOME_CACHE = new ThreadLocal<MapBiomeCache>() {
        @Override protected MapBiomeCache initialValue() { return new MapBiomeCache(128); }
    };

    private static final Object VEHICLE_INDEX_LOCK = new Object();
    private static final WeakHashMap<IsoMetaCell, VehicleIndex> VEHICLE_INDEX =
            new WeakHashMap<IsoMetaCell, VehicleIndex>();
    private static volatile VehicleMethods vehicleMethods;

    private static volatile MapObjectsAccess mapObjectsAccess;
    private static final ThreadLocal<Object[]> LUA_SNAPSHOT = new ThreadLocal<Object[]>() {
        @Override protected Object[] initialValue() { return new Object[16]; }
    };
    private static final ThreadLocal<Object[]> LUA_PARAMS = new ThreadLocal<Object[]>() {
        @Override protected Object[] initialValue() { return new Object[1]; }
    };

    private static final ThreadLocal<HotProbeStats> HOT_PROBE_STATS = new ThreadLocal<HotProbeStats>() {
        @Override protected HotProbeStats initialValue() { return new HotProbeStats(); }
    };

    private static final class HotProbeStats {
        long calls, gpIdle, renderStyle, build, renderWorld, renderCell, simWorld, simCell, simPhysics;
        long pGp, pRenderStyle, pBuild, pRenderWorld, pRenderCell, pSimWorld, pSimCell, pSimPhysics;
        void publishMaybe() {
            if ((calls & HOT_PROBE_PUBLISH_MASK) != 0L) return;
            publish();
        }
        void publish() {
            long d;
            d=gpIdle-pGp; if(d!=0){HP_GP_IDLE_SKIPS.addAndGet(d);pGp=gpIdle;}
            d=renderStyle-pRenderStyle; if(d!=0){HP_PROBE_RENDER_STYLE.addAndGet(d);pRenderStyle=renderStyle;}
            d=build-pBuild; if(d!=0){HP_PROBE_BUILD.addAndGet(d);pBuild=build;}
            d=renderWorld-pRenderWorld; if(d!=0){HP_PROBE_RENDER_WORLD.addAndGet(d);pRenderWorld=renderWorld;}
            d=renderCell-pRenderCell; if(d!=0){HP_PROBE_RENDER_CELL.addAndGet(d);pRenderCell=renderCell;}
            d=simWorld-pSimWorld; if(d!=0){HP_PROBE_SIM_WORLD.addAndGet(d);pSimWorld=simWorld;}
            d=simCell-pSimCell; if(d!=0){HP_PROBE_SIM_CELL.addAndGet(d);pSimCell=simCell;}
            d=simPhysics-pSimPhysics; if(d!=0){HP_PROBE_SIM_PHYSICS.addAndGet(d);pSimPhysics=simPhysics;}
        }
    }

    private Optimizer() {}

    public static void configure(String args) {
        Map<String,String> kv = parseArgs(args);
        forage = bool(kv, "forage", true);
        neighbour = bool(kv, "neighbour", true);
        grid = bool(kv, "grid", true);
        vehicles = bool(kv, "vehicles", true);
        buildings = bool(kv, "buildings", true);
        lua = bool(kv, "lua", true);
        worldgen = bool(kv, "worldgen", true);
        rthreadChunkDepthFast = bool(kv, "rthreadChunkDepthFast", true);
        rthreadChunkDepthLookupFast = bool(kv, "rthreadChunkDepthLookupFast", true);
        rthreadRingPackFast = bool(kv, "rthreadRingPackFast", false);
        rthreadRingRenderFast = bool(kv, "rthreadRingRenderFast", true);
        rthreadShaderLookupFast = bool(kv, "rthreadShaderLookupFast", true);
        rthreadMvpFast = bool(kv, "rthreadMvpFast", true);
        rthreadStateRunTextureFast = bool(kv, "rthreadStateRunTextureFast", true);
        rthreadDrawCensus = bool(kv, "rthreadDrawCensus", false);
        rthreadLeanTelemetry = bool(kv, "rthreadLeanTelemetry", true);
        rthreadDrawProbeFast = bool(kv, "rthreadDrawProbeFast", true);
        rthreadBuildLoopFast = bool(kv, "rthreadBuildLoopFast", true);
        rthreadTextureBindFast = bool(kv, "rthreadTextureBindFast", true);
        rthreadHotProbeFast = bool(kv, "rthreadHotProbeFast", true);
        rthreadBuildProbeFast = bool(kv, "rthreadBuildProbeFast", true);
        rthreadRenderWorldProbeFast = bool(kv, "rthreadRenderWorldProbeFast", true);
        rthreadRenderCellProbeFast = bool(kv, "rthreadRenderCellProbeFast", true);
        rthreadSimWorldProbeFast = bool(kv, "rthreadSimWorldProbeFast", true);
        rthreadSimCellProbeFast = bool(kv, "rthreadSimCellProbeFast", true);
        rthreadSimPhysicsProbeFast = bool(kv, "rthreadSimPhysicsProbeFast", true);
        rthreadGameProfilerFast = bool(kv, "rthreadGameProfilerFast", true);
        rthreadPrepareSameFast = bool(kv, "rthreadPrepareSameFast", false);
        rthreadNonDrawFast = bool(kv, "rthreadNonDrawFast", true);
        javaZombieCastFast = bool(kv, "javaZombieCastFast", true);
        rthreadSameDrawPackFast = bool(kv, "rthreadSameDrawPackFast", false);
        rthreadShaderHelperMvpFast = bool(kv, "rthreadShaderHelperMvpFast", true);
        rthreadStateRunRenderFast = bool(kv, "rthreadStateRunRenderFast", true);
        // CP6.11 regression rollback: CP6.10 loading prestage is RETIRED, not merely default-off.
        // Ignore any stale command-line/property request so GameLoadingState/vehicle-meta semantics
        // cannot be re-enabled accidentally by an old launch profile.
        zoneEntryPrestageFast = false;
        zoneChunkPreDrainFast = false;
        zoneChunkBudgetMs = 8.0;
        zoneChunkMaxPerTick = 8;
        zoneVehicleMetaPreloadFast = false;
        zoneVehicleMetaTimeoutMs = 15000L;
        Profiler.note("MGLPZ_CP6_13_CONFIG baseline=CP4.1 forage=" + forage
                + " neighbour=" + neighbour + " grid=" + grid
                + " vehicles=" + vehicles + " buildings=" + buildings
                + " lua=" + lua + " worldgen=" + worldgen
                + " rthreadChunkDepthFast=" + rthreadChunkDepthFast
                + " rthreadChunkDepthLookupFast=" + rthreadChunkDepthLookupFast
                + " rthreadRingPackFast=" + rthreadRingPackFast
                + " rthreadRingRenderFast=" + rthreadRingRenderFast
                + " rthreadShaderLookupFast=" + rthreadShaderLookupFast
                + " rthreadMvpFast=" + rthreadMvpFast
                + " rthreadStateRunTextureFast=" + rthreadStateRunTextureFast
                + " rthreadDrawCensus=" + rthreadDrawCensus
                + " rthreadLeanTelemetry=" + rthreadLeanTelemetry
                + " rthreadDrawProbeFast=" + rthreadDrawProbeFast
                + " rthreadBuildLoopFast=" + rthreadBuildLoopFast
                + " rthreadTextureBindFast=" + rthreadTextureBindFast
                + " rthreadHotProbeFast=" + rthreadHotProbeFast
                + " rthreadBuildProbeFast=" + rthreadBuildProbeFast
                + " rthreadRenderWorldProbeFast=" + rthreadRenderWorldProbeFast
                + " rthreadRenderCellProbeFast=" + rthreadRenderCellProbeFast
                + " rthreadSimWorldProbeFast=" + rthreadSimWorldProbeFast
                + " rthreadSimCellProbeFast=" + rthreadSimCellProbeFast
                + " rthreadSimPhysicsProbeFast=" + rthreadSimPhysicsProbeFast
                + " rthreadGameProfilerFast=" + rthreadGameProfilerFast
                + " rthreadPrepareSameFast=" + rthreadPrepareSameFast
                + " rthreadNonDrawFast=" + rthreadNonDrawFast
                + " javaZombieCastFast=" + javaZombieCastFast
                + " rthreadSameDrawPackFast=" + rthreadSameDrawPackFast
                + " rthreadShaderHelperMvpFast=" + rthreadShaderHelperMvpFast
                + " rthreadStateRunRenderFast=" + rthreadStateRunRenderFast
                + " zoneEntryPrestageFast=" + zoneEntryPrestageFast
                + " zoneChunkPreDrainFast=" + zoneChunkPreDrainFast
                + " zoneChunkBudgetMs=" + zoneChunkBudgetMs
                + " zoneChunkMaxPerTick=" + zoneChunkMaxPerTick
                + " zoneVehicleMetaPreloadFast=" + zoneVehicleMetaPreloadFast
                + " zoneVehicleMetaTimeoutMs=" + zoneVehicleMetaTimeoutMs);
    }

    /**
     * Returns true when setChunkDepth has been fully handled. False means the bytecode wrapper
     * must execute the exact renamed vanilla method. No cache state is committed until glUniform1f
     * succeeds, so GL exceptions preserve vanilla failure behavior.
     */
    public static boolean rthreadLeanTelemetryEnabled() { return rthreadLeanTelemetry; }
    public static boolean rthreadDrawProbeFastEnabled() { return rthreadDrawProbeFast; }

    /** CP6.6 exact idle-probe elision for the eight mega-hotpath families.
     * Every whitelisted caller null-checks profile() before close(); active GameProfiler always
     * falls through to vanilla. String identities originate from ldc literals in B42.20.3.
     */
    public static boolean skipIdleRenderStyleProbe(String name) {
        int kind = 0;
        if (name == "Render Style") {
            if (!rthreadDrawProbeFast) return false;
            kind = 1;
        } else {
            if (!rthreadHotProbeFast) return false;
            if (name == "buildStateDrawBuffer" || name == "buildStateUIDrawBuffer(UI)") { if (!rthreadBuildProbeFast) return false; kind = 2; }
            else if (name == "IsoWorld.render") { if (!rthreadRenderWorldProbeFast) return false; kind = 3; }
            else if (name == "IsoCell.render" || name == "IsoCell.renderTiles" || name == "IsoCell.doBuilding") { if (!rthreadRenderCellProbeFast) return false; kind = 4; }
            else if (name == "IsoWorld.update") { if (!rthreadSimWorldProbeFast) return false; kind = 5; }
            else if (name == "IsoCell.update") { if (!rthreadSimCellProbeFast) return false; kind = 6; }
            else if (name == "WorldSimulation.update") { if (!rthreadSimPhysicsProbeFast) return false; kind = 7; }
            else return false;
        }
        if (GameProfiler.isRunning()) return false;
        HotProbeStats hp = HOT_PROBE_STATS.get();
        ++hp.calls;
        if (kind == 1) ++hp.renderStyle;
        else if (kind == 2) ++hp.build;
        else if (kind == 3) ++hp.renderWorld;
        else if (kind == 4) ++hp.renderCell;
        else if (kind == 5) ++hp.simWorld;
        else if (kind == 6) ++hp.simCell;
        else if (kind == 7) ++hp.simPhysics;
        hp.publishMaybe();
        return true;
    }

    /** Exact fast gate for GameProfiler.profile(String): checkShouldMeasure() begins with the same
     * GameProfiler.isRunning() test, so false can return null without observable side effects.
     * Active profiling always executes the renamed vanilla method, preserving frame warnings.
     */
    public static boolean skipIdleGameProfilerArea() {
        if (!rthreadGameProfilerFast || GameProfiler.isRunning()) return false;
        HotProbeStats hp = HOT_PROBE_STATS.get();
        ++hp.calls; ++hp.gpIdle; hp.publishMaybe();
        return true;
    }

    public static boolean rthreadBuildLoopFastEnabled() { return rthreadBuildLoopFast; }
    public static boolean rthreadTextureBindFastEnabled() { return rthreadTextureBindFast; }
    public static boolean rthreadPrepareSameFastEnabled() { return rthreadPrepareSameFast; }
    public static boolean rthreadNonDrawFastEnabled() { return rthreadNonDrawFast; }
    public static boolean javaZombieCastFastEnabled() { return javaZombieCastFast; }
    public static boolean rthreadSameDrawPackFastEnabled() { return rthreadSameDrawPackFast; }
    public static boolean rthreadShaderHelperMvpFastEnabled() { return rthreadShaderHelperMvpFast; }
    public static boolean rthreadStateRunRenderFastEnabled() { return rthreadStateRunRenderFast; }
    public static boolean zoneEntryPrestageFastEnabled() { return zoneEntryPrestageFast; }
    public static boolean zoneChunkPreDrainFastEnabled() { return zoneEntryPrestageFast && zoneChunkPreDrainFast; }
    public static long zoneChunkBudgetNs() { return (long)(zoneChunkBudgetMs * 1000000.0); }
    public static int zoneChunkMaxPerTick() { return zoneChunkMaxPerTick; }
    public static boolean zoneVehicleMetaPreloadFastEnabled() { return zoneEntryPrestageFast && zoneVehicleMetaPreloadFast; }
    public static long zoneVehicleMetaTimeoutNs() { return zoneVehicleMetaTimeoutMs * 1000000L; }
    public static void noteZoneChunkBatch(int before,int after,int done,long durNs) {
        ZONE_CHUNK_BATCHES.incrementAndGet(); ZONE_CHUNK_MOVED.addAndGet(done);
        if (durNs >= 50000000L) Profiler.note("MGLPZ_CP6_12_PRESTAGE chunk_batch before="+before+" after="+after+" done="+done+" ms="+(durNs/1000000.0));
    }
    public static void noteZoneMeta(String event,long durNs) {
        ZONE_META_EVENTS.incrementAndGet();
        Profiler.note("MGLPZ_CP6_12_PRESTAGE meta="+event+(durNs==0L?"":" ms="+(durNs/1000000.0)));
    }
    public static void noteZoneGuardFail(String mechanism, Throwable t) {
        ZONE_GUARD_FAILS.incrementAndGet();
        Profiler.note("MGLPZ_CP6_12_PRESTAGE_GUARD_FAIL mechanism="+mechanism+" error="+(t==null?"timeout":t.getClass().getName())+" action=fallback_vanilla");
    }

    /** CP6.8: handle ShaderHelper.setModelViewProjection without repeating the registry path
     * when debug bound-shader checking is disabled. Cache validity is inherited from CP6.3's
     * registry epoch. False is returned only before any GL/MVP side effect. */
    public static boolean handleShaderHelperMvp(int id) {
        ShaderHelperMvpStats st = SHADER_HELPER_MVP_STATS.get();
        ++st.calls;
        if (!rthreadShaderHelperMvpFast) { st.publishMaybe(); return false; }
        if (id <= 0) { ++st.hits; ++st.programNoop; st.publishMaybe(); return true; }
        try {
            if (DebugOptions.instance.checks.boundShader.getValue()) {
                ++st.debugFallback; st.publishMaybe(); return false;
            }
            ShaderPrograms owner = ShaderPrograms.getInstance();
            ShaderProgram program = (ShaderProgram) cachedShaderProgramById(owner, id);
            if (program != null) ++st.cacheHits;
            else {
                ++st.misses;
                program = owner.getProgramByID(id);
            }
            if (program == null || !program.isCompiled()) {
                ++st.hits; ++st.programNoop; st.publishMaybe(); return true;
            }
            // From this point, do not catch: fallback after a partial uniform upload could
            // duplicate observable GL work. Preserve vanilla exception behavior instead.
            VertexBufferObject.setModelViewProjection(program);
            ++st.hits; st.publishMaybe(); return true;
        } catch (RuntimeException ex) {
            throw ex;
        } catch (Error err) {
            throw err;
        } catch (Throwable preflight) {
            st.publishMaybe(); return false;
        }
    }

    private static final ThreadLocal<ShaderHelperMvpStats> SHADER_HELPER_MVP_STATS = new ThreadLocal<ShaderHelperMvpStats>() {
        @Override protected ShaderHelperMvpStats initialValue() { return new ShaderHelperMvpStats(); }
    };
    private static final class ShaderHelperMvpStats {
        long calls,hits,cacheHits,misses,debugFallback,programNoop;
        void publishMaybe() { if ((calls & 4095L) == 0L) publishShaderHelperMvpFast(calls,hits,cacheHits,misses,debugFallback,programNoop); }
    }

    public static void publishShaderHelperMvpFast(long calls,long hits,long cacheHits,long misses,long debugFallback,long programNoop) {
        J68_SHMVP_CALLS.set(calls); J68_SHMVP_HITS.set(hits); J68_SHMVP_CACHE_HITS.set(cacheHits);
        J68_SHMVP_MISSES.set(misses); J68_SHMVP_DEBUG_FALLBACK.set(debugFallback); J68_SHMVP_PROGRAM_NOOP.set(programNoop);
    }
    public static void publishStateRunRenderFast(long calls,long hits,long draws,long opFallback,long profilerFallback) {
        J68_SR_RENDER_CALLS.set(calls); J68_SR_RENDER_HITS.set(hits); J68_SR_RENDER_DRAWS.set(draws);
        J68_SR_RENDER_OP_FALLBACK.set(opFallback); J68_SR_RENDER_PROFILER_FALLBACK.set(profilerFallback);
    }

    public static void publishBuildLoopFast(long calls, long handled, long nonDrawChecks, long nonDrawHits) {
        HP_BUILD_CALLS.set(calls); HP_BUILD_HANDLED.set(handled);
        J67_BUILD_NONDRAW_CHECKS.set(nonDrawChecks); J67_BUILD_NONDRAW_HITS.set(nonDrawHits);
    }

    public static void publishPrepareSameFast(long calls, long hits) {
        J67_PREPARE_CALLS.set(calls); J67_PREPARE_HITS.set(hits);
    }

    public static void publishZombieCastFast(long calls, long zombieClassCalls, long zombieHits) {
        J67_TYPE_CALLS.set(calls); J67_TYPE_ZOMBIE_CLASS_CALLS.set(zombieClassCalls); J67_TYPE_ZOMBIE_HITS.set(zombieHits);
    }

    public static void publishSameDrawPackFast(long calls, long hits, long capacityFallback) {
        J67_SAME_DRAW_CALLS.set(calls); J67_SAME_DRAW_HITS.set(hits); J67_SAME_DRAW_CAPACITY_FALLBACK.set(capacityFallback);
    }

    public static void publishTextureBindFast(long calls, long hits, long debugFallback,
                                              long stateFallback, long idFallback) {
        HP_TEX_BIND_CALLS.set(calls); HP_TEX_BIND_HITS.set(hits);
        HP_TEX_BIND_DEBUG_FALLBACK.set(debugFallback);
        HP_TEX_BIND_STATE_FALLBACK.set(stateFallback);
        HP_TEX_BIND_ID_FALLBACK.set(idFallback);
    }

    public static boolean handleChunkDepth(Object receiver, float value) {
        ChunkDepthCache c = CHUNK_DEPTH_CACHE.get();
        if (rthreadLeanTelemetry) return handleChunkDepthLean(c, receiver, value);
        ++c.calls;
        try {
            if (!rthreadChunkDepthFast) {
                ++c.toggleOff;
                ++c.fallbacks;
                return false;
            }
            ++c.enabledCalls;
            if (!(receiver instanceof DefaultShader)) {
                ++c.fallbacks;
                return false;
            }

            DefaultShader shader = (DefaultShader)receiver;
            ShaderProgram program = shader.getProgram();
            if (program == null) {
                ++c.programNull;
                ++c.fallbacks;
                return false;
            }

            final long epoch = defaultShaderCompileEpoch;
            ShaderProgram.Uniform uniform;
            final boolean cacheGenerationValid = c.program == program && c.epoch == epoch;
            if (rthreadChunkDepthLookupFast && cacheGenerationValid && c.uniform != null) {
                uniform = (ShaderProgram.Uniform)c.uniform;
                ++c.lookupSkips;
            } else {
                uniform = program.getUniform("chunkDepth", 5126);
                ++c.uniformLookups;
                if (c.program == program && c.epoch != 0L && c.epoch != epoch) ++c.compileInvalidations;
                c.program = program;
                c.epoch = epoch;
                c.uniform = uniform;
            }

            if (uniform == null) {
                ++c.uniformNull;
                return true; // vanilla setValue would be a no-op
            }

            int bits = Float.floatToRawIntBits(value);
            if (c.uniform == uniform && cacheGenerationValid && c.bitsValid && c.bits == bits) {
                ++c.cacheHits;
                return true;
            }

            boolean uniformChanged = c.lastUploadedUniform != null && c.lastUploadedUniform != uniform;
            boolean valueChanged = c.lastUploadedUniform == uniform && c.bitsValid && c.bits != bits;

            GL20.glUniform1f(uniform.loc, value);
            ++c.uploads;
            if (uniformChanged) ++c.uniformChanges;
            if (valueChanged) ++c.valueChanges;
            c.uniform = uniform;
            c.lastUploadedUniform = uniform;
            c.bits = bits;
            c.bitsValid = true;
            c.program = program;
            c.epoch = epoch;
            return true;
        } catch (Throwable t) {
            ++c.guardFailures;
            ++c.fallbacks;
            failOnce(RTHREAD_CHUNK_DEPTH_FAIL, "rthreadChunkDepth", t);
            return false;
        } finally {
            c.publishMaybe();
        }
    }

    private static boolean handleChunkDepthLean(ChunkDepthCache c, Object receiver, float value) {
        try {
            if (!rthreadChunkDepthFast || !(receiver instanceof DefaultShader)) return false;
            DefaultShader shader = (DefaultShader)receiver;
            ShaderProgram program = shader.getProgram();
            if (program == null) return false;
            final long epoch = defaultShaderCompileEpoch;
            final boolean cacheGenerationValid = c.program == program && c.epoch == epoch;
            ShaderProgram.Uniform uniform;
            if (rthreadChunkDepthLookupFast && cacheGenerationValid && c.uniform != null) {
                uniform = (ShaderProgram.Uniform)c.uniform;
            } else {
                uniform = program.getUniform("chunkDepth", 5126);
                c.program = program; c.epoch = epoch; c.uniform = uniform;
            }
            if (uniform == null) return true;
            int bits = Float.floatToRawIntBits(value);
            if (c.uniform == uniform && cacheGenerationValid && c.bitsValid && c.bits == bits) return true;
            GL20.glUniform1f(uniform.loc, value);
            c.uniform = uniform; c.lastUploadedUniform = uniform; c.bits = bits; c.bitsValid = true;
            c.program = program; c.epoch = epoch;
            return true;
        } catch (Throwable t) {
            failOnce(RTHREAD_CHUNK_DEPTH_FAIL, "rthreadChunkDepth", t);
            return false;
        }
    }

    /** Called only after the renamed vanilla DefaultShader.onCompileSuccess succeeds. */
    public static void afterDefaultShaderCompile(Object receiver, Object program) {
        synchronized (DEFAULT_SHADER_COMPILE_LOCK) {
            ++defaultShaderCompileEpoch;
        }
        if (!rthreadLeanTelemetry) CD_COMPILE_HOOKS.incrementAndGet();
    }

    public static boolean rthreadRingPackFastEnabled() {
        return rthreadRingPackFast;
    }

    public static boolean rthreadRingRenderFastEnabled() {
        return rthreadRingRenderFast;
    }

    /** Absolute low-frequency snapshot from zombie.core.MGLPZRingFast. */
    public static void publishRingFast(long calls, long handled, long toggle, long nonDraw,
                                       long capacity, long preflight, long stateChanges, long sameState) {
        RF_CALLS.set(calls);
        RF_HANDLED.set(handled);
        RF_FALLBACK_TOGGLE.set(toggle);
        RF_FALLBACK_NON_DRAW.set(nonDraw);
        RF_FALLBACK_CAPACITY.set(capacity);
        RF_FALLBACK_PREFLIGHT.set(preflight);
        RF_STATE_CHANGES.set(stateChanges);
        RF_SAME_STATE.set(sameState);
    }


    public static void publishRingRenderFast(long calls, long handled, long clearSkipped, long clearExecuted) {
        RR_CALLS.set(calls);
        RR_HANDLED.set(handled);
        RR_MODEL_CLEAR_SKIPPED.set(clearSkipped);
        RR_MODEL_CLEAR_EXECUTED.set(clearExecuted);
    }

    // ---------------------------------------------------------------------
    // CP6.3 shader registry last-ID cache.
    // A hit is valid only for the same ShaderPrograms owner, same ID, and same registry epoch.
    // registerProgram/unregisterProgram wrappers bump the epoch after vanilla mutation succeeds.
    // ---------------------------------------------------------------------

    public static Object cachedShaderProgramById(Object owner, int id) {
        ShaderLookupCache c = SHADER_LOOKUP_CACHE.get();
        if (rthreadLeanTelemetry) {
            if (!rthreadShaderLookupFast) return null;
            long epoch = shaderRegistryEpoch;
            return c.owner == owner && c.id == id && c.program != null && c.epoch == epoch ? c.program : null;
        }
        ++c.calls;
        if (!rthreadShaderLookupFast) {
            ++c.toggleOff;
            ++c.cacheMisses;
            c.publishMaybe();
            return null;
        }
        long epoch = shaderRegistryEpoch;
        if (c.owner == owner && c.id == id && c.program != null && c.epoch == epoch) {
            ++c.cacheHits;
            c.publishMaybe();
            return c.program;
        }
        ++c.cacheMisses;
        c.publishMaybe();
        return null;
    }

    public static void afterShaderProgramLookup(Object owner, int id, Object program) {
        ShaderLookupCache c = SHADER_LOOKUP_CACHE.get();
        if (program == null) {
            ++c.nullResults;
            c.owner = null;
            c.program = null;
            c.epoch = shaderRegistryEpoch;
            c.id = id;
            return;
        }
        c.owner = owner;
        c.id = id;
        c.program = program;
        c.epoch = shaderRegistryEpoch;
        if (!rthreadLeanTelemetry) ++c.cacheStores;
    }

    public static void afterShaderRegistryMutation(Object owner, Object program) {
        synchronized (Optimizer.class) { ++shaderRegistryEpoch; }
        if (!rthreadLeanTelemetry) SP_REGISTRY_INVALIDATIONS.incrementAndGet();
    }

    public static boolean rthreadMvpFastEnabled() { return rthreadMvpFast; }

    /** Absolute low-frequency snapshot from zombie.core.opengl.MGLPZMvpFast. */
    public static void publishMvpFast(long calls, long handled, long unchanged, long changedUploads,
                                      long uniformLookups, long uniformNull, long programNoop, long toggleFallback) {
        MVP_CALLS.set(calls);
        MVP_HANDLED.set(handled);
        MVP_UNCHANGED_SKIPS.set(unchanged);
        MVP_CHANGED_UPLOADS.set(changedUploads);
        MVP_UNIFORM_LOOKUPS.set(uniformLookups);
        MVP_UNIFORM_NULL.set(uniformNull);
        MVP_PROGRAM_NOOP.set(programNoop);
        MVP_TOGGLE_FALLBACK.set(toggleFallback);
    }

    public static boolean rthreadStateRunTextureFastEnabled() { return rthreadStateRunTextureFast; }
    public static boolean rthreadDrawCensusEnabled() { return rthreadDrawCensus; }

    /** CP6.6 lean StateRun proof-retention: exact calls/saved only, published every 65536 calls. */
    public static void publishStateRunLean(long calls, long saved) {
        SR_CALLS.set(calls); SR_RUNS_SAVED.set(saved);
    }

    /** Absolute low-frequency snapshot from zombie.core.MGLPZStateRunFast. */
    public static void publishStateRunFast(long calls, long changed, long same,
                                           long initial, long drawModel, long attrib,
                                           long tex0Break, long tex1Break, long tex2Break,
                                           long prevModel, long opTransition, long styleBreak,
                                           long diff0, long diff1, long diff2,
                                           long equiv0, long equiv1, long equiv2,
                                           long runsSaved, long toggleFallback) {
        SR_CALLS.set(calls); SR_CHANGED.set(changed); SR_SAME.set(same);
        SR_BREAK_INITIAL.set(initial); SR_BREAK_DRAWMODEL.set(drawModel); SR_BREAK_ATTRIB.set(attrib);
        SR_BREAK_TEX0.set(tex0Break); SR_BREAK_TEX1.set(tex1Break); SR_BREAK_TEX2.set(tex2Break);
        SR_BREAK_PREV_MODEL.set(prevModel); SR_BREAK_OP_TRANSITION.set(opTransition); SR_BREAK_STYLE.set(styleBreak);
        SR_TEX_WRAPPER_DIFF0.set(diff0); SR_TEX_WRAPPER_DIFF1.set(diff1); SR_TEX_WRAPPER_DIFF2.set(diff2);
        SR_TEX_EQUIV0.set(equiv0); SR_TEX_EQUIV1.set(equiv1); SR_TEX_EQUIV2.set(equiv2);
        SR_RUNS_SAVED.set(runsSaved); SR_TOGGLE_FALLBACK.set(toggleFallback);
    }

    /** Absolute low-frequency snapshot from the CP6.4 drawElements census. */
    public static void publishDrawCensus(long calls, long samples, long verticesSample, long indicesSample,
                                         long v4, long v16, long v64, long v256, long vLarge) {
        DRAW_CALLS.set(calls); DRAW_SAMPLES.set(samples);
        DRAW_VERTICES.set(verticesSample); DRAW_INDICES.set(indicesSample);
        DRAW_V4.set(v4); DRAW_V16.set(v16); DRAW_V64.set(v64); DRAW_V256.set(v256); DRAW_VLARGE.set(vLarge);
    }

    // ---------------------------------------------------------------------
    // Foraging: semantic rewrite of ZoneGenerator.genForaging without Streams,
    // per-zone Boolean[1024], Set allocation, or boxing in the 32x32 scan.
    // ---------------------------------------------------------------------

    public static boolean handleGenForaging(Object receiver, int chunkX, int chunkY) {
        if (!forage || !(receiver instanceof ZoneGenerator)) return false;
        final ZoneGenerator zg = (ZoneGenerator)receiver;
        final BiomeMap map;
        try {
            map = zoneGeneratorMap(zg);
        } catch (Throwable t) {
            failOnce(FORAGE_FAIL, "forage", t);
            return false;
        }
        if (map == null) return false;

        // From this point the routine mirrors vanilla ordering. Unexpected exceptions are allowed
        // to propagate rather than invoking vanilla after partial zone registration.
        IsoMetaGrid meta = IsoWorld.instance.getMetaGrid();
        int cellX = PZMath.fastfloor(((float)chunkX) / 32.0f);
        int cellY = PZMath.fastfloor(((float)chunkY) / 32.0f);
        if (meta.hasCellData(cellX, cellY) == MetaCellPresence.NOT_LOADED) {
            meta.setCellData(cellX, cellY, new IsoMetaCell(cellX, cellY));
        }
        IsoMetaChunk requested = meta.getChunkData(chunkX, chunkY);
        if (meta.hasCellData(cellX, cellY) != MetaCellPresence.LOADED || requested.doesHaveForaging()) {
            FORAGE_HANDLED.incrementAndGet();
            return true;
        }

        int tileBaseX = cellX * 256;
        int tileBaseY = cellY * 256;
        int baseChunkX = PZMath.fastfloor(((float)tileBaseX) / 8.0f);
        int baseChunkY = PZMath.fastfloor(((float)tileBaseY) / 8.0f);

        HashMap<Integer, boolean[]> grids = new HashMap<Integer, boolean[]>(16);
        for (int dx = 0; dx < 32; dx++) {
            for (int dy = 0; dy < 32; dy++) {
                int idx = dy * 32 + dx;
                int[] ids;
                try {
                    ids = map.getZones(baseChunkX + dx, baseChunkY + dy, BiomeMap.Type.ZONE);
                    if (ids == null) throw new NullPointerException("BiomeMap.getZones returned null");
                } catch (ArrayIndexOutOfBoundsException e) {
                    ids = FALLBACK_ZONE_255;
                } catch (NullPointerException e) {
                    ids = FALLBACK_ZONE_255;
                }
                boolean alreadyForaging = meta.getChunkData(baseChunkX + dx, baseChunkY + dy).doesHaveForaging();
                for (int i = 0; i < ids.length; i++) {
                    int id = ids[i];
                    boolean duplicate = false;
                    for (int j = 0; j < i; j++) {
                        if (ids[j] == id) { duplicate = true; break; }
                    }
                    if (duplicate) continue;
                    Integer key = Integer.valueOf(id);
                    boolean[] flags = grids.get(key);
                    if (flags == null) {
                        flags = new boolean[1024];
                        Arrays.fill(flags, true);
                        grids.put(key, flags);
                    }
                    flags[idx] = alreadyForaging;
                }
            }
        }

        for (Integer id : grids.keySet()) {
            ItemConfigurator.registerZone(map.getZoneName(id.intValue()));
        }

        for (Map.Entry<Integer, boolean[]> entry : grids.entrySet()) {
            int zoneId = entry.getKey().intValue();
            String zoneName = map.getZoneName(zoneId);
            if (zoneName == null) {
                DebugLog.log("Unknown foraging zone id=" + zoneId);
                continue;
            }
            boolean[] flags = entry.getValue();
            int row = 0;
            while (!allTrue(flags)) {
                int start = 0;
                boolean found = false;
                for (int col = 0; col < 32; col++) {
                    if (!flags[row * 32 + col]) {
                        start = col;
                        found = true;
                        break;
                    }
                }
                if (!found) {
                    row++;
                    continue;
                }

                int end = start;
                for (int col = start; col <= 32; col++) {
                    if (col == 32 || flags[row * 32 + col]) {
                        end = col - 1;
                        break;
                    }
                }

                int bottom = 32;
                outer:
                for (int r = row + 1; r < 32; r++) {
                    for (int col = start; col <= end; col++) {
                        if (flags[r * 32 + col]) {
                            bottom = r;
                            break outer;
                        }
                    }
                }

                meta.registerZone("", zoneName,
                        (start + baseChunkX) * 8,
                        (row + baseChunkY) * 8,
                        0,
                        (end + 1 - start) * 8,
                        (bottom - row) * 8);

                // Vanilla rechecks every cell using doesHaveZone(zoneName) after each rectangle.
                // This preserves its handling of existing/overlapping same-name zones.
                for (int dx = 0; dx < 32; dx++) {
                    for (int dy = 0; dy < 32; dy++) {
                        int idx = dy * 32 + dx;
                        if (!flags[idx]) {
                            flags[idx] = meta.getChunkData(baseChunkX + dx, baseChunkY + dy)
                                    .doesHaveZone(zoneName);
                        }
                    }
                }
                row = 0;
            }
        }
        FORAGE_HANDLED.incrementAndGet();
        return true;
    }

    private static final int[] FALLBACK_ZONE_255 = new int[]{255};

    private static boolean allTrue(boolean[] a) {
        for (int i = 0; i < a.length; i++) if (!a[i]) return false;
        return true;
    }

    private static BiomeMap zoneGeneratorMap(ZoneGenerator z) throws Exception {
        Field f = zoneGeneratorMapField;
        if (f == null) {
            f = ZoneGenerator.class.getDeclaredField("map");
            f.setAccessible(true);
            zoneGeneratorMapField = f;
        }
        return (BiomeMap)f.get(z);
    }

    // ---------------------------------------------------------------------
    // Neighbour V2: exact identity-pair cache + per-square versions.
    //
    // CP4 cleared the entire edge cache after every real RecalcProperties(). That was safe but
    // unnecessarily destructive: one dirty square could erase thousands of already-proven pairs
    // elsewhere in the same worker pass. CP4.1 keeps exact directed (A,B) identity pairs and
    // records the version of A and B at the moment vanilla completed the calculation. A real
    // RecalcProperties bumps only that square's version; pairs touching it stop matching while
    // unrelated pairs remain reusable. No coordinate hash is used for equality, so collisions
    // cannot turn an uncovered pair into a hit.
    // ---------------------------------------------------------------------

    public static void workerEnter() { neighbourScopeEnter(1); }
    public static void workerExit() { neighbourScopeExit(); }
    public static void mainEnter() { neighbourScopeEnter(2); }
    public static void mainExit() { neighbourScopeExit(); }

    private static void neighbourScopeEnter(int scope) {
        NeighbourCtx c = NEIGHBOUR.get();
        c.active = true;
        c.clear();
        c.scope = scope;
    }

    private static void neighbourScopeExit() {
        NeighbourCtx c = NEIGHBOUR.get();
        c.active = false;
        c.clear();
    }

    /** Called after an actual RecalcProperties body completed. */
    public static void invalidateNeighbourCache(Object squareObj) {
        NeighbourCtx c = NEIGHBOUR.get();
        if (!c.active || !(squareObj instanceof IsoGridSquare)) return;
        c.versions.bump((IsoGridSquare)squareObj);
        NEIGHBOUR_VERSION_BUMPS.incrementAndGet();
    }

    /** Compatibility/fail-safe entry point for old callers: clear pair coverage only. */
    public static void invalidateNeighbourCache() {
        NeighbourCtx c = NEIGHBOUR.get();
        if (c.active) c.pairs.clear();
    }

    public static boolean skipRecalc2(Object selfObj, Object otherObj, Object getter) {
        if (!neighbour) return false;
        NeighbourCtx c = NEIGHBOUR.get();
        if (!c.active || !(selfObj instanceof IsoGridSquare) || !(otherObj instanceof IsoGridSquare)) return false;
        IsoGridSquare self = (IsoGridSquare)selfObj;
        IsoGridSquare other = (IsoGridSquare)otherObj;
        if (other == null || other == self) return true; // identical to vanilla early return
        if (!prepareGetter(c, getter)) return false;
        if (isDirty(self) || isDirty(other)) return false;
        int va = c.versions.get(self), vb = c.versions.get(other);
        if (c.pairs.contains(self, other, va, vb) && c.pairs.contains(other, self, vb, va)) {
            if (!clearSolidFloor(self, other)) return false;
            NEIGHBOUR_SKIP2.incrementAndGet();
            if (c.scope == 2) NEIGHBOUR_MAIN_SKIP2.incrementAndGet(); else NEIGHBOUR_WORKER_SKIP2.incrementAndGet();
            return true;
        }
        c.pendingA = self; c.pendingB = other; c.pendingBoth = true;
        return false;
    }

    public static void afterRecalc2(Object selfObj, Object otherObj, Object getter) {
        if (!neighbour) return;
        NeighbourCtx c = NEIGHBOUR.get();
        if (!c.active) return;
        if (c.pendingA != null && c.pendingB != null) {
            int va = c.versions.get(c.pendingA), vb = c.versions.get(c.pendingB);
            if (c.pairs.put(c.pendingA, c.pendingB, va, vb)) NEIGHBOUR_PAIR_RECORDS.incrementAndGet();
            if (c.pairs.put(c.pendingB, c.pendingA, vb, va)) NEIGHBOUR_PAIR_RECORDS.incrementAndGet();
        }
        c.clearPending();
    }

    public static boolean skipRecalc3(Object selfObj, boolean both, Object otherObj, Object getter) {
        if (!neighbour) return false;
        NeighbourCtx c = NEIGHBOUR.get();
        if (!c.active || !(selfObj instanceof IsoGridSquare) || !(otherObj instanceof IsoGridSquare)) return false;
        IsoGridSquare self = (IsoGridSquare)selfObj;
        IsoGridSquare other = (IsoGridSquare)otherObj;
        if (other == null || other == self) return true; // identical to vanilla early return
        if (!prepareGetter(c, getter)) return false;
        if (isDirty(self) || (both && isDirty(other))) return false;
        int va = c.versions.get(self), vb = c.versions.get(other);
        boolean covered = c.pairs.contains(self, other, va, vb);
        if (both) covered = covered && c.pairs.contains(other, self, vb, va);
        if (covered) {
            // Vanilla invalidates solid-floor cache on both squares even when both=false.
            if (!clearSolidFloor(self, other)) return false;
            NEIGHBOUR_SKIP3.incrementAndGet();
            if (c.scope == 2) NEIGHBOUR_MAIN_SKIP3.incrementAndGet(); else NEIGHBOUR_WORKER_SKIP3.incrementAndGet();
            return true;
        }
        c.pendingA = self; c.pendingB = other; c.pendingBoth = both;
        return false;
    }

    public static void afterRecalc3(Object selfObj, boolean both, Object otherObj, Object getter) {
        if (!neighbour) return;
        NeighbourCtx c = NEIGHBOUR.get();
        if (!c.active) return;
        if (c.pendingA != null && c.pendingB != null) {
            int va = c.versions.get(c.pendingA), vb = c.versions.get(c.pendingB);
            if (c.pairs.put(c.pendingA, c.pendingB, va, vb)) NEIGHBOUR_PAIR_RECORDS.incrementAndGet();
            if (c.pendingBoth && c.pairs.put(c.pendingB, c.pendingA, vb, va)) NEIGHBOUR_PAIR_RECORDS.incrementAndGet();
        }
        c.clearPending();
    }

    private static boolean prepareGetter(NeighbourCtx c, Object getter) {
        if (c.getter == null) { c.getter = getter; return true; }
        if (c.getter != getter) {
            // A different provider can expose a different neighbourhood for CalculateCollide/
            // CalculateVisionBlocked. Pair coverage is therefore not transferable.
            c.pairs.clear();
            c.getter = getter;
        }
        return true;
    }

    private static boolean isDirty(IsoGridSquare s) {
        try {
            Field f = propertiesDirtyField;
            if (f == null) {
                f = IsoGridSquare.class.getDeclaredField("propertiesDirty");
                f.setAccessible(true);
                propertiesDirtyField = f;
            }
            return f.getBoolean(s);
        } catch (Throwable t) {
            failOnce(NEIGHBOUR_FAIL, "neighbour_dirty_field", t);
            return true; // fail closed: dirty => do not skip
        }
    }

    private static boolean clearSolidFloor(IsoGridSquare a, IsoGridSquare b) {
        try {
            Field f = solidFloorCachedField;
            if (f == null) {
                f = IsoGridSquare.class.getDeclaredField("solidFloorCached");
                f.setAccessible(true);
                solidFloorCachedField = f;
            }
            f.setBoolean(a, false);
            f.setBoolean(b, false);
            return true;
        } catch (Throwable t) {
            failOnce(NEIGHBOUR_FAIL, "neighbour_solid_floor_field", t);
            return false;
        }
    }

    // ---------------------------------------------------------------------
    // Grid square load workaround: same side effects, fewer repeated lookups and
    // cached ContainerOverlays.hasOverlays(spriteName), which is name-only in 42.20.3.
    // ---------------------------------------------------------------------

    public static boolean handleLoadGridSquare(Object squareObj) {
        if (!grid || !(squareObj instanceof IsoGridSquare)) return false;
        IsoGridSquare square = (IsoGridSquare)squareObj;
        try {
            if (square.isOverlayDone()) {
                GRID_HANDLED.incrementAndGet();
                return true;
            }
            PZArrayList<IsoObject> objects = square.getObjects();
            Object[] elements = objects.getElements();
            int count = objects.size();
            boolean client = GameClient.client;
            for (int i = 0; i < count; i++) {
                IsoObject obj = (IsoObject)elements[i];
                if (obj instanceof IsoWorldInventoryObject) continue;
                if (!client) fastItemPickerCheck(obj);
                IsoSprite sprite = obj.sprite;
                String name = sprite == null ? null : sprite.name;
                if (name != null && !containerHasOverlays(name, obj)) {
                    TileOverlays.instance.updateTileOverlaySprite(obj);
                }
            }
            square.setOverlayDone(true);
            GRID_HANDLED.incrementAndGet();
            return true;
        } catch (Throwable t) {
            // This method normally runs under IsoChunk.loadGridSquareIfNeeded's Throwable catch.
            // Re-throwing preserves the vanilla error path; only structural setup failures fall back.
            if (t instanceof RuntimeException) throw (RuntimeException)t;
            throw new RuntimeException(t);
        }
    }

    private static void fastItemPickerCheck(IsoObject obj) {
        IsoSprite sprite = obj.getSprite();
        if (sprite == null || sprite.getName() == null) return;
        ItemContainer c = obj.getContainer();
        if (c != null && !c.isExplored()) {
            ItemPickerJava.fillContainer(c, IsoPlayer.getInstance());
            c.setExplored(true);
            if (GameServer.server) GameServer.sendItemsInContainer(obj, c);
        }
        if (c != null && c.isEmpty()) return;
        ItemPickerJava.updateOverlaySprite(obj);
    }

    private static boolean containerHasOverlays(String spriteName, IsoObject obj) {
        Boolean cached = CONTAINER_OVERLAY_BY_SPRITE.get(spriteName);
        if (cached != null) return cached.booleanValue();
        boolean v = ContainerOverlays.instance.hasOverlays(obj);
        Boolean old = CONTAINER_OVERLAY_BY_SPRITE.putIfAbsent(spriteName, Boolean.valueOf(v));
        return old == null ? v : old.booleanValue();
    }

    // ---------------------------------------------------------------------
    // AddVehicles: build an ordered spatial index once per IsoMetaCell. Vanilla scans
    // every VehicleZone for every chunk; CP4.1 presents the exact same intersecting zones,
    // in original order, then invokes the same private spawn helpers.
    // ---------------------------------------------------------------------

    public static boolean handleAddVehicles(Object chunkObj) {
        if (!vehicles || !(chunkObj instanceof IsoChunk)) return false;
        IsoChunk chunk = (IsoChunk)chunkObj;
        VehicleMethods vm;
        try {
            vm = vehicleMethods();
        } catch (Throwable t) {
            failOnce(VEHICLE_FAIL, "vehicles_reflection", t);
            return false;
        }
        try {
            if (SandboxOptions.instance.carSpawnRate.getValue() == 1) {
                VEHICLE_HANDLED.incrementAndGet(); return true;
            }
            if (VehicleType.vehicles.isEmpty()) VehicleType.init();
            if (GameClient.client) { VEHICLE_HANDLED.incrementAndGet(); return true; }
            if (!SandboxOptions.instance.enableVehicles.getValue()) {
                VEHICLE_HANDLED.incrementAndGet(); return true;
            }
            if (!GameServer.server) WorldSimulation.instance.create();

            IsoMetaCell cell = IsoWorld.instance.getMetaGrid().getCellData(chunk.wx / 32, chunk.wy / 32);
            ArrayList<VehicleZone> zones = cell == null ? null : cell.vehicleZones;
            if (zones != null) {
                ArrayList<VehicleZone> candidates = vehicleCandidates(cell, zones, chunk.wx, chunk.wy);
                boolean traffic = SandboxOptions.instance.trafficJam.getValue();
                for (int i = 0; i < candidates.size(); i++) {
                    VehicleZone zone = candidates.get(i);
                    VEHICLE_CANDIDATE_CHECKS.incrementAndGet();
                    // Hardening: the index is only a candidate accelerator. Re-run the exact
                    // vanilla B42.20.3 overlap predicate before any RNG/callback side effect.
                    if (!vehicleZoneIntersectsChunk(zone, chunk.wx, chunk.wy)) {
                        VEHICLE_EXACT_REJECTS.incrementAndGet();
                        continue;
                    }
                    String name = zone.name;
                    if (name.isEmpty()) name = zone.type;
                    if (traffic) {
                        if (zone.isPolyline()) {
                            if ("TrafficJam".equalsIgnoreCase(name)) {
                                vm.trafficPolyline.invoke(chunk, zone, name);
                                continue;
                            }
                            if ("RTrafficJam".equalsIgnoreCase(name) && Rand.Next(100) < 10) {
                                vm.trafficPolyline.invoke(chunk, zone, name.replaceFirst("rtraffic", "traffic"));
                                continue;
                            }
                        }
                        if ("TrafficJamW".equalsIgnoreCase(name)) vm.trafficW.invoke(chunk, zone, name);
                        if ("TrafficJamE".equalsIgnoreCase(name)) vm.trafficE.invoke(chunk, zone, name);
                        if ("TrafficJamS".equalsIgnoreCase(name)) vm.trafficS.invoke(chunk, zone, name);
                        if ("TrafficJamN".equalsIgnoreCase(name)) vm.trafficN.invoke(chunk, zone, name);
                        if ("RTrafficJamW".equalsIgnoreCase(name) && Rand.Next(100) < 10)
                            vm.trafficW.invoke(chunk, zone, name.replaceFirst("rtraffic", "traffic"));
                        if ("RTrafficJamE".equalsIgnoreCase(name) && Rand.Next(100) < 10)
                            vm.trafficE.invoke(chunk, zone, name.replaceFirst("rtraffic", "traffic"));
                        if ("RTrafficJamS".equalsIgnoreCase(name) && Rand.Next(100) < 10)
                            vm.trafficS.invoke(chunk, zone, name.replaceFirst("rtraffic", "traffic"));
                        if ("RTrafficJamN".equalsIgnoreCase(name) && Rand.Next(100) < 10)
                            vm.trafficN.invoke(chunk, zone, name.replaceFirst("rtraffic", "traffic"));
                    }
                    if (StringUtils.containsIgnoreCase(name, "TrafficJam")) continue;
                    if ("TestVehicles".equals(name)) {
                        vm.forTest.invoke(chunk, zone);
                        continue;
                    }
                    if (!VehicleType.hasTypeForZone(name)) continue;
                    if (zone.isPolyline()) vm.onZonePolyline.invoke(chunk, zone, name);
                    else vm.onZone.invoke(chunk, zone, name);
                }
            }

            IsoMetaChunk metaChunk = IsoWorld.instance.getMetaChunk(chunk.wx, chunk.wy);
            if (metaChunk != null) {
                for (int i = 0; i < metaChunk.getZonesSize(); i++) {
                    vm.randomCrash.invoke(chunk, metaChunk.getZone(i), Boolean.FALSE);
                }
            }
            VEHICLE_HANDLED.incrementAndGet();
            return true;
        } catch (Throwable t) {
            // Private spawn helpers may throw exactly as vanilla would. Unwrap reflection wrapper.
            Throwable c = t instanceof java.lang.reflect.InvocationTargetException
                    ? ((java.lang.reflect.InvocationTargetException)t).getCause() : t;
            if (c instanceof RuntimeException) throw (RuntimeException)c;
            if (c instanceof Error) throw (Error)c;
            throw new RuntimeException(c);
        }
    }


    private static boolean vehicleZoneIntersectsChunk(VehicleZone zone, int wx, int wy) {
        int x0 = wx * 8;
        int y0 = wy * 8;
        int x1 = (wx + 1) * 8;
        int y1 = (wy + 1) * 8;
        // Exact bytecode predicate from IsoChunk.AddVehicles() B42.20.3:
        // x+w >= x0 && y+h >= y0 && x < x1 && y < y1
        return zone.x + zone.w >= x0 && zone.y + zone.h >= y0 && zone.x < x1 && zone.y < y1;
    }

    private static ArrayList<VehicleZone> vehicleCandidates(IsoMetaCell cell,
                                                             ArrayList<VehicleZone> zones,
                                                             int wx, int wy) {
        VehicleIndex idx;
        synchronized (VEHICLE_INDEX_LOCK) {
            idx = VEHICLE_INDEX.get(cell);
            if (idx == null || idx.source != zones || idx.size != zones.size()) {
                idx = new VehicleIndex(zones);
                VEHICLE_INDEX.put(cell, idx);
                VEHICLE_INDEX_BUILDS.incrementAndGet();
            }
        }
        return idx.get(wx, wy);
    }

    private static VehicleMethods vehicleMethods() throws Exception {
        VehicleMethods v = vehicleMethods;
        if (v != null) return v;
        synchronized (Optimizer.class) {
            v = vehicleMethods;
            if (v == null) vehicleMethods = v = new VehicleMethods();
        }
        return v;
    }

    // ---------------------------------------------------------------------
    // randomizeBuildingsEtc: same callbacks/order; optimize only duplicate collection
    // and loop-invariant values. IsoBuilding does not override equals in 42.20.3, so an
    // IdentityHashMap is equivalent to ArrayList.contains for this list.
    // ---------------------------------------------------------------------

    public static boolean handleRandomizeBuildings(Object chunkObj, ArrayList<?> buildingsObj) {
        if (!buildings || !(chunkObj instanceof IsoChunk) || buildingsObj == null) return false;
        IsoChunk chunk = (IsoChunk)chunkObj;
        @SuppressWarnings("unchecked") ArrayList<IsoBuilding> buildingList = (ArrayList<IsoBuilding>)buildingsObj;
        try {
            ArrayList<RoomDef> roomDefs = ROOM_DEFS.get();
            roomDefs.clear();
            IsoWorld.instance.metaGrid.getRoomsIntersecting(chunk.wx * 8 - 1, chunk.wy * 8 - 1, 9, 9, roomDefs);
            IdentityHashMap<IsoBuilding, Boolean> seen = BUILDING_SEEN.get();
            seen.clear();
            for (int i = 0; i < buildingList.size(); i++) seen.put(buildingList.get(i), Boolean.TRUE);
            for (int i = 0; i < roomDefs.size(); i++) {
                IsoRoom room = roomDefs.get(i).getIsoRoom();
                if (room == null) continue;
                IsoBuilding b = room.getBuilding();
                if (!seen.containsKey(b)) {
                    buildingList.add(b);
                    seen.put(b, Boolean.TRUE);
                }
            }

            boolean client = GameClient.client;
            for (int i = 0; i < buildingList.size(); i++) {
                IsoBuilding b = buildingList.get(i);
                if (!client && b != null && b.def != null && b.def.isFullyStreamedIn()) {
                    StashSystem.doBuildingStash(b.def);
                    if (b.def != null && StashSystem.isStashBuilding(b.def)) StashSystem.visitedBuilding(b.def);
                }
                RandomizedBuildingBase.ChunkLoaded(b);
            }

            if (!client && !buildingList.isEmpty()) {
                int minX = chunk.wx * 8;
                int minY = chunk.wy * 8;
                VirtualZombieManager vzm = VirtualZombieManager.instance;
                for (int i = 0; i < buildingList.size(); i++) {
                    IsoBuilding b = buildingList.get(i);
                    for (int j = 0; j < b.rooms.size(); j++) {
                        IsoRoom room = b.rooms.get(j);
                        RoomDef def = room.def;
                        if (!def.doneSpawn) continue;
                        if (chunk.isSpawnedRoom(def.id)) continue;
                        if (!vzm.shouldSpawnZombiesOnLevel(def.level)) continue;
                        if (!def.intersects(minX, minY, 8, 8)) continue;
                        chunk.addSpawnedRoom(def.id);
                        vzm.addIndoorZombiesToChunk(chunk, room);
                    }
                }
            }
            BUILDING_HANDLED.incrementAndGet();
            return true;
        } catch (Throwable t) {
            // No partial fallback after callbacks; propagate like vanilla.
            if (t instanceof RuntimeException) throw (RuntimeException)t;
            if (t instanceof Error) throw (Error)t;
            throw new RuntimeException(t);
        }
    }

    // ---------------------------------------------------------------------
    // MapObjects.newGridSquare: preserve callback snapshot and mutation checks while
    // replacing the shared ArrayList snapshot with a reusable Object[] and caching
    // reflective access to immutable Callback metadata.
    // ---------------------------------------------------------------------

    public static boolean handleLuaNewSquare(Object squareObj) {
        if (!lua || !(squareObj instanceof IsoGridSquare)) return false;
        final MapObjectsAccess access;
        try {
            access = mapObjectsAccess();
        } catch (Throwable t) {
            failOnce(LUA_FAIL, "lua_access", t);
            return false;
        }
        IsoGridSquare square = (IsoGridSquare)squareObj;
        if (square == null || square.getObjects().isEmpty()) { LUA_HANDLED.incrementAndGet(); return true; }
        PZArrayList<IsoObject> objects = square.getObjects();
        int n = objects.size();
        Object[] snap = LUA_SNAPSHOT.get();
        if (snap.length < n) {
            int cap = snap.length;
            while (cap < n) cap <<= 1;
            snap = new Object[cap];
            LUA_SNAPSHOT.set(snap);
        }
        Object[] elements = objects.getElements();
        System.arraycopy(elements, 0, snap, 0, n);
        Object[] params = LUA_PARAMS.get();
        for (int i = 0; i < n; i++) {
            IsoObject obj = (IsoObject)snap[i];
            if (!objects.contains(obj)) continue;
            if (obj instanceof IsoWorldInventoryObject || obj == null || obj.sprite == null) continue;
            String spriteName = obj.sprite.name != null ? obj.sprite.name : obj.spriteName;
            if (spriteName == null || spriteName.isEmpty()) continue;
            Object callback = access.onNew.get(spriteName);
            if (callback == null) continue;
            CallbackData cb = access.callback(callback);
            params[0] = obj;
            for (int f = 0; f < cb.functions.size(); f++) {
                try {
                    LuaManager.caller.protectedCallVoid(LuaManager.thread, cb.functions.get(f), params);
                } catch (Throwable t) {
                    ExceptionLogger.logException(t);
                }
                String current = obj.sprite == null ? obj.spriteName
                        : (obj.sprite.name != null ? obj.sprite.name : obj.spriteName);
                if (!objects.contains(obj) || obj.sprite == null || !cb.spriteName.equals(current)) break;
            }
        }
        for (int i = 0; i < n; i++) snap[i] = null;
        LUA_HANDLED.incrementAndGet();
        return true;
    }

    private static MapObjectsAccess mapObjectsAccess() throws Exception {
        MapObjectsAccess a = mapObjectsAccess;
        if (a != null) return a;
        synchronized (Optimizer.class) {
            a = mapObjectsAccess;
            if (a == null) mapObjectsAccess = a = new MapObjectsAccess();
        }
        return a;
    }

    // ---------------------------------------------------------------------
    // Worldgen map-biome memoization. Cache is cleared at the beginning of each
    // generateChunks scope; this only removes duplicate deterministic noise/registry lookups.
    // ---------------------------------------------------------------------

    public static void worldgenScopeEnter() {
        MAP_BIOME_CACHE.get().clear();
    }

    public static boolean mapBiomeHas(Object owner, int x, int y, String name) {
        if (!worldgen) return false;
        MapBiomeCache c = MAP_BIOME_CACHE.get();
        boolean hit = c.find(owner, x, y, name);
        if (hit) WORLDGEN_HIT.incrementAndGet(); else WORLDGEN_MISS.incrementAndGet();
        return hit;
    }

    public static Object mapBiomeLast() {
        return MAP_BIOME_CACHE.get().last;
    }

    public static void mapBiomePut(Object owner, int x, int y, String name, Object value) {
        if (worldgen) MAP_BIOME_CACHE.get().put(owner, x, y, name, value);
    }

    // ---------------------------------------------------------------------

    public static String summaryLine() {
        return "MGLPZ_CP6_13_OPT_SUMMARY baseline=CP4.1"
                + " forage_handled=" + FORAGE_HANDLED.get()
                + " neighbour_skip2=" + NEIGHBOUR_SKIP2.get()
                + " neighbour_skip3=" + NEIGHBOUR_SKIP3.get()
                + " neighbour_version_bumps=" + NEIGHBOUR_VERSION_BUMPS.get()
                + " neighbour_pair_records=" + NEIGHBOUR_PAIR_RECORDS.get()
                + " neighbour_worker_skip2=" + NEIGHBOUR_WORKER_SKIP2.get()
                + " neighbour_worker_skip3=" + NEIGHBOUR_WORKER_SKIP3.get()
                + " neighbour_main_skip2=" + NEIGHBOUR_MAIN_SKIP2.get()
                + " neighbour_main_skip3=" + NEIGHBOUR_MAIN_SKIP3.get()
                + " grid_handled=" + GRID_HANDLED.get()
                + " vehicles_handled=" + VEHICLE_HANDLED.get()
                + " vehicle_index_builds=" + VEHICLE_INDEX_BUILDS.get()
                + " vehicle_candidate_checks=" + VEHICLE_CANDIDATE_CHECKS.get()
                + " vehicle_exact_rejects=" + VEHICLE_EXACT_REJECTS.get()
                + " buildings_handled=" + BUILDING_HANDLED.get()
                + " lua_handled=" + LUA_HANDLED.get()
                + " worldgen_cache_hit=" + WORLDGEN_HIT.get()
                + " worldgen_cache_miss=" + WORLDGEN_MISS.get()
                + " rthread_chunk_depth_fast=" + rthreadChunkDepthFast
                + " rthread_chunk_depth_lookup_fast=" + rthreadChunkDepthLookupFast
                + " rthread_ring_pack_fast=" + rthreadRingPackFast
                + " rthread_ring_render_fast=" + rthreadRingRenderFast
                + " rthread_shader_lookup_fast=" + rthreadShaderLookupFast
                + " rthread_mvp_fast=" + rthreadMvpFast
                + " rthread_staterun_texture_fast=" + rthreadStateRunTextureFast
                + " rthread_draw_census=" + rthreadDrawCensus
                + " rthread_lean_telemetry=" + rthreadLeanTelemetry
                + " rthread_draw_probe_fast=" + rthreadDrawProbeFast
                + " rthread_build_loop_fast=" + rthreadBuildLoopFast
                + " rthread_texture_bind_fast=" + rthreadTextureBindFast
                + " rthread_hot_probe_fast=" + rthreadHotProbeFast
                + " rthread_build_probe_fast=" + rthreadBuildProbeFast
                + " rthread_render_world_probe_fast=" + rthreadRenderWorldProbeFast
                + " rthread_render_cell_probe_fast=" + rthreadRenderCellProbeFast
                + " rthread_sim_world_probe_fast=" + rthreadSimWorldProbeFast
                + " rthread_sim_cell_probe_fast=" + rthreadSimCellProbeFast
                + " rthread_sim_physics_probe_fast=" + rthreadSimPhysicsProbeFast
                + " rthread_game_profiler_fast=" + rthreadGameProfilerFast
                + " rthread_prepare_same_fast=" + rthreadPrepareSameFast
                + " rthread_nondraw_fast=" + rthreadNonDrawFast
                + " java_zombie_cast_fast=" + javaZombieCastFast
                + " rthread_same_draw_pack_fast=" + rthreadSameDrawPackFast
                + " rthread_shader_helper_mvp_fast=" + rthreadShaderHelperMvpFast
                + " rthread_state_run_render_fast=" + rthreadStateRunRenderFast
                + " zone_entry_prestage_fast=" + zoneEntryPrestageFast
                + " zone_chunk_pre_drain_fast=" + zoneChunkPreDrainFast
                + " zone_chunk_budget_ms=" + zoneChunkBudgetMs
                + " zone_chunk_max_per_tick=" + zoneChunkMaxPerTick
                + " zone_vehicle_meta_preload_fast=" + zoneVehicleMetaPreloadFast
                + " zone_vehicle_meta_timeout_ms=" + zoneVehicleMetaTimeoutMs
                + " zone_chunk_batches=" + ZONE_CHUNK_BATCHES.get()
                + " zone_chunk_moved=" + ZONE_CHUNK_MOVED.get()
                + " zone_meta_events=" + ZONE_META_EVENTS.get()
                + " zone_guard_fails=" + ZONE_GUARD_FAILS.get()
                + zombie.iso.MGLPZLoadPreDrain.telemetry()
                + zombie.vehicles.MGLPZVehicleMetaPreload.telemetry()
                + megaHotTelemetry()
                + chunkDepthTelemetry()
                + ringFastTelemetry()
                + shaderLookupTelemetry()
                + mvpTelemetry()
                + stateRunTelemetry()
                + drawCensusTelemetry();
    }


    private static String megaHotTelemetry() {
        long bc = HP_BUILD_CALLS.get();
        long bh = HP_BUILD_HANDLED.get();
        long tc = HP_TEX_BIND_CALLS.get();
        long th = HP_TEX_BIND_HITS.get();
        long bperm = bc == 0L ? 0L : (bh * 1000L) / bc;
        long tperm = tc == 0L ? 0L : (th * 1000L) / tc;
        return " hp_build_calls_pub=" + bc
                + " hp_build_handled_pub=" + bh
                + " hp_build_permille=" + bperm
                + " hp_tex_bind_calls_pub=" + tc
                + " hp_tex_bind_hits_pub=" + th
                + " hp_tex_bind_hit_permille=" + tperm
                + " hp_tex_bind_debug_fallback_pub=" + HP_TEX_BIND_DEBUG_FALLBACK.get()
                + " hp_tex_bind_state_fallback_pub=" + HP_TEX_BIND_STATE_FALLBACK.get()
                + " hp_tex_bind_id_fallback_pub=" + HP_TEX_BIND_ID_FALLBACK.get()
                + " hp_gp_idle_skips_pub=" + HP_GP_IDLE_SKIPS.get()
                + " hp_probe_render_style_skips_pub=" + HP_PROBE_RENDER_STYLE.get()
                + " hp_probe_build_skips_pub=" + HP_PROBE_BUILD.get()
                + " hp_probe_render_world_skips_pub=" + HP_PROBE_RENDER_WORLD.get()
                + " hp_probe_render_cell_skips_pub=" + HP_PROBE_RENDER_CELL.get()
                + " hp_probe_sim_world_skips_pub=" + HP_PROBE_SIM_WORLD.get()
                + " hp_probe_sim_cell_skips_pub=" + HP_PROBE_SIM_CELL.get()
                + " hp_probe_sim_physics_skips_pub=" + HP_PROBE_SIM_PHYSICS.get()
                + " hp_probe_pending_bound_per_thread=" + HOT_PROBE_PUBLISH_MASK
                + " j67_prepare_calls_pub=" + J67_PREPARE_CALLS.get()
                + " j67_prepare_hits_pub=" + J67_PREPARE_HITS.get()
                + " j67_prepare_hit_permille=" + (J67_PREPARE_CALLS.get()==0L?0L:(J67_PREPARE_HITS.get()*1000L)/J67_PREPARE_CALLS.get())
                + " j67_nondraw_checks_pub=" + J67_BUILD_NONDRAW_CHECKS.get()
                + " j67_nondraw_hits_pub=" + J67_BUILD_NONDRAW_HITS.get()
                + " j67_nondraw_hit_permille=" + (J67_BUILD_NONDRAW_CHECKS.get()==0L?0L:(J67_BUILD_NONDRAW_HITS.get()*1000L)/J67_BUILD_NONDRAW_CHECKS.get())
                + " j67_type_calls_pub=" + J67_TYPE_CALLS.get()
                + " j67_type_zombie_class_calls_pub=" + J67_TYPE_ZOMBIE_CLASS_CALLS.get()
                + " j67_type_zombie_hits_pub=" + J67_TYPE_ZOMBIE_HITS.get()
                + " j67_same_draw_calls_pub=" + J67_SAME_DRAW_CALLS.get()
                + " j67_same_draw_hits_pub=" + J67_SAME_DRAW_HITS.get()
                + " j67_same_draw_hit_permille=" + (J67_SAME_DRAW_CALLS.get()==0L?0L:(J67_SAME_DRAW_HITS.get()*1000L)/J67_SAME_DRAW_CALLS.get())
                + " j67_same_draw_capacity_fallback_pub=" + J67_SAME_DRAW_CAPACITY_FALLBACK.get()
                + " j68_shmvp_calls_pub=" + J68_SHMVP_CALLS.get()
                + " j68_shmvp_hits_pub=" + J68_SHMVP_HITS.get()
                + " j68_shmvp_hit_permille=" + (J68_SHMVP_CALLS.get()==0L?0L:(J68_SHMVP_HITS.get()*1000L)/J68_SHMVP_CALLS.get())
                + " j68_shmvp_cache_hits_pub=" + J68_SHMVP_CACHE_HITS.get()
                + " j68_shmvp_misses_pub=" + J68_SHMVP_MISSES.get()
                + " j68_shmvp_debug_fallback_pub=" + J68_SHMVP_DEBUG_FALLBACK.get()
                + " j68_shmvp_program_noop_pub=" + J68_SHMVP_PROGRAM_NOOP.get()
                + " j68_sr_render_calls_pub=" + J68_SR_RENDER_CALLS.get()
                + " j68_sr_render_hits_pub=" + J68_SR_RENDER_HITS.get()
                + " j68_sr_render_hit_permille=" + (J68_SR_RENDER_CALLS.get()==0L?0L:(J68_SR_RENDER_HITS.get()*1000L)/J68_SR_RENDER_CALLS.get())
                + " j68_sr_render_draws_pub=" + J68_SR_RENDER_DRAWS.get()
                + " j68_sr_render_op_fallback_pub=" + J68_SR_RENDER_OP_FALLBACK.get()
                + " j68_sr_render_profiler_fallback_pub=" + J68_SR_RENDER_PROFILER_FALLBACK.get();
    }


    private static String chunkDepthTelemetry() {
        long calls = CD_CALLS.get();
        long enabled = CD_ENABLED_CALLS.get();
        long hits = CD_CACHE_HITS.get();
        long uploads = CD_UPLOADS.get();
        long actionable = hits + uploads;
        long hitPermille = actionable == 0L ? 0L : (hits * 1000L) / actionable;
        return " cd_calls_pub=" + calls
                + " cd_enabled_pub=" + enabled
                + " cd_cache_hits_pub=" + hits
                + " cd_uploads_pub=" + uploads
                + " cd_hit_permille=" + hitPermille
                + " cd_uniform_changes_pub=" + CD_UNIFORM_CHANGES.get()
                + " cd_value_changes_pub=" + CD_VALUE_CHANGES.get()
                + " cd_uniform_null_pub=" + CD_UNIFORM_NULL.get()
                + " cd_program_null_pub=" + CD_PROGRAM_NULL.get()
                + " cd_fallbacks_pub=" + CD_FALLBACKS.get()
                + " cd_guard_failures_pub=" + CD_GUARD_FAILURES.get()
                + " cd_toggle_off_pub=" + CD_TOGGLE_OFF.get()
                + " cd_publish_batches=" + CD_PUBLISH_BATCHES.get()
                + " cd_cache_threads=" + CD_CACHE_THREADS.get()
                + " cd_uniform_lookups_pub=" + CD_UNIFORM_LOOKUPS.get()
                + " cd_lookup_skips_pub=" + CD_LOOKUP_SKIPS.get()
                + " cd_compile_invalidations_pub=" + CD_COMPILE_INVALIDATIONS.get()
                + " cd_compile_hooks=" + CD_COMPILE_HOOKS.get()
                + " cd_epoch=" + defaultShaderCompileEpoch
                + " cd_pending_bound_per_thread=1023";
    }

    private static String ringFastTelemetry() {
        long calls = RF_CALLS.get();
        long handled = RF_HANDLED.get();
        long permille = calls == 0L ? 0L : (handled * 1000L) / calls;
        return " rf_calls_pub=" + calls
                + " rf_handled_pub=" + handled
                + " rf_handled_permille=" + permille
                + " rf_fallback_toggle_pub=" + RF_FALLBACK_TOGGLE.get()
                + " rf_fallback_non_draw_pub=" + RF_FALLBACK_NON_DRAW.get()
                + " rf_fallback_capacity_pub=" + RF_FALLBACK_CAPACITY.get()
                + " rf_fallback_preflight_pub=" + RF_FALLBACK_PREFLIGHT.get()
                + " rf_state_changes_pub=" + RF_STATE_CHANGES.get()
                + " rf_same_state_pub=" + RF_SAME_STATE.get()
                + " rf_bulk_float_puts_saved_est=" + (handled * 35L)
                + " rf_bulk_short_puts_saved_est=" + (handled * 5L)
                + " rf_pending_bound_per_thread=4095"
                + " rr_calls_pub=" + RR_CALLS.get()
                + " rr_handled_pub=" + RR_HANDLED.get()
                + " rr_model_clear_skipped_pub=" + RR_MODEL_CLEAR_SKIPPED.get()
                + " rr_model_clear_executed_pub=" + RR_MODEL_CLEAR_EXECUTED.get()
                + " rr_pending_bound=255";
    }


    private static String shaderLookupTelemetry() {
        long calls = SP_CALLS.get();
        long hits = SP_CACHE_HITS.get();
        long hitPermille = calls == 0L ? 0L : (hits * 1000L) / calls;
        return " sp_calls_pub=" + calls
                + " sp_cache_hits_pub=" + hits
                + " sp_cache_misses_pub=" + SP_CACHE_MISSES.get()
                + " sp_hit_permille=" + hitPermille
                + " sp_cache_stores_pub=" + SP_CACHE_STORES.get()
                + " sp_null_results_pub=" + SP_NULL_RESULTS.get()
                + " sp_toggle_off_pub=" + SP_TOGGLE_OFF.get()
                + " sp_registry_invalidations=" + SP_REGISTRY_INVALIDATIONS.get()
                + " sp_registry_epoch=" + shaderRegistryEpoch
                + " sp_cache_threads=" + SP_CACHE_THREADS.get()
                + " sp_pending_bound_per_thread=4095";
    }

    private static String mvpTelemetry() {
        long calls = MVP_CALLS.get();
        long unchanged = MVP_UNCHANGED_SKIPS.get();
        long permille = calls == 0L ? 0L : (unchanged * 1000L) / calls;
        return " mvp_calls_pub=" + calls
                + " mvp_handled_pub=" + MVP_HANDLED.get()
                + " mvp_unchanged_skips_pub=" + unchanged
                + " mvp_unchanged_permille=" + permille
                + " mvp_changed_uploads_pub=" + MVP_CHANGED_UPLOADS.get()
                + " mvp_uniform_lookups_pub=" + MVP_UNIFORM_LOOKUPS.get()
                + " mvp_uniform_null_pub=" + MVP_UNIFORM_NULL.get()
                + " mvp_program_noop_pub=" + MVP_PROGRAM_NOOP.get()
                + " mvp_toggle_fallback_pub=" + MVP_TOGGLE_FALLBACK.get()
                + " mvp_pending_bound_per_thread=4095";
    }


    private static String stateRunTelemetry() {
        long calls = SR_CALLS.get();
        long saved = SR_RUNS_SAVED.get();
        long diff = SR_TEX_WRAPPER_DIFF0.get() + SR_TEX_WRAPPER_DIFF1.get() + SR_TEX_WRAPPER_DIFF2.get();
        long equiv = SR_TEX_EQUIV0.get() + SR_TEX_EQUIV1.get() + SR_TEX_EQUIV2.get();
        long savedPermille = calls == 0L ? 0L : (saved * 1000L) / calls;
        long equivPermille = diff == 0L ? 0L : (equiv * 1000L) / diff;
        return " sr_telemetry_mode=" + (rthreadLeanTelemetry ? "LEAN" : "DETAILED")
                + " sr_calls_pub=" + calls
                + " sr_changed_pub=" + SR_CHANGED.get()
                + " sr_same_pub=" + SR_SAME.get()
                + " sr_runs_saved_pub=" + saved
                + " sr_saved_permille=" + savedPermille
                + " sr_break_initial_pub=" + SR_BREAK_INITIAL.get()
                + " sr_break_drawmodel_pub=" + SR_BREAK_DRAWMODEL.get()
                + " sr_break_attrib_pub=" + SR_BREAK_ATTRIB.get()
                + " sr_break_tex0_pub=" + SR_BREAK_TEX0.get()
                + " sr_break_tex1_pub=" + SR_BREAK_TEX1.get()
                + " sr_break_tex2_pub=" + SR_BREAK_TEX2.get()
                + " sr_break_prev_model_pub=" + SR_BREAK_PREV_MODEL.get()
                + " sr_break_op_pub=" + SR_BREAK_OP_TRANSITION.get()
                + " sr_break_style_pub=" + SR_BREAK_STYLE.get()
                + " sr_tex_diff0_pub=" + SR_TEX_WRAPPER_DIFF0.get()
                + " sr_tex_diff1_pub=" + SR_TEX_WRAPPER_DIFF1.get()
                + " sr_tex_diff2_pub=" + SR_TEX_WRAPPER_DIFF2.get()
                + " sr_tex_equiv0_pub=" + SR_TEX_EQUIV0.get()
                + " sr_tex_equiv1_pub=" + SR_TEX_EQUIV1.get()
                + " sr_tex_equiv2_pub=" + SR_TEX_EQUIV2.get()
                + " sr_tex_equiv_permille=" + equivPermille
                + " sr_toggle_fallback_pub=" + SR_TOGGLE_FALLBACK.get()
                + " sr_pending_bound=8191";
    }

    private static String drawCensusTelemetry() {
        long calls = DRAW_CALLS.get();
        long samples = DRAW_SAMPLES.get();
        long avgVx1000 = samples == 0L ? 0L : (DRAW_VERTICES.get() * 1000L) / samples;
        return " draw_calls_pub=" + calls
                + " draw_samples_pub=" + samples
                + " draw_sample_stride=64"
                + " draw_vertices_sample_pub=" + DRAW_VERTICES.get()
                + " draw_indices_sample_pub=" + DRAW_INDICES.get()
                + " draw_sample_avg_vertices_x1000=" + avgVx1000
                + " draw_v4_sample_pub=" + DRAW_V4.get()
                + " draw_v16_sample_pub=" + DRAW_V16.get()
                + " draw_v64_sample_pub=" + DRAW_V64.get()
                + " draw_v256_sample_pub=" + DRAW_V256.get()
                + " draw_vlarge_sample_pub=" + DRAW_VLARGE.get()
                + " draw_pending_bound=4095";
    }

    private static void failOnce(AtomicBoolean once, String mechanism, Throwable t) {
        if (once.compareAndSet(false, true)) {
            Profiler.note("MGLPZ_CP6_12_GUARD_FAIL baseline=CP4.1 mechanism=" + mechanism
                    + " error=" + t.getClass().getName() + " action=fallback_original");
        }
    }

    private static boolean bool(Map<String,String> kv, String k, boolean def) {
        String v = kv.get(k);
        if (v == null) v = System.getProperty("mglpz.cp41." + k);
        if (v == null) return def;
        return "1".equals(v) || "true".equalsIgnoreCase(v) || "yes".equalsIgnoreCase(v) || "on".equalsIgnoreCase(v);
    }

    private static int integer(Map<String,String> kv, String k, int def, int min, int max) {
        String v=kv.get(k); if(v==null)v=System.getProperty("mglpz.cp41."+k); int x=def;
        if(v!=null)try{x=Integer.parseInt(v);}catch(Exception ignored){}
        return x<min?min:(x>max?max:x);
    }
    private static double dbl(Map<String,String> kv, String k, double def, double min, double max) {
        String v=kv.get(k); if(v==null)v=System.getProperty("mglpz.cp41."+k); double x=def;
        if(v!=null)try{x=Double.parseDouble(v);}catch(Exception ignored){}
        return x<min?min:(x>max?max:x);
    }

    private static Map<String,String> parseArgs(String args) {
        HashMap<String,String> out = new HashMap<String,String>();
        if (args == null || args.trim().isEmpty()) return out;
        String[] parts = args.split(",");
        for (String p : parts) {
            int eq = p.indexOf('=');
            if (eq > 0) out.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
        }
        return out;
    }

    private static final class LongSet {
        private long[] keys;
        private byte[] used;
        private int size;
        LongSet(int cap) { resize(cap); }
        void clear() { Arrays.fill(used, (byte)0); size = 0; }
        boolean contains(long k) {
            int m = keys.length - 1;
            int i = mix(k) & m;
            while (used[i] != 0) {
                if (keys[i] == k) return true;
                i = (i + 1) & m;
            }
            return false;
        }
        void add(long k) {
            if ((size + 1) * 10 >= keys.length * 7) rehash(keys.length << 1);
            int m = keys.length - 1;
            int i = mix(k) & m;
            while (used[i] != 0) {
                if (keys[i] == k) return;
                i = (i + 1) & m;
            }
            used[i] = 1; keys[i] = k; size++;
        }
        private void resize(int requested) {
            int n = 1; while (n < requested) n <<= 1;
            keys = new long[n]; used = new byte[n]; size = 0;
        }
        private void rehash(int n) {
            long[] oldK = keys; byte[] oldU = used;
            resize(n);
            for (int i=0;i<oldK.length;i++) if (oldU[i] != 0) add(oldK[i]);
        }
        private static int mix(long x) {
            x ^= x >>> 33; x *= 0xff51afd7ed558ccdL; x ^= x >>> 33;
            return (int)x;
        }
    }

    private static final class NeighbourCtx {
        boolean active;
        int scope;
        Object getter;
        final SquareVersions versions = new SquareVersions(2048);
        final DirectedPairCache pairs = new DirectedPairCache(8192);
        IsoGridSquare pendingA, pendingB;
        boolean pendingBoth;
        void clear() { scope=0; getter=null; versions.clear(); pairs.clear(); clearPending(); }
        void clearPending() { pendingA=null; pendingB=null; pendingBoth=false; }
    }

    /** Reusable identity->version map. No boxing and no coordinate aliasing. */
    private static final class SquareVersions {
        private IsoGridSquare[] keys;
        private int[] values;
        private int size;
        SquareVersions(int cap) { resize(cap); }
        void clear() { Arrays.fill(keys, null); size = 0; }
        int get(IsoGridSquare k) {
            int m = keys.length - 1;
            int i = mixIdentity(k) & m;
            while (keys[i] != null) {
                if (keys[i] == k) return values[i];
                i = (i + 1) & m;
            }
            return 0;
        }
        void bump(IsoGridSquare k) {
            if ((size + 1) * 10 >= keys.length * 7) rehash(keys.length << 1);
            int m = keys.length - 1;
            int i = mixIdentity(k) & m;
            while (keys[i] != null) {
                if (keys[i] == k) { values[i]++; return; }
                i = (i + 1) & m;
            }
            keys[i] = k; values[i] = 1; size++;
        }
        private void resize(int requested) {
            int n=1; while (n<requested) n<<=1;
            keys=new IsoGridSquare[n]; values=new int[n]; size=0;
        }
        private void rehash(int n) {
            IsoGridSquare[] ok=keys; int[] ov=values;
            resize(n);
            for (int j=0;j<ok.length;j++) if (ok[j]!=null) putRaw(ok[j],ov[j]);
        }
        private void putRaw(IsoGridSquare k, int v) {
            int m=keys.length-1, i=mixIdentity(k)&m;
            while(keys[i]!=null) i=(i+1)&m;
            keys[i]=k; values[i]=v; size++;
        }
    }

    /** Exact directed identity pair cache storing endpoint versions at calculation time. */
    private static final class DirectedPairCache {
        private IsoGridSquare[] a, b;
        private int[] va, vb;
        private int size;
        DirectedPairCache(int cap) { resize(cap); }
        void clear() { Arrays.fill(a,null); Arrays.fill(b,null); size=0; }
        boolean contains(IsoGridSquare ka, IsoGridSquare kb, int kva, int kvb) {
            int m=a.length-1, i=mixPair(ka,kb)&m;
            while(a[i]!=null) {
                if(a[i]==ka && b[i]==kb) return va[i]==kva && vb[i]==kvb;
                i=(i+1)&m;
            }
            return false;
        }
        /** @return true when a new identity pair was inserted, false when existing record updated. */
        boolean put(IsoGridSquare ka, IsoGridSquare kb, int kva, int kvb) {
            if ((size + 1) * 10 >= a.length * 7) rehash(a.length << 1);
            int m=a.length-1, i=mixPair(ka,kb)&m;
            while(a[i]!=null) {
                if(a[i]==ka && b[i]==kb) { va[i]=kva; vb[i]=kvb; return false; }
                i=(i+1)&m;
            }
            a[i]=ka; b[i]=kb; va[i]=kva; vb[i]=kvb; size++; return true;
        }
        private void resize(int requested) {
            int n=1; while(n<requested) n<<=1;
            a=new IsoGridSquare[n]; b=new IsoGridSquare[n]; va=new int[n]; vb=new int[n]; size=0;
        }
        private void rehash(int n) {
            IsoGridSquare[] oa=a, ob=b; int[] ova=va, ovb=vb;
            resize(n);
            for(int j=0;j<oa.length;j++) if(oa[j]!=null) put(oa[j],ob[j],ova[j],ovb[j]);
        }
    }

    private static int mixIdentity(Object o) {
        int x = System.identityHashCode(o);
        x ^= x >>> 16; x *= 0x7feb352d; x ^= x >>> 15; x *= 0x846ca68b; x ^= x >>> 16;
        return x;
    }
    private static int mixPair(Object a, Object b) {
        int x = mixIdentity(a) * 0x9E3779B9 + Integer.rotateLeft(mixIdentity(b), 13);
        x ^= x >>> 16; x *= 0x7feb352d; x ^= x >>> 15;
        return x;
    }

    private static final class VehicleIndex {
        final ArrayList<VehicleZone> source;
        final int size;
        final HashMap<Long, ArrayList<VehicleZone>> byChunk = new HashMap<Long, ArrayList<VehicleZone>>();
        VehicleIndex(ArrayList<VehicleZone> zones) {
            source = zones; size = zones.size();
            for (int i=0;i<zones.size();i++) {
                VehicleZone z = zones.get(i);
                int minX = Math.floorDiv(z.x, 8);
                int maxX = Math.floorDiv(z.x + z.w, 8);
                int minY = Math.floorDiv(z.y, 8);
                int maxY = Math.floorDiv(z.y + z.h, 8);
                for (int cx=minX; cx<=maxX; cx++) {
                    for (int cy=minY; cy<=maxY; cy++) {
                        long key = (((long)cx) << 32) ^ (cy & 0xffffffffL);
                        ArrayList<VehicleZone> l = byChunk.get(Long.valueOf(key));
                        if (l == null) { l = new ArrayList<VehicleZone>(4); byChunk.put(Long.valueOf(key), l); }
                        l.add(z);
                    }
                }
            }
        }
        ArrayList<VehicleZone> get(int x, int y) {
            long key = (((long)x) << 32) ^ (y & 0xffffffffL);
            ArrayList<VehicleZone> l = byChunk.get(Long.valueOf(key));
            return l == null ? EMPTY_VEHICLE_ZONES : l;
        }
    }
    private static final ArrayList<VehicleZone> EMPTY_VEHICLE_ZONES = new ArrayList<VehicleZone>(0);

    private static final class VehicleMethods {
        final Method forTest, onZone, onZonePolyline, trafficW, trafficE, trafficS, trafficN, trafficPolyline, randomCrash;
        VehicleMethods() throws Exception {
            forTest = method("AddVehicles_ForTest", Zone.class);
            onZone = method("AddVehicles_OnZone", VehicleZone.class, String.class);
            onZonePolyline = method("AddVehicles_OnZonePolyline", VehicleZone.class, String.class);
            trafficW = method("AddVehicles_TrafficJam_W", Zone.class, String.class);
            trafficE = method("AddVehicles_TrafficJam_E", Zone.class, String.class);
            trafficS = method("AddVehicles_TrafficJam_S", Zone.class, String.class);
            trafficN = method("AddVehicles_TrafficJam_N", Zone.class, String.class);
            trafficPolyline = method("AddVehicles_TrafficJam_Polyline", Zone.class, String.class);
            randomCrash = method("addRandomCarCrash", Zone.class, boolean.class);
        }
        private static Method method(String n, Class<?>... p) throws Exception {
            Method m = IsoChunk.class.getDeclaredMethod(n, p); m.setAccessible(true); return m;
        }
    }

    private static final class MapObjectsAccess {
        final HashMap<String,Object> onNew;
        final Field cbSprite, cbFunctions;
        final IdentityHashMap<Object, CallbackData> cache = new IdentityHashMap<Object, CallbackData>();
        @SuppressWarnings("unchecked") MapObjectsAccess() throws Exception {
            Field f = MapObjects.class.getDeclaredField("onNew"); f.setAccessible(true);
            onNew = (HashMap<String,Object>)f.get(null);
            Class<?> cb = Class.forName("zombie.Lua.MapObjects$Callback", false, MapObjects.class.getClassLoader());
            cbSprite = cb.getDeclaredField("spriteName"); cbSprite.setAccessible(true);
            cbFunctions = cb.getDeclaredField("functions"); cbFunctions.setAccessible(true);
        }
        @SuppressWarnings("unchecked") CallbackData callback(Object o) {
            CallbackData d = cache.get(o);
            if (d != null) return d;
            try {
                d = new CallbackData((String)cbSprite.get(o), (ArrayList<Object>)cbFunctions.get(o));
                cache.put(o,d); return d;
            } catch (Exception e) { throw new RuntimeException(e); }
        }
    }
    private static final class CallbackData {
        final String spriteName; final ArrayList<Object> functions;
        CallbackData(String s, ArrayList<Object> f) { spriteName=s; functions=f; }
    }

    private static final class ShaderLookupCache {
        Object owner;
        Object program;
        int id;
        long epoch;
        long calls, cacheHits, cacheMisses, cacheStores, nullResults, toggleOff;
        long pCalls, pCacheHits, pCacheMisses, pCacheStores, pNullResults, pToggleOff;

        void publishMaybe() {
            if ((calls & SHADER_LOOKUP_PUBLISH_MASK) != 0L) return;
            publish();
        }
        void publish() {
            addDelta(SP_CALLS, calls - pCalls); pCalls = calls;
            addDelta(SP_CACHE_HITS, cacheHits - pCacheHits); pCacheHits = cacheHits;
            addDelta(SP_CACHE_MISSES, cacheMisses - pCacheMisses); pCacheMisses = cacheMisses;
            addDelta(SP_CACHE_STORES, cacheStores - pCacheStores); pCacheStores = cacheStores;
            addDelta(SP_NULL_RESULTS, nullResults - pNullResults); pNullResults = nullResults;
            addDelta(SP_TOGGLE_OFF, toggleOff - pToggleOff); pToggleOff = toggleOff;
        }
        private static void addDelta(AtomicLong dst, long delta) { if (delta != 0L) dst.addAndGet(delta); }
    }

    private static final class ChunkDepthCache {
        Object program;
        Object uniform;
        Object lastUploadedUniform;
        long epoch;
        int bits;
        boolean bitsValid;

        long calls, enabledCalls, cacheHits, uploads, uniformChanges, valueChanges;
        long uniformNull, programNull, fallbacks, guardFailures, toggleOff;
        long uniformLookups, lookupSkips, compileInvalidations;
        long pCalls, pEnabledCalls, pCacheHits, pUploads, pUniformChanges, pValueChanges;
        long pUniformNull, pProgramNull, pFallbacks, pGuardFailures, pToggleOff;
        long pUniformLookups, pLookupSkips, pCompileInvalidations;

        void publishMaybe() {
            if ((calls & CHUNK_DEPTH_PUBLISH_MASK) != 0L) return;
            publish();
        }

        void publish() {
            addDelta(CD_CALLS, calls - pCalls); pCalls = calls;
            addDelta(CD_ENABLED_CALLS, enabledCalls - pEnabledCalls); pEnabledCalls = enabledCalls;
            addDelta(CD_CACHE_HITS, cacheHits - pCacheHits); pCacheHits = cacheHits;
            addDelta(CD_UPLOADS, uploads - pUploads); pUploads = uploads;
            addDelta(CD_UNIFORM_CHANGES, uniformChanges - pUniformChanges); pUniformChanges = uniformChanges;
            addDelta(CD_VALUE_CHANGES, valueChanges - pValueChanges); pValueChanges = valueChanges;
            addDelta(CD_UNIFORM_NULL, uniformNull - pUniformNull); pUniformNull = uniformNull;
            addDelta(CD_PROGRAM_NULL, programNull - pProgramNull); pProgramNull = programNull;
            addDelta(CD_FALLBACKS, fallbacks - pFallbacks); pFallbacks = fallbacks;
            addDelta(CD_GUARD_FAILURES, guardFailures - pGuardFailures); pGuardFailures = guardFailures;
            addDelta(CD_TOGGLE_OFF, toggleOff - pToggleOff); pToggleOff = toggleOff;
            addDelta(CD_UNIFORM_LOOKUPS, uniformLookups - pUniformLookups); pUniformLookups = uniformLookups;
            addDelta(CD_LOOKUP_SKIPS, lookupSkips - pLookupSkips); pLookupSkips = lookupSkips;
            addDelta(CD_COMPILE_INVALIDATIONS, compileInvalidations - pCompileInvalidations); pCompileInvalidations = compileInvalidations;
            CD_PUBLISH_BATCHES.incrementAndGet();
        }

        private static void addDelta(AtomicLong dst, long delta) {
            if (delta != 0L) dst.addAndGet(delta);
        }
    }

    private static final class MapBiomeCache {
        final Object[] owners, values;
        final String[] names;
        final int[] xs, ys;
        final byte[] used;
        final int mask;
        Object last;
        MapBiomeCache(int cap) {
            int n=1; while(n<cap)n<<=1;
            owners=new Object[n]; values=new Object[n]; names=new String[n]; xs=new int[n]; ys=new int[n]; used=new byte[n]; mask=n-1;
        }
        void clear() { Arrays.fill(used,(byte)0); Arrays.fill(owners,null); Arrays.fill(values,null); Arrays.fill(names,null); last=null; }
        boolean find(Object o,int x,int y,String n) {
            int i=slot(o,x,y,n);
            if (used[i]!=0 && owners[i]==o && xs[i]==x && ys[i]==y && eq(names[i],n)) { last=values[i]; return true; }
            last=null; return false;
        }
        void put(Object o,int x,int y,String n,Object v) {
            int i=slot(o,x,y,n); used[i]=1; owners[i]=o; xs[i]=x; ys[i]=y; names[i]=n; values[i]=v; last=v;
        }
        int slot(Object o,int x,int y,String n) {
            int h=System.identityHashCode(o); h=31*h+x; h=31*h+y; h=31*h+(n==null?0:n.hashCode()); h^=h>>>16; return h&mask;
        }
        static boolean eq(Object a,Object b){return a==b||(a!=null&&a.equals(b));}
    }
}
