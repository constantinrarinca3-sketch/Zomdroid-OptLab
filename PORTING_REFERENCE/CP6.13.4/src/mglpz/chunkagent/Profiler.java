package mglpz.chunkagent;

import java.io.File;
import java.io.FileOutputStream;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.text.DecimalFormat;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CP3B = CP2C bounded RecalcProperties fix + full deep-split attribution for all large chunk stages.
 *
 * Exact Build 42.20.3 behavior motivating this patch:
 * - loadInWorldStreamerThread() performs a direct RecalcProperties sweep;
 * - RecalcAllWithNeighbours() then calls RecalcPropertiesIfNeeded repeatedly;
 * - RecalcProperties() sets propertiesDirty=true whenever its chunk is already loaded;
 * - therefore every IfNeeded check re-enters RecalcProperties, often ~100-160x/square.
 *
 * CP2C changes only one bounded condition: after an actual RecalcProperties returns while
 * nested inside RecalcAllWithNeighbours during ISO.worker, CP2C clears propertiesDirty.
 * The first neighbour-pass recalculation still runs. Any later legitimate re-dirty can
 * trigger another recalculation. The vanilla loadInWorldStreamerThread epilogue sets
 * propertiesDirty=true for every square again, preserving the post-worker dirty state.
 */
public final class Profiler {
    private static final long NS_PER_MS = 1_000_000L;
    private static volatile double spikeMs = 20.0;
    // CP6.4: keep statistical slow threshold separate from per-event log threshold.
    private static volatile double eventLogMs = 20.0;
    private static volatile double rootLogMs = 50.0;
    private static volatile long summarySeconds = 10L;
    private static volatile double lifecycleMaxMs = 10000.0;
    private static volatile double deepMs = 10.0;
    private static volatile int deepTop = 32;
    private static volatile boolean deepCpu = true;
    private static volatile boolean deepDetailCpu = false;
    private static volatile boolean verbose = false;
    private static volatile boolean dedupEnabled = true;
    // CP6.9: lifecycle latency diagnostics are aggregated by default. Per-chunk lifecycle
    // logging can itself create second-scale stalls when hundreds of stale records expire together.
    private static volatile boolean lifecycleEventLogs = false;
    // CP6.11: gameplay-only diagnostics. Ignore world-entry settling so normal load spikes do not
    // contaminate the zone-transition verdict. First IngameState.update arms a warmup window.
    private static volatile double gameplayWarmupMs = 5000.0;
    private static volatile double gameplaySpikeMs = 50.0;
    private static final AtomicLong gameplayFirstUpdateNs = new AtomicLong();
    private static final AtomicBoolean gameplayArmedLogged = new AtomicBoolean(false);
    private static volatile boolean cp613GameplayReady;
    private static final AtomicLong lifecycleStaleRequest = new AtomicLong();
    private static final AtomicLong lifecycleStaleWorker = new AtomicLong();
    private static final AtomicLong lifecycleWorkerReadySuppressed = new AtomicLong();
    private static final AtomicLong lifecycleMainReadySuppressed = new AtomicLong();
    private static final AtomicLong lifecyclePerEventLogged = new AtomicLong();
    private static volatile PrintWriter fileOut;
    private static final Object OUT_LOCK = new Object();
    private static final AtomicBoolean STARTED = new AtomicBoolean(false);
    private static final AtomicBoolean DIRTY_FIELD_FAIL_REPORTED = new AtomicBoolean(false);
    private static volatile Field propertiesDirtyField;
    private static volatile Class<?> propertiesDirtyOwner;
    private static final ThreadMXBean THREAD_MX = initThreadMxBean();

    private static final ThreadLocal<Map<String, Start>> starts = new ThreadLocal<Map<String, Start>>() {
        @Override protected Map<String, Start> initialValue() { return new HashMap<String, Start>(); }
    };

    private static final ThreadLocal<PipelineCtx> pipelineCtx = new ThreadLocal<PipelineCtx>();
    private static final ThreadLocal<WorkerCtx> workerCtx = new ThreadLocal<WorkerCtx>();
    private static final ThreadLocal<ForageCtx> forageCtx = new ThreadLocal<ForageCtx>();
    private static final ThreadLocal<ArrayList<DeepCtx>> deepCtxs = new ThreadLocal<ArrayList<DeepCtx>>() {
        @Override protected ArrayList<DeepCtx> initialValue() { return new ArrayList<DeepCtx>(8); }
    };
    private static final ThreadLocal<ArrayDeque<DeepFrame>> deepFrames = new ThreadLocal<ArrayDeque<DeepFrame>>() {
        @Override protected ArrayDeque<DeepFrame> initialValue() { return new ArrayDeque<DeepFrame>(16); }
    };

    private static final ThreadLocal<Integer> z69EntryDepth = new ThreadLocal<Integer>() {
        @Override protected Integer initialValue() { return Integer.valueOf(0); }
    };
    private static final ThreadLocal<Integer> z69VehicleDbDepth = new ThreadLocal<Integer>() {
        @Override protected Integer initialValue() { return Integer.valueOf(0); }
    };

    private static final ConcurrentHashMap<String, Stats> stats = new ConcurrentHashMap<String, Stats>();
    private static final ConcurrentHashMap<Long, Life> life = new ConcurrentHashMap<Long, Life>();
    private static final ConcurrentHashMap<Class<?>, CoordFields> coordCache = new ConcurrentHashMap<Class<?>, CoordFields>();
    private static final DecimalFormat FMT = new DecimalFormat("0.000");

    private Profiler() {}

