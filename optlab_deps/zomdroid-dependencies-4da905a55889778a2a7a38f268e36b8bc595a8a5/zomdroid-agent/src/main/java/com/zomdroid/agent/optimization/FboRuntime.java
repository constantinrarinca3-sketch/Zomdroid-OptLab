package com.zomdroid.agent.optimization;

import java.lang.reflect.Field;

/** Fail-open runtime for the semantics-preserving FBO dirty-bit deduplication only. */
public final class FboRuntime {
    private static volatile boolean dirtyDedupEnabled;

    private static volatile boolean dirtyReflectionReady;
    private static Field zoomInfoArray;
    private static Field zoomDirty;
    private static Field zoomDirtyFlags;

    private FboRuntime() {}

    public static void configure(boolean dirtyDedup) {
        dirtyDedupEnabled = dirtyDedup;
    }

    public static void disableDirtyDedup() {
        dirtyDedupEnabled = false;
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
}
