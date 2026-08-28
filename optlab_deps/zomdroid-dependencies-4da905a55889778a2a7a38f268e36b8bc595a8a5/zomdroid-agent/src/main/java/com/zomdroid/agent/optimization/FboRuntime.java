package com.zomdroid.agent.optimization;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.WeakHashMap;

/** Conservative FBO work governor. It never defers a texture that has not rendered once. */
public final class FboRuntime {
    private static volatile boolean dirtyDedupEnabled;
    private static volatile boolean frameBudgetEnabled;
    private static volatile boolean coordinatorEnabled;
    private static volatile int normalBudget = 6;
    private static volatile int urgentBudget = 2;
    private static volatile int maxDeferralFrames = 2;

    private static final Map<Object, Integer> DENIED_STREAK = new WeakHashMap<>();
    private static final IdentityHashMap<Object, Boolean> FRAME_DECISIONS = new IdentityHashMap<>();
    private static long currentFrame = Long.MIN_VALUE;
    private static int admissions;

    private static volatile boolean managerReflectionReady;
    private static Field managerRenderChunk;
    private static Field renderChunkInit;
    private static Field renderChunkSubmitted;
    private static Field renderChunkTexture;
    private static Field chunkWx;
    private static Field chunkWy;

    private static volatile boolean worldReflectionReady;
    private static Field worldInstance;
    private static Method worldFrameNo;
    private static Field playerArray;
    private static Method playerX;
    private static Method playerY;

    private static volatile boolean dirtyReflectionReady;
    private static Field zoomInfoArray;
    private static Field zoomDirty;
    private static Field zoomDirtyFlags;

    private FboRuntime() {}

    public static void configure(boolean dirtyDedup, boolean frameBudget, boolean coordinator) {
        dirtyDedupEnabled = dirtyDedup;
        frameBudgetEnabled = frameBudget;
        coordinatorEnabled = coordinator;
        normalBudget = intProperty("zomdroid.optlab.fbo.budget", 6, 2, 16);
        urgentBudget = intProperty("zomdroid.optlab.fbo.urgent.budget", 2, 1, normalBudget);
        maxDeferralFrames = intProperty("zomdroid.optlab.fbo.max.defer.frames", 2, 1, 5);
    }

    public static void disableBudgetShape(int replacements) {
        frameBudgetEnabled = false;
        coordinatorEnabled = false;
        FeatureCompatibility.fallback("FBO_FRAME_BUDGET",
                "dirty_gate_replacements=" + replacements);
        FeatureCompatibility.fallback("STREAM_FBO_COORDINATOR",
                "dirty_gate_replacements=" + replacements);
    }

    public static void disableDirtyDedup() {
        dirtyDedupEnabled = false;
    }

    public static void disableFrameBudget() {
        frameBudgetEnabled = false;
    }

    public static void disableCoordinator() {
        coordinatorEnabled = false;
    }

    public static boolean allowDirty(boolean dirty, Object manager, Object chunk, int level, float zoom) {
        if (!dirty || !frameBudgetEnabled) return dirty;
        try {
            ensureManagerReflection(manager, chunk);
            long frame = frameNumber(chunk.getClass().getClassLoader());
            synchronized (FboRuntime.class) {
                if (frame != currentFrame) {
                    currentFrame = frame;
                    admissions = 0;
                    FRAME_DECISIONS.clear();
                }
                Object renderChunk = managerRenderChunk.get(manager);
                // First render, recycled texture, or a cache not yet submitted: bypass governor.
                boolean reusable = renderChunk != null
                        && renderChunkInit.getBoolean(renderChunk)
                        && renderChunkSubmitted.getBoolean(renderChunk)
                        && renderChunkTexture.get(renderChunk) != null;
                if (!reusable) return true;

                // FBORenderChunk is the actual level/zoom work item. Keying decisions and
                // fairness by IsoChunk could let one cached level suppress a newly created one.
                Boolean prior = FRAME_DECISIONS.get(renderChunk);
                if (prior != null) return prior;

                boolean urgent = coordinatorEnabled && StreamCoreRuntime.isUrgent();
                int limit = urgent ? urgentBudget : normalBudget;
                int streak = DENIED_STREAK.getOrDefault(renderChunk, 0);
                boolean critical = isNearPlayer(chunk);
                boolean allow = critical || admissions < limit
                        || streak >= maxDeferralFrames;
                if (allow) {
                    if (!critical) admissions++;
                    DENIED_STREAK.remove(renderChunk);
                } else {
                    DENIED_STREAK.put(renderChunk, streak + 1);
                    ProofRuntime.appliedOnce("FBO_FRAME_BUDGET",
                            "cached_fbo_deferred_dirty_preserved_max_frames=" + maxDeferralFrames);
                    if (urgent) {
                        ProofRuntime.appliedOnce("STREAM_FBO_COORDINATOR",
                                "urgent_stream_noncritical_fbo_budget=" + limit);
                    }
                }
                FRAME_DECISIONS.put(renderChunk, allow);
                return allow;
            }
        } catch (Throwable error) {
            frameBudgetEnabled = false;
            coordinatorEnabled = false;
            FeatureCompatibility.fallback("FBO_FRAME_BUDGET",
                    "runtime=" + error.getClass().getSimpleName());
            FeatureCompatibility.fallback("STREAM_FBO_COORDINATOR",
                    "runtime=" + error.getClass().getSimpleName());
            return dirty;
        }
    }