    public static void configure(String args) {
        Map<String,String> kv = parseArgs(args);
        Optimizer.configure(args);
        spikeMs = parseDouble(kv.get("spikeMs"), propDouble("mglpz.chunk.spikeMs", 20.0));
        eventLogMs = parseDouble(kv.get("eventLogMs"), propDouble("mglpz.chunk.eventLogMs", spikeMs));
        rootLogMs = parseDouble(kv.get("rootLogMs"), propDouble("mglpz.chunk.rootLogMs", 50.0));
        summarySeconds = parseLong(kv.get("summarySec"), propLong("mglpz.chunk.summarySec", 10L));
        lifecycleMaxMs = parseDouble(kv.get("lifecycleMaxMs"), propDouble("mglpz.chunk.lifecycleMaxMs", 10000.0));
        deepMs = parseDouble(kv.get("deepMs"), propDouble("mglpz.chunk.deepMs", 10.0));
        deepTop = (int)parseLong(kv.get("deepTop"), propLong("mglpz.chunk.deepTop", 32L));
        if (deepTop < 4) deepTop = 4;
        if (deepTop > 64) deepTop = 64;
        deepCpu = parseBool(kv.get("deepCpu"), Boolean.parseBoolean(System.getProperty("mglpz.chunk.deepCpu", "true")));
        deepDetailCpu = parseBool(kv.get("deepDetailCpu"), Boolean.parseBoolean(System.getProperty("mglpz.chunk.deepDetailCpu", "false")));
        verbose = parseBool(kv.get("verbose"), Boolean.parseBoolean(System.getProperty("mglpz.chunk.verbose", "false")));
        dedupEnabled = parseBool(kv.get("dedup"), Boolean.parseBoolean(System.getProperty("mglpz.cp2c.dedup", "true")));
        lifecycleEventLogs = parseBool(kv.get("lifecycleEventLogs"),
                Boolean.parseBoolean(System.getProperty("mglpz.chunk.lifecycleEventLogs", "false")));
        gameplayWarmupMs = parseDouble(kv.get("gameplayWarmupMs"), propDouble("mglpz.cp611.gameplayWarmupMs", 5000.0));
        gameplaySpikeMs = parseDouble(kv.get("gameplaySpikeMs"), propDouble("mglpz.cp611.gameplaySpikeMs", 50.0));
        if (gameplayWarmupMs < 0.0) gameplayWarmupMs = 0.0;
        if (gameplaySpikeMs < 10.0) gameplaySpikeMs = 10.0;
        String path = kv.get("log");
        if (path == null || path.length() == 0) path = System.getProperty("mglpz.chunk.log", "");
        if (path.length() > 0) openFile(path);
        if (STARTED.compareAndSet(false, true) && !MGLPZCP613AllocationFix.enabled()) startSummaryThread();
        note("MGLPZ_CHUNK_AGENT_CONFIG cp=CP6.13.4-HOTPATH-CPU-ALLOC baseline=CP4.1 spike_ms=" + spikeMs + " event_log_ms=" + eventLogMs + " summary_sec=" + summarySeconds
                + " lifecycle_max_ms=" + lifecycleMaxMs + " deep_ms=" + deepMs
                + " deep_top=" + deepTop + " deep_cpu=" + deepCpu + " deep_detail_cpu=" + deepDetailCpu
                + " dedup=" + dedupEnabled
                + " lifecycle_event_logs=" + lifecycleEventLogs
                + " gameplay_warmup_ms=" + gameplayWarmupMs + " gameplay_spike_ms=" + gameplaySpikeMs
                + " verbose=" + verbose + " log=" + (path.length() == 0 ? "stderr" : path));
    }

    public static void enter(String stage, Object chunk) {
        stage = z69NormalizeStage(stage, chunk);
        if (stage == null) return;
        // CP6.13 production build keeps just two lifecycle signals alive: reset at entry and
        // arm the gameplay-only fixes on the first IngameState.update. No Start/Coord allocation.
        if (MGLPZCP613AllocationFix.enabled()) {
            if ("Z69.entry.total".equals(stage)) {
                gameplayFirstUpdateNs.set(0L);
                gameplayArmedLogged.set(false);
                cp613GameplayReady = false;
                return;
            }
            if ("Z69.ingame.update".equals(stage)) {
                long n = System.nanoTime();
                if (gameplayFirstUpdateNs.compareAndSet(0L, n))
                    note("MGLPZ_CP6_13_GAMEPLAY_ARM warmup_ms=" + gameplayWarmupMs + " first_update_ns=" + n);
                return;
            }
            if (!cp613EssentialStage(stage)) return;
        }
        if (isGameplayStage(stage) && !gameplayProfilingActive()) return;
        if ("Z69.entry.total".equals(stage)) {
            // New world/session: re-arm the gameplay-only warmup window.
            gameplayFirstUpdateNs.set(0L);
            gameplayArmedLogged.set(false);
            z69EntryDepth.set(Integer.valueOf(z69EntryDepth.get().intValue() + 1));
        } else if (stage.startsWith("Z69.entry.") && z69EntryDepth.get().intValue() <= 0) return;
        if ("Z69.db.vehiclesMainUpdate".equals(stage)) {
            z69VehicleDbDepth.set(Integer.valueOf(z69VehicleDbDepth.get().intValue() + 1));
        } else if (("Z69.db.vehicleAddToWorld".equals(stage) || "Z69.db.vehicleAddToWorldBool".equals(stage) || "Z69.db.createPhysics0".equals(stage) || "Z69.db.createPhysics1".equals(stage) || "Z69.db.worldSimulationCreate".equals(stage) || "Z69.db.vehicleRemoveFromWorld".equals(stage) || "Z69.db.virtualAddToMeta".equals(stage))
                && z69VehicleDbDepth.get().intValue() <= 0) return;
        long now = System.nanoTime();
        if ("Z69.ingame.update".equals(stage) && gameplayFirstUpdateNs.compareAndSet(0L, now)) {
            note("MGLPZ_CP6_13_GAMEPLAY_ARM warmup_ms=" + gameplayWarmupMs + " first_update_ns=" + now);
        }

        if ("DS.brand.worldgen".equals(stage)) Optimizer.worldgenScopeEnter();
        if (isDeepDetail(stage)) {
            enterDeepDetail(stage, now);
            return;
        }

        // CP3B forage hot detail probe: getZones may execute 1024x in one genForaging call.
        // Avoid HashMap/global atomics entirely on this path.
        if (isForageDetail(stage)) {
            ForageCtx f = forageCtx.get();
            if (f != null) f.getZonesStartNs = now;
            return;
        }

        // Worker detail probes execute many times per chunk. Do not reflect coordinates or touch
        // global atomics here; just time them inside the current worker context.
        if (isDetail(stage)) {
            starts.get().put(stage, new Start(now, Coord.NONE, 0L));
            WorkerCtx w = workerCtx.get();
            if (w != null) w.onEnter(stage);
            return;
        }

        // WorldStreamer.addJob writes IsoChunk.wx/wy inside the original method.
        // Capturing coordinates before the call is therefore wrong for pooled/reused chunks.
        if ("WS.addJob".equals(stage) || "WS.addJobInstant".equals(stage)) {
            starts.get().put(stage, new Start(now, Coord.NONE, 0L));
            return;
        }

        Coord c = coord(chunk);
        long cpu = (isDeepParent(stage) || stage.startsWith("Z69.") || isGameplayParent(stage)) ? cpuNow() : 0L;
        starts.get().put(stage, new Start(now, c, cpu));
        if (isDeepParent(stage)) startDeepParent(stage, c, now, cpu);

        if ("WS.DoChunkAlways".equals(stage)) {
            pipelineCtx.set(new PipelineCtx(c, now));
        } else if ("ISO.worker".equals(stage)) {
            workerCtx.set(new WorkerCtx(c, now));
            Optimizer.workerEnter();
        } else if ("ISO.main".equals(stage)) {
            Optimizer.mainEnter();
        } else if ("FORAGE.genForaging".equals(stage)) {
            forageCtx.set(new ForageCtx(now));
        }
    }

