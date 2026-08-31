package com.zomdroid.agent.optimization;

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

import zombie.GameWindow;
import zombie.SandboxOptions;
import zombie.LoadGridsquarePerformanceWorkaround;
import zombie.Lua.LuaManager;
import zombie.Lua.MapObjects;
import zombie.VirtualZombieManager;
import zombie.characters.IsoPlayer;
import zombie.core.logger.ExceptionLogger;
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
 * Production CP4.1 multi-fix runtime for Project Zomboid Build 42.
 *
 * Every optimization has an independent gate. Fastpaths either preserve the original operation
 * order/side effects or decline the fast path before side effects so the original method runs.
 * No async/deferred chunk work is introduced. Heavy profiling, per-call clock reads and periodic
 * logger threads from the diagnostic pack are deliberately absent.
 */
public final class ChunkOptimizationRuntime {
    private static volatile boolean forage;
    private static volatile boolean neighbourWorker;
    private static volatile boolean neighbourMain;
    private static volatile boolean grid;
    private static volatile boolean vehicles;
    private static volatile boolean buildings;
    private static volatile boolean lua;
    private static volatile boolean worldgen;
    private static volatile boolean cp2c;

    private static final AtomicBoolean FORAGE_FAIL = new AtomicBoolean(false);
    private static final AtomicBoolean GRID_FAIL = new AtomicBoolean(false);
    private static final AtomicBoolean VEHICLE_FAIL = new AtomicBoolean(false);
    private static final AtomicBoolean BUILDING_FAIL = new AtomicBoolean(false);
    private static final AtomicBoolean LUA_FAIL = new AtomicBoolean(false);
    private static final AtomicBoolean NEIGHBOUR_FAIL = new AtomicBoolean(false);

    private static final FastCounter FORAGE_HANDLED = new FastCounter();
    private static final FastCounter GRID_HANDLED = new FastCounter();
    private static final FastCounter VEHICLE_HANDLED = new FastCounter();
    private static final FastCounter VEHICLE_INDEX_BUILDS = new FastCounter();
    private static final FastCounter VEHICLE_CANDIDATE_CHECKS = new FastCounter();
    private static final FastCounter VEHICLE_EXACT_REJECTS = new FastCounter();
    private static final FastCounter BUILDING_HANDLED = new FastCounter();
    private static final FastCounter LUA_HANDLED = new FastCounter();
    private static final FastCounter NEIGHBOUR_SKIP2 = new FastCounter();
    private static final FastCounter NEIGHBOUR_SKIP3 = new FastCounter();
    private static final FastCounter NEIGHBOUR_VERSION_BUMPS = new FastCounter();
    private static final FastCounter NEIGHBOUR_PAIR_RECORDS = new FastCounter();
    private static final FastCounter NEIGHBOUR_WORKER_SKIP2 = new FastCounter();
    private static final FastCounter NEIGHBOUR_WORKER_SKIP3 = new FastCounter();
    private static final FastCounter NEIGHBOUR_MAIN_SKIP2 = new FastCounter();
    private static final FastCounter NEIGHBOUR_MAIN_SKIP3 = new FastCounter();
    private static final FastCounter WORLDGEN_HIT = new FastCounter();
    private static final FastCounter WORLDGEN_MISS = new FastCounter();

    private static final int HIT_FORAGE = 0;
    private static final int HIT_NEIGHBOUR_WORKER = 1;
    private static final int HIT_NEIGHBOUR_MAIN = 2;
    private static final int HIT_GRID = 3;
    private static final int HIT_VEHICLES = 4;
    private static final int HIT_BUILDINGS = 5;
    private static final int HIT_LUA = 6;
    private static final int HIT_WORLDGEN = 7;
    private static final int HIT_CP2C = 8;
    private static final boolean[] FIRST_HIT = new boolean[9];

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

    private ChunkOptimizationRuntime() {}

