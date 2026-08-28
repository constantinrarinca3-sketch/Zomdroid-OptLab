package com.zomdroid.agent.optimization;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.time.Instant;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/** A small, bounded, machine-readable proof channel shared by every OPT-LAB hook. */
public final class ProofRuntime {
    private static final Object LOCK = new Object();
    private static final Set<String> ONCE = new HashSet<>();
    private static final int MAX_LINES = 96;

    private static volatile String path = "";
    private static volatile String session = "unknown";
    private static int lines;

    private ProofRuntime() {}

    public static void configureFromProperties() {
        path = System.getProperty("zomdroid.optlab.proof.path", "").trim();
        session = System.getProperty("zomdroid.optlab.session", "unknown").trim();
        emit("SESSION", "START", "schema=7 path=" + clean(path));
    }

    public static void state(String mechanism, String state, String detail) {
        emit(mechanism, state, detail);
    }

    public static void appliedOnce(String mechanism, String detail) {
        synchronized (LOCK) {
            if (!ONCE.add(mechanism)) return;
        }
        emit(mechanism, "EXERCISED", detail);
    }

    private static void emit(String mechanism, String state, String detail) {
        String line = "[ZD-OPT-PROOF]"
                + " session=" + clean(session)
                + " mechanism=" + clean(mechanism)
                + " state=" + clean(state)
                + " detail=" + clean(detail)
                + " utc=" + Instant.now();
        System.out.println(line);

        String output = path;
        if (output.isEmpty()) return;
        synchronized (LOCK) {
            if (lines >= MAX_LINES) return;
            lines++;
            try {
                File file = new File(output);
                File parent = file.getParentFile();
                if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                    return;
                }
                try (PrintWriter writer = new PrintWriter(new FileWriter(file, true))) {
                    writer.println(line);
                }
            } catch (Throwable ignored) {
                // Proof must never become a game-start dependency.
            }
        }
    }

    private static String clean(Object value) {
        if (value == null) return "null";
        return String.valueOf(value)
                .replace('\n', '_')
                .replace('\r', '_')
                .replace(' ', '_')
                .toLowerCase(Locale.ROOT);
    }
}