    public static void exit(String stage, Object chunk) {
        stage = z69NormalizeStage(stage, chunk);
        if (stage == null) return;
        if (MGLPZCP613AllocationFix.enabled() && !cp613EssentialStage(stage)) return;
        if (isGameplayStage(stage) && !gameplayProfilingActive()) return;
        if (stage.startsWith("Z69.entry.") && !"Z69.entry.total".equals(stage)
                && z69EntryDepth.get().intValue() <= 0) return;
        if (("Z69.db.vehicleAddToWorld".equals(stage) || "Z69.db.vehicleAddToWorldBool".equals(stage) || "Z69.db.createPhysics0".equals(stage) || "Z69.db.createPhysics1".equals(stage) || "Z69.db.worldSimulationCreate".equals(stage) || "Z69.db.vehicleRemoveFromWorld".equals(stage) || "Z69.db.virtualAddToMeta".equals(stage))
                && z69VehicleDbDepth.get().intValue() <= 0) return;
        long now = System.nanoTime();

        if (isDeepDetail(stage)) {
            exitDeepDetail(stage, now);
            return;
        }

        if (isForageDetail(stage)) {
            ForageCtx f = forageCtx.get();
            if (f != null && f.getZonesStartNs != 0L) {
                long dur = now - f.getZonesStartNs;
                f.getZonesStartNs = 0L;
                f.addGetZones(dur);
            }
            return;
        }

        Start s = starts.get().remove(stage);
        if (s == null) return;
        long dur = now - s.ns;
        if ("Z69.db.queueLoadMetaWS".equals(stage)) zombie.vehicles.MGLPZVehicleMetaPreload.markWorldStreamerDone();
        if ("Z69.db.queueLoadMeta".equals(stage)) zombie.vehicles.MGLPZVehicleMetaPreload.markMainDone();

        if (isDetail(stage)) {
            if ("SUB.recalcProps".equals(stage)) Optimizer.invalidateNeighbourCache(chunk);
            WorkerCtx w = workerCtx.get();
            if (w != null) {
                if (dedupEnabled && "SUB.recalcProps".equals(stage) && w.neighbourDepth > 0) {
                    if (clearPropertiesDirty(chunk)) w.dedupClears++;
                }
                w.add(stage, dur);
                w.onExit(stage);
            }
            return;
        }

        long cpuDur = s.cpuNs == 0L ? 0L : nonNegative(cpuNow() - s.cpuNs);
        addStat(stage, dur);

        Coord c = ("WS.addJob".equals(stage) || "WS.addJobInstant".equals(stage))
                ? coord(chunk) : (s.coord.valid ? s.coord : coord(chunk));

        // Normal addJob has now written wx/wy and enqueued the chunk, so this is the
        // first safe point to key lifecycle state by the new coordinate. Use s.ns as
        // the request timestamp so addJob's own tiny execution time remains included.
        if ("WS.addJob".equals(stage) && c.valid) {
            long k = key(c.x, c.y);
            Life old = life.put(k, new Life(s.ns));
            if (verbose) {
                note("MGLPZ_CHUNK_REQUEST chunk=" + c.x + "," + c.y
                        + " replaced=" + (old != null) + " inflight_est=" + life.size()
                        + " thread=" + thread());
            }
        }

        recordPipeline(stage, dur);

        if ("FORAGE.genForaging".equals(stage)) {
            finishForage(dur);
        }

        if ("ISO.worker".equals(stage)) {
            finishWorker(c, dur);
            Optimizer.workerExit();
        } else if ("ISO.main".equals(stage)) {
            Optimizer.mainExit();
        }

        if (isDeepParent(stage)) finishDeepParent(stage, c, dur, cpuDur);

        long stageLogNs = isGameplayParent(stage) ? gameplaySpikeNs() : (stage.startsWith("Z69.") ? rootLogNs() : eventLogNs());
        if (dur >= stageLogNs) {
            String cpuPart = (stage.startsWith("Z69.") || isGameplayParent(stage)) && s.cpuNs != 0L
                    ? " cpu_ms=" + ms(cpuDur) + " wait_ms=" + ms(nonNegative(dur - cpuDur)) : "";
            String prefix = isGameplayParent(stage) ? "MGLPZ_CP6_13_GAMEPLAY_SPIKE" : "MGLPZ_CHUNK_SPIKE";
            note(prefix + " stage=" + stage + " chunk=" + fmtCoord(c)
                    + " ms=" + ms(dur) + cpuPart + " thread=" + thread());
        } else if (verbose) {
            note("MGLPZ_CHUNK_STAGE stage=" + stage + " chunk=" + fmtCoord(c)
                    + " ms=" + ms(dur) + " thread=" + thread());
        }

        if (c.valid) lifecycle(stage, c, now, dur);

        if ("WS.DoChunkAlways".equals(stage)) {
            finishPipeline(c, dur);
        }
        if ("Z69.entry.total".equals(stage)) {
            int d = z69EntryDepth.get().intValue();
            z69EntryDepth.set(Integer.valueOf(d <= 1 ? 0 : d - 1));
        }
        if ("Z69.db.vehiclesMainUpdate".equals(stage)) {
            int d = z69VehicleDbDepth.get().intValue();
            z69VehicleDbDepth.set(Integer.valueOf(d <= 1 ? 0 : d - 1));
        }
    }

    private static boolean cp613EssentialStage(String stage) {
        if (stage == null) return false;
        // CP4.1 semantic scopes only. All other inherited CP6.11/12 profiler wrappers are
        // production no-ops by default to remove timing/Start/Coord allocation from hot gameplay.
        return "ISO.worker".equals(stage)
                || "ISO.main".equals(stage)
                || stage.startsWith("SUB.")
                || "DS.brand.worldgen".equals(stage);
    }

    private static String z69NormalizeStage(String stage, Object chunk) {
        if (!"Z69.entry.luaEvent".equals(stage)) return stage;
        if (!(chunk instanceof String)) return null;
        String event = (String) chunk;
        if ("OnGameStart".equals(event)) return "Z69.entry.lua.OnGameStart";
        if ("OnLoad".equals(event)) return "Z69.entry.lua.OnLoad";
        return null;
    }

    private static boolean isDetail(String stage) {
        return stage != null && stage.startsWith("SUB.");
    }

    private static boolean isForageDetail(String stage) {
        return stage != null && stage.startsWith("FSUB.");
    }

    private static boolean isDeepDetail(String stage) {
        return stage != null && (stage.startsWith("DS.") || stage.startsWith("G11D."));
    }

    private static boolean isDeepParent(String stage) {
        return isGameplayParent(stage)
                || "LOAD.loadOrCreate".equals(stage)
                || "LOAD.brandNew".equals(stage)
                || "ISO.disk".equals(stage)
                || "ISO.worker".equals(stage)
                || "ISO.main".equals(stage)
                || "ISO.grid".equals(stage);
    }

    private static boolean isGameplayStage(String stage) {
        return stage != null && (stage.startsWith("G11.") || stage.startsWith("G11D."));
    }