    public static void configure(boolean forageRequested,
                                 boolean neighbourWorkerRequested,
                                 boolean neighbourMainRequested,
                                 boolean gridRequested,
                                 boolean vehiclesRequested,
                                 boolean buildingsRequested,
                                 boolean luaRequested,
                                 boolean worldgenRequested,
                                 boolean cp2cRequested) {
        forage = forageRequested;
        neighbourWorker = neighbourWorkerRequested;
        neighbourMain = neighbourMainRequested;
        grid = gridRequested;
        vehicles = vehiclesRequested;
        buildings = buildingsRequested;
        lua = luaRequested;
        worldgen = worldgenRequested;
        cp2c = cp2cRequested;
        Arrays.fill(FIRST_HIT, false);
        ProofRuntime.state("CHUNK_OPTIMIZATION", anyEnabled() ? "ARMED" : "OFF",
                "forage=" + bit(forage)
                        + " neighbour_worker=" + bit(neighbourWorker)
                        + " neighbour_main=" + bit(neighbourMain)
                        + " grid=" + bit(grid)
                        + " vehicles=" + bit(vehicles)
                        + " buildings=" + bit(buildings)
                        + " lua=" + bit(lua)
                        + " worldgen=" + bit(worldgen)
                        + " cp2c=" + bit(cp2c));
    }

    public static void disable(String mechanism) {
        if ("CHUNK_FORAGING".equals(mechanism)) forage = false;
        else if ("CHUNK_NEIGHBOUR_WORKER".equals(mechanism)) neighbourWorker = false;
        else if ("CHUNK_NEIGHBOUR_MAIN".equals(mechanism)) neighbourMain = false;
        else if ("CHUNK_GRID_LOAD".equals(mechanism)) grid = false;
        else if ("CHUNK_VEHICLE_INDEX".equals(mechanism)) vehicles = false;
        else if ("CHUNK_RANDOMIZED_BUILDINGS".equals(mechanism)) buildings = false;
        else if ("CHUNK_LUA_MAPOBJECTS".equals(mechanism)) lua = false;
        else if ("CHUNK_WORLDGEN_BIOME".equals(mechanism)) worldgen = false;
        else if ("CHUNK_CP2C_DIRTY_CLEAR".equals(mechanism)) cp2c = false;
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
        firstHit(HIT_FORAGE, "CHUNK_FORAGING");

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

    public static void workerEnter() {
        if (neighbourWorker || cp2c) neighbourScopeEnter(1);
    }

    public static void workerExit() {
        NeighbourCtx c = NEIGHBOUR.get();
        if (c.active && c.scope == 1) neighbourScopeExit();
    }

    public static void mainEnter() {
        if (neighbourMain) neighbourScopeEnter(2);
    }

    public static void mainExit() {
        NeighbourCtx c = NEIGHBOUR.get();
        if (c.active && c.scope == 2) neighbourScopeExit();
    }

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

    public static void recalcNeighboursEnter() {
        NeighbourCtx c = NEIGHBOUR.get();
        if (cp2c && c.active && c.scope == 1) c.cp2cDepth++;
    }

    public static void recalcNeighboursExit() {
        NeighbourCtx c = NEIGHBOUR.get();
        if (c.cp2cDepth > 0) c.cp2cDepth--;
    }

    /** Called only after an actual RecalcProperties body completed successfully. */
    public static void onRecalcPropertiesCompleted(Object squareObj) {
        NeighbourCtx c = NEIGHBOUR.get();
        if (!c.active || !(squareObj instanceof IsoGridSquare)) return;
        IsoGridSquare square = (IsoGridSquare)squareObj;

        if (neighbourEnabled(c.scope)) {
            c.versions.bump(square);
            // CalculateCollide/CalculateVisionBlocked may consult squares beyond the two explicit
            // endpoints. Clearing all proven pairs on any real property mutation is conservative
            // and prevents a third-square change from creating a stale hit.
            c.pairs.clear();
            c.clearPending();
            NEIGHBOUR_VERSION_BUMPS.incrementAndGet();
        }

        if (cp2c && c.scope == 1 && c.cp2cDepth > 0 && clearPropertiesDirty(square)) {
            firstHit(HIT_CP2C, "CHUNK_CP2C_DIRTY_CLEAR");
        }
    }

    /** Compatibility/fail-safe entry point for old callers: clear pair coverage only. */
    public static void invalidateNeighbourCache() {
        NeighbourCtx c = NEIGHBOUR.get();
        if (c.active) c.pairs.clear();
    }

    public static boolean skipRecalc2(Object selfObj, Object otherObj, Object getter) {
        NeighbourCtx c = NEIGHBOUR.get();
        if (!neighbourEnabled(c.scope) || !c.active
                || !(selfObj instanceof IsoGridSquare)
                || !(otherObj instanceof IsoGridSquare)) return false;
        IsoGridSquare self = (IsoGridSquare)selfObj;
        IsoGridSquare other = (IsoGridSquare)otherObj;
        if (other == null || other == self) return true; // identical to vanilla early return
        if (!prepareGetter(c, getter)) return false;
        if (isDirty(self) || isDirty(other)) return false;
        int va = c.versions.get(self), vb = c.versions.get(other);
        if (c.pairs.contains(self, other, va, vb) && c.pairs.contains(other, self, vb, va)) {
            if (!clearSolidFloor(self, other)) return false;
            NEIGHBOUR_SKIP2.incrementAndGet();
            if (c.scope == 2) {
                NEIGHBOUR_MAIN_SKIP2.incrementAndGet();
                firstHit(HIT_NEIGHBOUR_MAIN, "CHUNK_NEIGHBOUR_MAIN");
            } else {
                NEIGHBOUR_WORKER_SKIP2.incrementAndGet();
                firstHit(HIT_NEIGHBOUR_WORKER, "CHUNK_NEIGHBOUR_WORKER");
            }
            return true;
        }
        c.pendingA = self; c.pendingB = other; c.pendingBoth = true;
        return false;
    }

    public static void afterRecalc2(Object selfObj, Object otherObj, Object getter) {
        NeighbourCtx c = NEIGHBOUR.get();
        if (!c.active || !neighbourEnabled(c.scope)) return;
        if (c.pendingA != null && c.pendingB != null) {
            int va = c.versions.get(c.pendingA), vb = c.versions.get(c.pendingB);
            if (c.pairs.put(c.pendingA, c.pendingB, va, vb)) NEIGHBOUR_PAIR_RECORDS.incrementAndGet();
            if (c.pairs.put(c.pendingB, c.pendingA, vb, va)) NEIGHBOUR_PAIR_RECORDS.incrementAndGet();
        }
        c.clearPending();
    }

    public static boolean skipRecalc3(Object selfObj, boolean both, Object otherObj, Object getter) {
        NeighbourCtx c = NEIGHBOUR.get();
        if (!neighbourEnabled(c.scope) || !c.active
                || !(selfObj instanceof IsoGridSquare)
                || !(otherObj instanceof IsoGridSquare)) return false;
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
            if (c.scope == 2) {
                NEIGHBOUR_MAIN_SKIP3.incrementAndGet();
                firstHit(HIT_NEIGHBOUR_MAIN, "CHUNK_NEIGHBOUR_MAIN");
            } else {
                NEIGHBOUR_WORKER_SKIP3.incrementAndGet();
                firstHit(HIT_NEIGHBOUR_WORKER, "CHUNK_NEIGHBOUR_WORKER");
            }
            return true;
        }
        c.pendingA = self; c.pendingB = other; c.pendingBoth = both;
        return false;
    }