    public static boolean shouldSkipSetDirty(Object nLevels, long flags) {
        if (!dirtyDedupEnabled || nLevels == null || flags == 0L) return false;
        try {
            ensureDirtyReflection(nLevels);
            Object[] infos = (Object[]) zoomInfoArray.get(nLevels);
            if (infos == null || infos.length == 0) return false;
            for (Object info : infos) {
                if (info == null || !zoomDirty.getBoolean(info)
                        || (zoomDirtyFlags.getLong(info) & flags) != flags) {
                    return false;
                }
            }
            ProofRuntime.appliedOnce("FBO_DIRTY_DEDUP", "identical_dirty_epoch_coalesced");
            return true;
        } catch (Throwable error) {
            dirtyDedupEnabled = false;
            FeatureCompatibility.fallback("FBO_DIRTY_DEDUP",
                    "runtime=" + error.getClass().getSimpleName());
            return false;
        }
    }

    private static boolean isNearPlayer(Object chunk) throws Exception {
        ensureWorldReflection(chunk.getClass().getClassLoader());
        // IsoChunk coordinates address 10x10 world-square chunks.
        float centerX = chunkWx.getInt(chunk) * 10.0f + 5.0f;
        float centerY = chunkWy.getInt(chunk) * 10.0f + 5.0f;
        Object[] players = (Object[]) playerArray.get(null);
        if (players == null) return true;
        for (Object player : players) {
            if (player == null) continue;
            float dx = centerX - ((Number) playerX.invoke(player)).floatValue();
            float dy = centerY - ((Number) playerY.invoke(player)).floatValue();
            if (dx * dx + dy * dy <= 144.0f) return true;
        }
        return false;
    }

    private static long frameNumber(ClassLoader loader) throws Exception {
        ensureWorldReflection(loader);
        Object world = worldInstance.get(null);
        if (world == null) return System.nanoTime() / 16_666_667L;
        return ((Number) worldFrameNo.invoke(world)).longValue();
    }

    private static synchronized void ensureManagerReflection(Object manager, Object chunk)
            throws Exception {
        if (managerReflectionReady) return;
        managerRenderChunk = manager.getClass().getField("renderChunk");
        ClassLoader loader = chunk.getClass().getClassLoader();
        Class<?> renderChunk = Class.forName(
                "zombie.iso.fboRenderChunk.FBORenderChunk", false, loader);
        renderChunkInit = renderChunk.getField("isInit");
        renderChunkSubmitted = renderChunk.getField("submitted");
        renderChunkTexture = renderChunk.getField("tex");
        chunkWx = chunk.getClass().getField("wx");
        chunkWy = chunk.getClass().getField("wy");
        managerReflectionReady = true;
    }

    private static synchronized void ensureWorldReflection(ClassLoader loader) throws Exception {
        if (worldReflectionReady) return;
        Class<?> world = Class.forName("zombie.iso.IsoWorld", false, loader);
        worldInstance = world.getField("instance");
        worldFrameNo = world.getMethod("getFrameNo");
        Class<?> player = Class.forName("zombie.characters.IsoPlayer", false, loader);
        playerArray = player.getField("players");
        playerX = player.getMethod("getX");
        playerY = player.getMethod("getY");
        worldReflectionReady = true;
    }

    private static synchronized void ensureDirtyReflection(Object nLevels) throws Exception {
        if (dirtyReflectionReady) return;
        zoomInfoArray = nLevels.getClass().getDeclaredField("zoomInfo");
        zoomInfoArray.setAccessible(true);
        Class<?> info = zoomInfoArray.getType().getComponentType();
        zoomDirty = info.getDeclaredField("dirty");
        zoomDirty.setAccessible(true);
        zoomDirtyFlags = info.getDeclaredField("dirtyFlags");
        zoomDirtyFlags.setAccessible(true);
        dirtyReflectionReady = true;
    }

    private static int intProperty(String key, int fallback, int min, int max) {
        try {
            return Math.max(min, Math.min(max, Integer.parseInt(System.getProperty(key, ""))));
        } catch (RuntimeException ignored) {
            return fallback;
        }
    }

}