    private static boolean isGameplayParent(String stage) {
        return "G11.render.cell".equals(stage)
                || "G11.physics.core".equals(stage)
                || "G11.moving.update".equals(stage)
                || "G11.moving.post".equals(stage)
                || "G11.zombie.population".equals(stage);
    }

    /** Cheap production gate: after warmup this is only one volatile read. */
    public static boolean cp613GameplayReadyFast() {
        if (cp613GameplayReady) return true;
        long first = gameplayFirstUpdateNs.get();
        if (first == 0L) return false;
        long elapsed = System.nanoTime() - first;
        if (elapsed < gameplayWarmupNs()) return false;
        cp613GameplayReady = true;
        if (gameplayArmedLogged.compareAndSet(false, true))
            note("MGLPZ_CP6_13_GAMEPLAY_ACTIVE after_ms=" + ms(elapsed) + " mode=production-fixes");
        return true;
    }

    public static boolean gameplayProfilingActive() {
        long first = gameplayFirstUpdateNs.get();
        if (first == 0L) return false;
        long elapsed = System.nanoTime() - first;
        boolean active = elapsed >= gameplayWarmupNs();
        if (active && gameplayArmedLogged.compareAndSet(false, true)) {
            note("MGLPZ_CP6_13_GAMEPLAY_ACTIVE after_ms=" + ms(elapsed)
                    + " spike_ms=" + gameplaySpikeMs + " thread=" + thread());
        }
        return active;
    }

    public static long gameplayProbeStartNs() {
        return gameplayProfilingActive() ? System.nanoTime() : 0L;
    }

    public static void gameplayRingSpike(long startNs, int runs, long stateCalls, long stateHits,
                                          long draws, long opFallback, int sequence) {
        if (startNs == 0L) return;
        long dur = nonNegative(System.nanoTime() - startNs);
        if (dur < gameplaySpikeNs()) return;
        String severity = dur >= 1000L * NS_PER_MS ? "CATASTROPHIC"
                : dur >= 250L * NS_PER_MS ? "FREEZE"
                : dur >= 100L * NS_PER_MS ? "SEVERE" : "STUTTER";
        note("MGLPZ_CP6_13_RING_SPIKE severity=" + severity + " wall_ms=" + ms(dur)
                + " runs=" + runs + " state_calls=" + stateCalls + " state_hits=" + stateHits
                + " draws=" + draws + " op_fallback=" + opFallback + " sequence=" + sequence
                + " ms_per_draw=" + (draws <= 0L ? "NA" : ms(dur / draws)) + " thread=" + thread());
    }

    private static void startDeepParent(String stage, Coord c, long wallNs, long cpuNs) {
        deepCtxs.get().add(new DeepCtx(stage, c, wallNs, cpuNs));
    }

    private static void enterDeepDetail(String stage, long now) {
        ArrayList<DeepCtx> ctxs = deepCtxs.get();
        if (ctxs.isEmpty()) return;
        deepFrames.get().addLast(new DeepFrame(stage, now, cpuNowDetail()));
    }

    private static void exitDeepDetail(String stage, long now) {
        ArrayDeque<DeepFrame> stack = deepFrames.get();
        if (stack.isEmpty()) return;
        DeepFrame f = stack.removeLast();
        if (!stage.equals(f.stage)) {
            stack.clear();
            note("MGLPZ_CP4.1_DEEP_GUARD_FAIL stage=" + stage + " expected=" + f.stage
                    + " reason=stack_mismatch action=diagnostic_only");
            return;
        }
        long wall = nonNegative(now - f.wallStartNs);
        long cpuEnd = f.cpuStartNs == 0L ? 0L : cpuNowDetail();
        long cpu = (f.cpuStartNs == 0L || cpuEnd == 0L) ? 0L : nonNegative(cpuEnd - f.cpuStartNs);
        long selfWall = nonNegative(wall - f.childWallNs);
        long selfCpu = nonNegative(cpu - f.childCpuNs);

        DeepFrame parent = stack.peekLast();
        if (parent != null) {
            parent.childWallNs += wall;
            parent.childCpuNs += cpu;
        }

        ArrayList<DeepCtx> ctxs = deepCtxs.get();
        for (int i = 0; i < ctxs.size(); i++) {
            ctxs.get(i).add(stage, wall, selfWall, cpu, selfCpu);
        }
    }

    private static void finishDeepParent(String stage, Coord c, long wallNs, long cpuNs) {
        ArrayList<DeepCtx> ctxs = deepCtxs.get();
        DeepCtx ctx = null;
        for (int i = ctxs.size() - 1; i >= 0; i--) {
            DeepCtx x = ctxs.get(i);
            if (stage.equals(x.parent)) {
                ctx = x;
                ctxs.remove(i);
                break;
            }
        }
        if (ctx == null) return;
        if (wallNs < (isGameplayParent(stage) ? gameplaySpikeNs() : deepNs()) && !verbose) return;

        ArrayList<DeepPart> parts = new ArrayList<DeepPart>(ctx.parts.values());
        Collections.sort(parts, new Comparator<DeepPart>() {
            @Override public int compare(DeepPart a, DeepPart b) {
                if (a.selfWallNs == b.selfWallNs) return a.name.compareTo(b.name);
                return a.selfWallNs < b.selfWallNs ? 1 : -1;
            }
        });

        long coveredSelf = 0L;
        long coveredCpuSelf = 0L;
        for (DeepPart p : parts) {
            coveredSelf += p.selfWallNs;
            coveredCpuSelf += p.selfCpuNs;
        }
        long residual = nonNegative(wallNs - coveredSelf);
        long residualCpu = (!deepDetailCpu || cpuNs == 0L) ? 0L : nonNegative(cpuNs - coveredCpuSelf);

        StringBuilder b = new StringBuilder(1024);
        b.append(isGameplayParent(stage) ? "MGLPZ_CP6_13_GAMEPLAY_SPLIT parent=" : "MGLPZ_DEEP_SPLIT parent=").append(stage)
         .append(" chunk=").append(fmtCoord(c))
         .append(" wall_ms=").append(ms(wallNs))
         .append(" cpu_ms=").append(cpuNs == 0L ? "NA" : ms(cpuNs))
         .append(" covered_self_ms=").append(ms(coveredSelf))
         .append(" residual_ms=").append(ms(residual))
         .append(" residual_cpu_ms=").append((!deepDetailCpu || cpuNs == 0L) ? "NA" : ms(residualCpu))
         .append(" parts=").append(parts.size())
         .append(" inflight_est=").append(life.size());

        int n = Math.min(deepTop, parts.size());
        for (int i = 0; i < n; i++) {
            DeepPart p = parts.get(i);
            b.append(" | ").append(p.name)
             .append(" calls=").append(p.calls)
             .append(" self_ms=").append(ms(p.selfWallNs))
             .append(" incl_ms=").append(ms(p.inclusiveWallNs))
             .append(" max_ms=").append(ms(p.maxWallNs));
            if (p.selfCpuNs != 0L || p.inclusiveCpuNs != 0L) {
                b.append(" cpu_self_ms=").append(ms(p.selfCpuNs))
                 .append(" cpu_incl_ms=").append(ms(p.inclusiveCpuNs));
            }
        }
        if (parts.size() > n) b.append(" | omitted_parts=").append(parts.size() - n);
        note(b.toString());
    }