    public static void afterRecalc3(Object selfObj, boolean both, Object otherObj, Object getter) {
        NeighbourCtx c = NEIGHBOUR.get();
        if (!c.active || !neighbourEnabled(c.scope)) return;
        if (c.pendingA != null && c.pendingB != null) {
            int va = c.versions.get(c.pendingA), vb = c.versions.get(c.pendingB);
            if (c.pairs.put(c.pendingA, c.pendingB, va, vb)) NEIGHBOUR_PAIR_RECORDS.incrementAndGet();
            if (c.pendingBoth && c.pairs.put(c.pendingB, c.pendingA, vb, va)) NEIGHBOUR_PAIR_RECORDS.incrementAndGet();
        }
        c.clearPending();
    }

    private static boolean neighbourEnabled(int scope) {
        return scope == 1 ? neighbourWorker : scope == 2 && neighbourMain;
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

    private static boolean clearPropertiesDirty(IsoGridSquare square) {
        try {
            Field f = propertiesDirtyField;
            if (f == null) {
                f = IsoGridSquare.class.getDeclaredField("propertiesDirty");
                f.setAccessible(true);
                propertiesDirtyField = f;
            }
            if (!f.getBoolean(square)) return false;
            f.setBoolean(square, false);
            return true;
        } catch (Throwable t) {
            failOnce(NEIGHBOUR_FAIL, "cp2c_properties_dirty", t);
            cp2c = false;
            return false;
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
        firstHit(HIT_GRID, "CHUNK_GRID_LOAD");
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
        firstHit(HIT_VEHICLES, "CHUNK_VEHICLE_INDEX");
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
            if (idx == null || !idx.matches(zones)) {
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
        synchronized (ChunkOptimizationRuntime.class) {
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
        firstHit(HIT_BUILDINGS, "CHUNK_RANDOMIZED_BUILDINGS");
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
        firstHit(HIT_LUA, "CHUNK_LUA_MAPOBJECTS");
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
        synchronized (ChunkOptimizationRuntime.class) {
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
        if (worldgen) MAP_BIOME_CACHE.get().clear();
    }

    public static boolean mapBiomeHas(Object owner, int x, int y, String name) {
        if (!worldgen) return false;
        firstHit(HIT_WORLDGEN, "CHUNK_WORLDGEN_BIOME");
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
        return "MGLPZ_CP4.1_OPT_SUMMARY"
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
                + " worldgen_cache_miss=" + WORLDGEN_MISS.get();
    }

    private static void failOnce(AtomicBoolean once, String mechanism, Throwable t) {
        if (once.compareAndSet(false, true)) {
            ProofRuntime.state("CHUNK_GUARD_" + mechanism.toUpperCase(java.util.Locale.ROOT),
                    "FALLBACK", "error=" + t.getClass().getName()
                            + " action=original_game_code");
        }
    }

    private static boolean anyEnabled() {
        return forage || neighbourWorker || neighbourMain || grid || vehicles || buildings
                || lua || worldgen || cp2c;
    }

    private static void firstHit(int index, String mechanism) {
        // A benign race can emit at most one extra bounded proof line; keeping this non-atomic
        // removes a memory barrier from the chunk hot paths after the first hit.
        if (!FIRST_HIT[index]) {
            FIRST_HIT[index] = true;
            ProofRuntime.state(mechanism, "EXERCISED", "production_fast_path_hit=1");
        }
    }

    private static int bit(boolean value) { return value ? 1 : 0; }

    /** Diagnostic counters are deliberately non-atomic: their values never control semantics. */
    private static final class FastCounter {
        private long value;
        void incrementAndGet() { value++; }
        long get() { return value; }
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
        int cp2cDepth;
        Object getter;
        final SquareVersions versions = new SquareVersions(2048);
        final DirectedPairCache pairs = new DirectedPairCache(8192);
        IsoGridSquare pendingA, pendingB;
        boolean pendingBoth;
        void clear() {
            scope=0; cp2cDepth=0; getter=null; versions.clear(); pairs.clear(); clearPending();
        }
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
        final VehicleZone[] members;
        final int[] geometry;
        final HashMap<Long, ArrayList<VehicleZone>> byChunk = new HashMap<Long, ArrayList<VehicleZone>>();
        VehicleIndex(ArrayList<VehicleZone> zones) {
            source = zones;
            size = zones.size();
            members = new VehicleZone[size];
            geometry = new int[size * 4];
            for (int i=0;i<zones.size();i++) {
                VehicleZone z = zones.get(i);
                members[i] = z;
                int base = i * 4;
                geometry[base] = z.x;
                geometry[base + 1] = z.y;
                geometry[base + 2] = z.w;
                geometry[base + 3] = z.h;
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
        boolean matches(ArrayList<VehicleZone> zones) {
            if (source != zones || size != zones.size()) return false;
            // Exact identity + geometry validation closes the same-size in-place mutation hole.
            // Name/type changes do not affect index membership and are read live from each zone.
            for (int i = 0; i < size; i++) {
                VehicleZone z = zones.get(i);
                int base = i * 4;
                if (z != members[i]
                        || z.x != geometry[base]
                        || z.y != geometry[base + 1]
                        || z.w != geometry[base + 2]
                        || z.h != geometry[base + 3]) return false;
            }
            return true;
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
