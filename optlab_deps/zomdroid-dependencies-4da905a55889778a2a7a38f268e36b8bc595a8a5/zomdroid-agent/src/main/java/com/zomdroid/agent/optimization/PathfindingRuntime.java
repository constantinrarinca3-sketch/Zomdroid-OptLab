package com.zomdroid.agent.optimization;

import java.io.File;
import java.io.FileWriter;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runtime proof and fail-open switch for the official Build 42 ARM64 pathfinder. */
public final class PathfindingRuntime {
    public static final String FALLBACK_MARKER = ".zomdroid-pathfinding-runtime-fallback";

    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static volatile boolean requested;
    private static volatile boolean active;

    private PathfindingRuntime() {}

    public static void configureFromProperties() {
        requested = flag("zomdroid.native.pathfinding.requested");
        active = flag("zomdroid.native.pathfinding.active");
        FAILED.set(false);
    }

    public static boolean isProofEnabled() {
        return requested && active;
    }

    public static void exercised() {
        if (!isProofEnabled() || FAILED.get()) return;
        ProofRuntime.appliedOnce("PATHFINDING_NATIVE_EXERCISED",
                "native_findPath_completed_normally");
    }

    /**
     * Requests the game's own PolygonalMap2 fallback and returns null so Advice can suppress the
     * failed native request after recording it. The current request becomes an empty path; the
     * game's next checkUseNativeCode() performs the full engine handoff on its normal thread.
     */
    public static Throwable fallback(Throwable error) {
        if (error == null || !isProofEnabled()) return error;
        if (FAILED.compareAndSet(false, true)) {
            active = false;
            ProofRuntime.state("PATHFINDING_NATIVE_FALLBACK", "FALLBACK",
                    "original_java_polygonalmap2 reason=" + error.getClass().getSimpleName());
            requestJavaFallback();
            writeFallbackMarker(error);
        }
        return null;
    }

    /** Records a thread-loop failure but lets PZ's existing outer catch consume the throwable. */
    public static void threadFailure(Throwable error) {
        if (error != null) fallback(error);
    }

    private static void requestJavaFallback() {
        try {
            Class<?> optionsClass = Class.forName("zombie.debug.DebugOptions");
            Field instanceField = optionsClass.getField("instance");
            Object options = instanceField.get(null);
            Field pathfindField = optionsClass.getField("pathfindUseNativeCode");
            Object option = pathfindField.get(options);
            Method setValue = option.getClass().getMethod("setValue", boolean.class);
            setValue.invoke(option, false);
        } catch (Throwable reflectionFailure) {
            ProofRuntime.state("PATHFINDING_NATIVE_FALLBACK", "FALLBACK_PENDING_RESTART",
                    "debug_option_reflection=" + reflectionFailure.getClass().getSimpleName());
        }
    }

    private static void writeFallbackMarker(Throwable error) {
        try {
            String home = System.getProperty("user.home", "").trim();
            if (home.isEmpty()) return;
            File marker = new File(home, FALLBACK_MARKER);
            try (FileWriter writer = new FileWriter(marker, false)) {
                writer.write("reason=");
                writer.write(error.getClass().getName());
                writer.write('\n');
            }
        } catch (Throwable ignored) {
            // A diagnostic marker must never become another runtime failure.
        }
    }

    private static boolean flag(String property) {
        return "1".equals(System.getProperty(property, "0"));
    }
}