    private static ThreadMXBean initThreadMxBean() {
        try {
            ThreadMXBean b = ManagementFactory.getThreadMXBean();
            if (b.isThreadCpuTimeSupported() && !b.isThreadCpuTimeEnabled()) {
                try { b.setThreadCpuTimeEnabled(true); } catch (Throwable ignored) {}
            }
            return b;
        } catch (Throwable t) {
            return null;
        }
    }

    private static long cpuNow() {
        if (!deepCpu || THREAD_MX == null) return 0L;
        try {
            if (!THREAD_MX.isThreadCpuTimeSupported() || !THREAD_MX.isThreadCpuTimeEnabled()) return 0L;
            long v = THREAD_MX.getCurrentThreadCpuTime();
            return v < 0L ? 0L : v;
        } catch (Throwable t) {
            return 0L;
        }
    }


    private static long cpuNowDetail() {
        if (!deepDetailCpu) return 0L;
        return cpuNow();
    }
    private static void finishForage(long forageNs) {
        ForageCtx f = forageCtx.get();
        forageCtx.remove();
        if (f == null) return;

        PipelineCtx p = pipelineCtx.get();
        Coord c = p == null ? Coord.NONE : p.coord;
        if (p != null) {
            p.forageNs = forageNs;
            p.forageGetZonesNs = f.getZonesNs;
            p.forageGetZonesCalls = f.getZonesCalls;
            p.maxForageGetZonesNs = f.maxGetZonesNs;
        }

        long other = nonNegative(forageNs - f.getZonesNs);
        if (forageNs >= spikeNs() || verbose) {
            note("MGLPZ_FORAGE_BREAKDOWN chunk=" + fmtCoord(c)
                    + " genForaging_ms=" + ms(forageNs)
                    + " getZones_ms=" + ms(f.getZonesNs)
                    + " getZones_calls=" + f.getZonesCalls
                    + " getZones_avg_ms=" + (f.getZonesCalls == 0 ? "0.000" : ms(f.getZonesNs / f.getZonesCalls))
                    + " getZones_max_ms=" + ms(f.maxGetZonesNs)
                    + " forageOther_ms=" + ms(other));
        }
    }

    private static void finishWorker(Coord c, long workerNs) {
        WorkerCtx w = workerCtx.get();
        workerCtx.remove();
        if (w == null) return;

        PipelineCtx p = pipelineCtx.get();
        if (p != null) p.copyWorker(w, workerNs);

        if (workerNs >= spikeNs() || verbose) {
            note("MGLPZ_CP2C_DEDUP chunk=" + fmtCoord(c)
                    + " enabled=" + dedupEnabled
                    + " worker_ms=" + ms(workerNs)
                    + " recalcProps_calls=" + w.recalcPropsCalls
                    + " nested_recalcProps_calls=" + w.nestedRecalcPropsCalls
                    + " dirty_clears=" + w.dedupClears
                    + " recalcNeighbours_calls=" + w.recalcNeighboursCalls);
        }

        if (p == null && (workerNs >= spikeNs() || verbose)) {
            note(workerLine("MGLPZ_CHUNK_WORKER_BREAKDOWN", c, workerNs, w));
        }
    }

    private static void finishPipeline(Coord c, long totalNs) {
        PipelineCtx p = pipelineCtx.get();
        pipelineCtx.remove();
        if (p == null) return;
        p.totalNs = totalNs;

        if (totalNs < spikeNs() && !verbose) return;

        long workerSub = p.ensure3x3Ns + p.recalcPropsNs + p.recalcNeighboursNs;
        long workerOther = nonNegative(p.workerNs - workerSub);
        long loadSourceNs = Math.max(p.diskOuterNs, Math.max(p.bufferNs, p.brandNewNs));
        long loadOrCreateOther = nonNegative(p.loadOrCreateNs - loadSourceNs - p.forageNs);
        long topKnown = p.loadChunkNs + p.vehicleNs + p.workerNs + p.gridNs;
        long pipelineOther = nonNegative(totalNs - topKnown);

        StringBuilder b = new StringBuilder();
        b.append("MGLPZ_CHUNK_PIPELINE chunk=").append(fmtCoord(c))
         .append(" total_ms=").append(ms(totalNs))
         .append(" loadChunk_ms=").append(ms(p.loadChunkNs))
         .append(" loadOrCreate_ms=").append(ms(p.loadOrCreateNs))
         .append(" disk_ms=").append(ms(p.diskNs))
         .append(" diskOuter_ms=").append(ms(p.diskOuterNs))
         .append(" diskInternal_ms=").append(ms(p.diskInternalNs))
         .append(" buffer_ms=").append(ms(p.bufferNs))
         .append(" brandNew_ms=").append(ms(p.brandNewNs))
         .append(" forage_ms=").append(ms(p.forageNs))
         .append(" forage_getZones_ms=").append(ms(p.forageGetZonesNs))
         .append(" forage_getZones_calls=").append(p.forageGetZonesCalls)
         .append(" forage_getZones_max_ms=").append(ms(p.maxForageGetZonesNs))
         .append(" loadOrCreateOther_ms=").append(ms(loadOrCreateOther))
         .append(" vehicle_ms=").append(ms(p.vehicleNs))
         .append(" worker_ms=").append(ms(p.workerNs))
         .append(" ensure3x3_ms=").append(ms(p.ensure3x3Ns))
         .append(" recalcProps_ms=").append(ms(p.recalcPropsNs))
         .append(" recalcNeighbours_ms=").append(ms(p.recalcNeighboursNs))
         .append(" workerOther_ms=").append(ms(workerOther))
         .append(" grid_ms=").append(ms(p.gridNs))
         .append(" pipelineOther_ms=").append(ms(pipelineOther))
         .append(" ensure3x3_calls=").append(p.ensure3x3Calls)
         .append(" recalcProps_calls=").append(p.recalcPropsCalls)
         .append(" recalcNeighbours_calls=").append(p.recalcNeighboursCalls)
         .append(" cp2c_dirty_clears=").append(p.dedupClears)
         .append(" cp2c_nested_recalcProps_calls=").append(p.nestedRecalcPropsCalls)
         .append(" maxRecalcProps_ms=").append(ms(p.maxRecalcPropsNs))
         .append(" maxRecalcNeighbours_ms=").append(ms(p.maxRecalcNeighboursNs));
        note(b.toString());
    }

