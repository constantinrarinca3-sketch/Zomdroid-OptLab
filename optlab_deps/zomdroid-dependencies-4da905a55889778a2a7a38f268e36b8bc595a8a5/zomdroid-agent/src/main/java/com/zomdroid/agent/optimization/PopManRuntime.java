package com.zomdroid.agent.optimization;

import java.io.File;
import java.io.FileWriter;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runtime proof and fail-closed restart switch for the official ARM64 PopMan. */
public final class PopManRuntime {
    public static final String FALLBACK_MARKER = ".zomdroid-popman-runtime-fallback";

    private static final AtomicBoolean FAILED = new AtomicBoolean();
    private static final AtomicBoolean BRIDGE_LOADED = new AtomicBoolean();
    private static volatile boolean requested;
    private static volatile boolean active;

    private PopManRuntime() {}

    public static void configureFromProperties() {
        requested = flag("zomdroid.native.popman.requested");
        active = flag("zomdroid.native.popman.active");
        FAILED.set(false);
        BRIDGE_LOADED.set(false);
    }

    public static boolean isProofEnabled() {
        return requested && active;
    }

    /** Called after the game's own System.loadLibrary("PZPopMan64") succeeds. */
    public static Throwable loadSaveCellBridge(Throwable existingError) {
        if (existingError != null || !isProofEnabled() || BRIDGE_LOADED.get()) {
            return existingError;
        }
        try {
            System.loadLibrary("PZPopManSaveCellBridge");
            BRIDGE_LOADED.set(true);
            ProofRuntime.state("POPMAN_NATIVE_ACTIVE", "ACTIVE",
                    "official_arm64_with_exact_save_cell_bridge");
            return null;
        } catch (Throwable error) {
            return fallback(error, "bridge_load");
        }
    }

    public static Throwable saveCellCompleted(Throwable error) {
        if (!isProofEnabled()) return error;
        if (error == null) {
            ProofRuntime.appliedOnce("POPMAN_NATIVE_EXERCISED",
                    "native_writeCellSnapshot_completed_normally");
            return null;
        }
        return fallback(error, "write_cell_snapshot");
    }

    private static Throwable fallback(Throwable error, String route) {
        if (FAILED.compareAndSet(false, true)) {
            active = false;
            ProofRuntime.state("POPMAN_NATIVE_FALLBACK", "FALLBACK_PENDING_RESTART",
                    route + " reason=" + error.getClass().getSimpleName());
            writeFallbackMarker(error, route);
        }
        // Never suppress a failed population save. A loud failure plus a safe x86 restart is
        // preferable to continuing with an incomplete world snapshot.
        return error;
    }

    private static void writeFallbackMarker(Throwable error, String route) {
        try {
            String home = System.getProperty("user.home", "").trim();
            if (home.isEmpty()) return;
            File marker = new File(home, FALLBACK_MARKER);
            try (FileWriter writer = new FileWriter(marker, false)) {
                writer.write("route=");
                writer.write(route);
                writer.write("\nreason=");
                writer.write(error.getClass().getName());
                writer.write('\n');
            }
        } catch (Throwable ignored) {
            // A diagnostic marker must never replace the original failure.
        }
    }

    private static boolean flag(String property) {
        return "1".equals(System.getProperty(property, "0"));
    }
}