    private static String workerLine(String prefix, Coord c, long workerNs, WorkerCtx w) {
        long sum = w.ensure3x3Ns + w.recalcPropsNs + w.recalcNeighboursNs;
        return prefix + " chunk=" + fmtCoord(c)
                + " worker_ms=" + ms(workerNs)
                + " ensure3x3_ms=" + ms(w.ensure3x3Ns)
                + " recalcProps_ms=" + ms(w.recalcPropsNs)
                + " recalcNeighbours_ms=" + ms(w.recalcNeighboursNs)
                + " workerOther_ms=" + ms(nonNegative(workerNs - sum))
                + " ensure3x3_calls=" + w.ensure3x3Calls
                + " recalcProps_calls=" + w.recalcPropsCalls
                + " recalcNeighbours_calls=" + w.recalcNeighboursCalls
                + " cp2c_dirty_clears=" + w.dedupClears
                + " cp2c_nested_recalcProps_calls=" + w.nestedRecalcPropsCalls
                + " maxRecalcProps_ms=" + ms(w.maxRecalcPropsNs)
                + " maxRecalcNeighbours_ms=" + ms(w.maxRecalcNeighboursNs);
    }

    private static void recordPipeline(String stage, long dur) {
        PipelineCtx p = pipelineCtx.get();
        if (p == null) return;
        if ("ISO.loadChunk".equals(stage)) p.loadChunkNs = dur;
        else if ("LOAD.loadOrCreate".equals(stage)) p.loadOrCreateNs = dur;
        else if ("ISO.disk".equals(stage)) p.diskNs = dur;
        else if ("LOAD.diskOuter".equals(stage)) p.diskOuterNs = dur;
        else if ("LOAD.diskInternal".equals(stage)) p.diskInternalNs = dur;
        else if ("LOAD.buffer".equals(stage)) p.bufferNs = dur;
        else if ("LOAD.brandNew".equals(stage)) p.brandNewNs = dur;
        else if ("FORAGE.genForaging".equals(stage)) p.forageNs = dur;
        else if ("VEH.loadChunk".equals(stage)) p.vehicleNs = dur;
        else if ("ISO.worker".equals(stage)) p.workerNs = dur;
        else if ("ISO.grid".equals(stage)) p.gridNs = dur;
    }

    private static void lifecycle(String stage, Coord c, long now, long dur) {
        if (!("ISO.disk".equals(stage) || "ISO.worker".equals(stage) || "ISO.main".equals(stage))) return;
        long k = key(c.x, c.y);
        Life l = life.get(k);
        if (l == null) {
            Life fresh = new Life(0L);
            Life raced = life.putIfAbsent(k, fresh);
            l = raced == null ? fresh : raced;
        }
        l.lastActivityNs.set(now);

        if ("ISO.disk".equals(stage)) {
            l.diskDone.set(now);
            return;
        }

        if ("ISO.worker".equals(stage)) {
            l.workerDone.set(now);
            long req = validTimestamp("request", c, l.requestNs.get(), now, l);
            if (req != 0L) {
                long total = now - req;
                addStat("LAT.request_worker", total);
                if (total >= eventLogNs() || verbose) {
                    if (verbose || lifecycleEventLogs) {
                        lifecyclePerEventLogged.incrementAndGet();
                        note("MGLPZ_CHUNK_WORKER_READY chunk=" + c.x + "," + c.y
                                + " request_to_worker_ms=" + ms(total)
                                + " worker_stage_ms=" + ms(dur)
                                + " inflight_est=" + life.size());
                    } else {
                        lifecycleWorkerReadySuppressed.incrementAndGet();
                    }
                }
            }
            return;
        }

        long req = validTimestamp("request", c, l.requestNs.get(), now, l);
        long worker = validTimestamp("worker", c, l.workerDone.get(), now, l);
        StringBuilder b = new StringBuilder();
        b.append("MGLPZ_CHUNK_MAIN_READY chunk=").append(c.x).append(',').append(c.y)
         .append(" main_stage_ms=").append(ms(dur))
         .append(" inflight_est=").append(life.size());

        boolean latencySlow = false;
        if (req != 0L) {
            long requestToMain = now - req;
            addStat("LAT.request_main", requestToMain);
            b.append(" request_to_main_ms=").append(ms(requestToMain));
            latencySlow |= requestToMain >= eventLogNs();
        }
        if (worker != 0L) {
            long workerToMain = now - worker;
            addStat("LAT.worker_main", workerToMain);
            b.append(" worker_to_main_ms=").append(ms(workerToMain));
            latencySlow |= workerToMain >= eventLogNs();
        }
        if (dur >= eventLogNs() || latencySlow || verbose) {
            if (verbose || lifecycleEventLogs) {
                lifecyclePerEventLogged.incrementAndGet();
                note(b.toString());
            } else {
                lifecycleMainReadySuppressed.incrementAndGet();
            }
        }
        life.remove(k, l);
    }

    private static long validTimestamp(String kind, Coord c, long value, long now, Life l) {
        if (value == 0L) return 0L;
        long age = now - value;
        long max = lifecycleMaxNs();
        if (age < 0L || age > max) {
            if ("request".equals(kind)) {
                l.requestNs.compareAndSet(value, 0L);
                lifecycleStaleRequest.incrementAndGet();
            }
            if ("worker".equals(kind)) {
                l.workerDone.compareAndSet(value, 0L);
                lifecycleStaleWorker.incrementAndGet();
            }
            if (verbose || lifecycleEventLogs) {
                lifecyclePerEventLogged.incrementAndGet();
                note("MGLPZ_CHUNK_LIFECYCLE_STALE chunk=" + c.x + "," + c.y
                        + " field=" + kind + " age_ms=" + ms(age < 0L ? -age : age)
                        + " action=suppress");
            }
            return 0L;
        }
        return value;
    }

    public static void note(String line) {
        synchronized (OUT_LOCK) {
            System.err.println(line);
            PrintWriter w = fileOut;
            if (w != null) {
                w.println(line);
                w.flush();
            }
        }
    }

    private static void startSummaryThread() {
        ScheduledExecutorService ex = Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "MGLPZ-ChunkProfiler-Summary");
                t.setDaemon(true);
                return t;
            }
        });
        long sec = summarySeconds <= 0 ? 10L : summarySeconds;
        ex.scheduleAtFixedRate(new Runnable() {
            public void run() { dumpSummary(); }
        }, sec, sec, TimeUnit.SECONDS);
    }

    public static void dumpSummary() {
        try {
            cleanupStaleLife();
            ArrayList<Map.Entry<String, Stats>> entries = new ArrayList<Map.Entry<String, Stats>>(stats.entrySet());
            Collections.sort(entries, new Comparator<Map.Entry<String, Stats>>() {
                public int compare(Map.Entry<String, Stats> a, Map.Entry<String, Stats> b) {
                    return a.getKey().compareTo(b.getKey());
                }
            });
            StringBuilder b = new StringBuilder("MGLPZ_CHUNK_SUMMARY cp=CP4.1 inflight=").append(life.size());
            for (Map.Entry<String, Stats> e : entries) {
                Stats s = e.getValue();
                long n = s.count.get();
                long total = s.totalNs.get();
                b.append(" | ").append(e.getKey())
                 .append(" n=").append(n)
                 .append(" avg_ms=").append(n == 0 ? "0" : ms(total / n))
                 .append(" max_ms=").append(ms(s.maxNs.get()))
                 .append(" slow=").append(s.slow.get());
            }
            note(b.toString());
            note("MGLPZ_CP6_10_LIFECYCLE_SUMMARY stale_request=" + lifecycleStaleRequest.get()
                    + " stale_worker=" + lifecycleStaleWorker.get()
                    + " worker_ready_suppressed=" + lifecycleWorkerReadySuppressed.get()
                    + " main_ready_suppressed=" + lifecycleMainReadySuppressed.get()
                    + " per_event_logged=" + lifecyclePerEventLogged.get()
                    + " per_event_enabled=" + lifecycleEventLogs);
            note(Optimizer.summaryLine());
        } catch (Throwable t) {
            note("MGLPZ_CHUNK_SUMMARY_ERROR " + t.getClass().getName());
        }
    }

    private static void cleanupStaleLife() {
        long now = System.nanoTime();
        long cutoff = lifecycleMaxNs() * 2L;
        for (Map.Entry<Long, Life> e : life.entrySet()) {
            Life l = e.getValue();
            long activity = l.lastActivityNs.get();
            if (activity != 0L && now - activity > cutoff) life.remove(e.getKey(), l);
        }
    }

    private static void addStat(String name, long ns) {
        Stats st = stats.get(name);
        if (st == null) {
            Stats fresh = new Stats();
            Stats raced = stats.putIfAbsent(name, fresh);
            st = raced == null ? fresh : raced;
        }
        st.add(ns, ns >= spikeNs());
    }

    private static Coord coord(Object o) {
        if (o == null) return Coord.NONE;
        try {
            Class<?> c = o.getClass();
            CoordFields f = coordCache.get(c);
            if (f == null) {
                Field a = findField(c, "wx");
                Field b = findField(c, "wy");
                boolean square = false;
                if (a == null || b == null) {
                    if ("zombie.iso.IsoGridSquare".equals(c.getName())) {
                        a = findField(c, "x");
                        b = findField(c, "y");
                        square = a != null && b != null;
                    }
                }
                if (a != null) a.setAccessible(true);
                if (b != null) b.setAccessible(true);
                CoordFields fresh = new CoordFields(a, b, square);
                CoordFields raced = coordCache.putIfAbsent(c, fresh);
                f = raced == null ? fresh : raced;
            }
            if (f.a == null || f.b == null) return Coord.NONE;
            int x = f.a.getInt(o);
            int y = f.b.getInt(o);
            if (f.squareXY) {
                x = Math.floorDiv(x, 8);
                y = Math.floorDiv(y, 8);
            }
            return new Coord(x, y, true);
        } catch (Throwable ignored) {
            return Coord.NONE;
        }
    }

    private static boolean clearPropertiesDirty(Object square) {
        if (square == null) return false;
        try {
            Class<?> c = square.getClass();
            if (!"zombie.iso.IsoGridSquare".equals(c.getName())) return false;
            Field f = propertiesDirtyField;
            if (f == null || propertiesDirtyOwner != c) {
                f = findField(c, "propertiesDirty");
                if (f == null) {
                    if (DIRTY_FIELD_FAIL_REPORTED.compareAndSet(false, true))
                        note("MGLPZ_CP2C_GUARD_FAIL reason=propertiesDirty_field_missing action=no_change");
                    return false;
                }
                f.setAccessible(true);
                propertiesDirtyOwner = c;
                propertiesDirtyField = f;
            }
            if (!f.getBoolean(square)) return false;
            f.setBoolean(square, false);
            return true;
        } catch (Throwable t) {
            if (DIRTY_FIELD_FAIL_REPORTED.compareAndSet(false, true))
                note("MGLPZ_CP2C_GUARD_FAIL reason=" + t.getClass().getName() + " action=no_change");
            return false;
        }
    }

    private static Field findField(Class<?> c, String name) {
        Class<?> cur = c;
        while (cur != null) {
            try { return cur.getDeclaredField(name); }
            catch (NoSuchFieldException e) { cur = cur.getSuperclass(); }
        }
        return null;
    }

    private static String fmtCoord(Coord c) { return c.valid ? (c.x + "," + c.y) : "NA"; }
    private static long key(int x, int y) { return (((long)x) << 32) ^ (y & 0xffffffffL); }
    private static String thread() { return Thread.currentThread().getName(); }
    private static long spikeNs() { return (long)(spikeMs * NS_PER_MS); }
    private static long eventLogNs() { return (long)(eventLogMs * NS_PER_MS); }
    private static long rootLogNs() { return (long)(rootLogMs * NS_PER_MS); }
    private static long deepNs() { return (long)(deepMs * NS_PER_MS); }
    private static long gameplayWarmupNs() { return (long)(gameplayWarmupMs * NS_PER_MS); }
    private static long gameplaySpikeNs() { return (long)(gameplaySpikeMs * NS_PER_MS); }
    private static long lifecycleMaxNs() { return (long)(lifecycleMaxMs * NS_PER_MS); }
    private static long nonNegative(long v) { return v < 0L ? 0L : v; }
    private static String ms(long ns) { synchronized (FMT) { return FMT.format(ns / 1_000_000.0); } }

    private static void openFile(String path) {
        try {
            File f = new File(path);
            File p = f.getParentFile();
            if (p != null) p.mkdirs();
            fileOut = new PrintWriter(new FileOutputStream(f, true), true);
        } catch (Throwable t) {
            fileOut = null;
            note("MGLPZ_CHUNK_LOG_OPEN_FAIL path=" + path + " error=" + t.getClass().getName());
        }
    }

    private static Map<String,String> parseArgs(String args) {
        Map<String,String> out = new HashMap<String,String>();
        if (args == null || args.trim().length() == 0) return out;
        String[] parts = args.split(",");
        for (String p : parts) {
            int eq = p.indexOf('=');
            if (eq > 0) out.put(p.substring(0, eq).trim(), p.substring(eq + 1).trim());
        }
        return out;
    }

    private static double parseDouble(String s, double def) {
        if (s == null) return def;
        try { return Double.parseDouble(s); } catch (Throwable t) { return def; }
    }
    private static long parseLong(String s, long def) {
        if (s == null) return def;
        try { return Long.parseLong(s); } catch (Throwable t) { return def; }
    }
    private static boolean parseBool(String s, boolean def) { return s == null ? def : Boolean.parseBoolean(s); }
    private static double propDouble(String k, double d) { return parseDouble(System.getProperty(k), d); }
    private static long propLong(String k, long d) { return parseLong(System.getProperty(k), d); }

    private static final class Start {
        final long ns; final Coord coord; final long cpuNs;
        Start(long ns, Coord coord, long cpuNs) { this.ns = ns; this.coord = coord; this.cpuNs = cpuNs; }
    }

    private static final class DeepFrame {
        final String stage;
        final long wallStartNs, cpuStartNs;
        long childWallNs, childCpuNs;
        DeepFrame(String stage, long wallStartNs, long cpuStartNs) {
            this.stage = stage; this.wallStartNs = wallStartNs; this.cpuStartNs = cpuStartNs;
        }
    }

    private static final class DeepCtx {
        final String parent;
        final Coord coord;
        final long wallStartNs, cpuStartNs;
        final LinkedHashMap<String, DeepPart> parts = new LinkedHashMap<String, DeepPart>();
        DeepCtx(String parent, Coord coord, long wallStartNs, long cpuStartNs) {
            this.parent = parent; this.coord = coord; this.wallStartNs = wallStartNs; this.cpuStartNs = cpuStartNs;
        }
        void add(String name, long wall, long selfWall, long cpu, long selfCpu) {
            DeepPart p = parts.get(name);
            if (p == null) { p = new DeepPart(name); parts.put(name, p); }
            p.calls++;
            p.inclusiveWallNs += wall; p.selfWallNs += selfWall;
            p.inclusiveCpuNs += cpu; p.selfCpuNs += selfCpu;
            if (wall > p.maxWallNs) p.maxWallNs = wall;
            if (cpu > p.maxCpuNs) p.maxCpuNs = cpu;
        }
    }

    private static final class DeepPart {
        final String name;
        long calls, inclusiveWallNs, selfWallNs, maxWallNs;
        long inclusiveCpuNs, selfCpuNs, maxCpuNs;
        DeepPart(String name) { this.name = name; }
    }

    private static final class Coord {
        static final Coord NONE = new Coord(0,0,false);
        final int x, y; final boolean valid;
        Coord(int x, int y, boolean valid) { this.x=x; this.y=y; this.valid=valid; }
    }

    private static final class CoordFields {
        final Field a, b; final boolean squareXY;
        CoordFields(Field a, Field b, boolean squareXY) { this.a=a; this.b=b; this.squareXY=squareXY; }
    }

    private static final class Life {
        final AtomicLong requestNs = new AtomicLong();
        final AtomicLong diskDone = new AtomicLong();
        final AtomicLong workerDone = new AtomicLong();
        final AtomicLong lastActivityNs = new AtomicLong();
        Life(long request) {
            requestNs.set(request);
            lastActivityNs.set(request == 0L ? System.nanoTime() : request);
        }
    }

    private static final class WorkerCtx {
        final Coord coord;
        final long startNs;
        long ensure3x3Ns, recalcPropsNs, recalcNeighboursNs;
        long maxEnsure3x3Ns, maxRecalcPropsNs, maxRecalcNeighboursNs;
        long ensure3x3Calls, recalcPropsCalls, recalcNeighboursCalls;
        long nestedRecalcPropsCalls, dedupClears;
        int neighbourDepth;

        WorkerCtx(Coord coord, long startNs) { this.coord=coord; this.startNs=startNs; }

        void onEnter(String stage) {
            if ("SUB.recalcNeighbours".equals(stage)) neighbourDepth++;
            else if ("SUB.recalcProps".equals(stage) && neighbourDepth > 0) nestedRecalcPropsCalls++;
        }

        void onExit(String stage) {
            if ("SUB.recalcNeighbours".equals(stage) && neighbourDepth > 0) neighbourDepth--;
        }

        void add(String stage, long ns) {
            if ("SUB.ensure3x3".equals(stage)) {
                ensure3x3Ns += ns; ensure3x3Calls++; if (ns > maxEnsure3x3Ns) maxEnsure3x3Ns = ns;
            } else if ("SUB.recalcProps".equals(stage)) {
                recalcPropsNs += ns; recalcPropsCalls++; if (ns > maxRecalcPropsNs) maxRecalcPropsNs = ns;
            } else if ("SUB.recalcNeighbours".equals(stage)) {
                recalcNeighboursNs += ns; recalcNeighboursCalls++; if (ns > maxRecalcNeighboursNs) maxRecalcNeighboursNs = ns;
            }
        }
    }

    private static final class ForageCtx {
        final long startNs;
        long getZonesStartNs;
        long getZonesNs, maxGetZonesNs, getZonesCalls;

        ForageCtx(long startNs) { this.startNs = startNs; }

        void addGetZones(long ns) {
            getZonesNs += ns;
            getZonesCalls++;
            if (ns > maxGetZonesNs) maxGetZonesNs = ns;
        }
    }

    private static final class PipelineCtx {
        final Coord coord;
        final long startNs;
        long totalNs, loadChunkNs, loadOrCreateNs, diskNs, diskOuterNs, diskInternalNs;
        long bufferNs, brandNewNs, forageNs, forageGetZonesNs, maxForageGetZonesNs;
        long forageGetZonesCalls, vehicleNs, workerNs, gridNs;
        long ensure3x3Ns, recalcPropsNs, recalcNeighboursNs;
        long maxRecalcPropsNs, maxRecalcNeighboursNs;
        long ensure3x3Calls, recalcPropsCalls, recalcNeighboursCalls;
        long nestedRecalcPropsCalls, dedupClears;

        PipelineCtx(Coord coord, long startNs) { this.coord=coord; this.startNs=startNs; }

        void copyWorker(WorkerCtx w, long workerNs) {
            this.workerNs = workerNs;
            ensure3x3Ns = w.ensure3x3Ns;
            recalcPropsNs = w.recalcPropsNs;
            recalcNeighboursNs = w.recalcNeighboursNs;
            maxRecalcPropsNs = w.maxRecalcPropsNs;
            maxRecalcNeighboursNs = w.maxRecalcNeighboursNs;
            ensure3x3Calls = w.ensure3x3Calls;
            recalcPropsCalls = w.recalcPropsCalls;
            recalcNeighboursCalls = w.recalcNeighboursCalls;
            nestedRecalcPropsCalls = w.nestedRecalcPropsCalls;
            dedupClears = w.dedupClears;
        }
    }

    private static final class Stats {
        final AtomicLong count = new AtomicLong();
        final AtomicLong totalNs = new AtomicLong();
        final AtomicLong maxNs = new AtomicLong();
        final AtomicLong slow = new AtomicLong();
        void add(long ns, boolean isSlow) {
            count.incrementAndGet();
            totalNs.addAndGet(ns);
            if (isSlow) slow.incrementAndGet();
            long prev;
            do {
                prev = maxNs.get();
                if (ns <= prev) break;
            } while (!maxNs.compareAndSet(prev, ns));
        }
    }
}
