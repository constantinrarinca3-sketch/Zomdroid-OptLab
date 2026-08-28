package com.zomdroid.agent.optimization;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-mechanism B42 compatibility registry.
 *
 * <p>An exact class hash is strong evidence, not a global kill switch. Unknown B42 class hashes
 * may reach the structural probe in {@code Main}; a mechanism becomes active only after every
 * required class shape has passed. Missing classes, non-B42 instances, shape mismatches and
 * transform errors fail open for that mechanism alone.</p>
 */
public final class FeatureCompatibility {
    public enum Compatibility {
        VERIFIED,
        COMPATIBLE,
        PROBE,
        UNSUPPORTED
    }

    public static final class Requirement {
        public final String label;
        public final String expectedSha256;
        public final String actualSha256;

        public Requirement(String label, String expectedSha256, String actualSha256) {
            this.label = token(label);
            this.expectedSha256 = token(expectedSha256);
            this.actualSha256 = token(actualSha256);
        }

        private boolean exact() {
            return expectedSha256.equals(actualSha256);
        }

        private boolean available() {
            return !("MISSING".equals(actualSha256) || "ERROR".equals(actualSha256)
                    || "UNKNOWN".equals(actualSha256));
        }

        private String proof() {
            return label + "_sha256_" + actualSha256;
        }
    }

    public static final class Decision {
        private final String mechanism;
        private final boolean requested;
        private final Compatibility initialCompatibility;
        private final int requiredParts;
        private final LinkedHashSet<String> passedParts = new LinkedHashSet<>();
        private boolean blocked;
        private boolean active;

        private Decision(String mechanism, boolean requested, Compatibility compatibility,
                         int requiredParts) {
            this.mechanism = token(mechanism);
            this.requested = requested;
            this.initialCompatibility = compatibility;
            this.requiredParts = Math.max(1, requiredParts);
        }

        public synchronized boolean isCandidate() {
            return requested && !blocked
                    && (initialCompatibility == Compatibility.VERIFIED
                    || initialCompatibility == Compatibility.PROBE);
        }

        public synchronized boolean isActive() {
            return active && !blocked;
        }

        public synchronized void passPart(String part) {
            if (!isCandidate()) return;
            passedParts.add(token(part));
            if (!active && passedParts.size() >= requiredParts) {
                active = true;
                Compatibility result = initialCompatibility == Compatibility.VERIFIED
                        ? Compatibility.VERIFIED : Compatibility.COMPATIBLE;
                ProofRuntime.state(mechanism, "ACTIVE",
                        "compatibility=" + result + " structural_parts=" + passedParts);
            }
        }

        public synchronized void fallback(String reason) {
            if (!requested || blocked) return;
            blocked = true;
            active = false;
            ProofRuntime.state(mechanism, "UNSUPPORTED",
                    "fallback=original_game_code reason=" + token(reason));
        }

        public String mechanism() {
            return mechanism;
        }
    }

    private static final Map<String, Decision> DECISIONS = new ConcurrentHashMap<>();
    private static volatile String buildFamily = "UNKNOWN";
    private static volatile String versionHint = "UNKNOWN";
    private static volatile String jarSha256 = "UNKNOWN";
    private static volatile boolean knownJar;
    private static volatile boolean onlyBuild42 = true;

    private FeatureCompatibility() {}

    public static void configureFromProperties() {
        DECISIONS.clear();
        buildFamily = property("zomdroid.optlab.pz.build.family");
        versionHint = property("zomdroid.optlab.pz.version.hint");
        jarSha256 = property("zomdroid.optlab.jar.sha256");
        knownJar = "1".equals(System.getProperty("zomdroid.optlab.jar.known", "0"));
        onlyBuild42 = "1".equals(System.getProperty(
                "zomdroid.optlab.only.build42", "1"));
        ProofRuntime.state("PZ_IDENTITY", "DETECTED",
                "build_family=" + buildFamily + " version_hint=" + versionHint
                        + " jar_sha256=" + jarSha256 + " known_jar=" + bit(knownJar)
                        + " only_build_42=" + bit(onlyBuild42));
    }

    public static Decision evaluate(String mechanism, boolean requested, int requiredParts,
                                    Requirement... requirements) {
        String id = token(mechanism);
        if (!requested) return register(new Decision(id, false, Compatibility.UNSUPPORTED,
                requiredParts), "OFF", "not_requested");
        if (!"42".equals(buildFamily) && onlyBuild42) {
            return register(new Decision(id, true, Compatibility.UNSUPPORTED, requiredParts),
                    "UNSUPPORTED", "fallback=original_game_code only_build_42=1 build_family="
                            + buildFamily);
        }

        // Known B42 hashes stay VERIFIED. Cross-family use, when the user deliberately disables
        // the default policy, must always prove its shape and can only become COMPATIBLE.
        boolean exact = "42".equals(buildFamily);
        StringBuilder detail = new StringBuilder();
        for (Requirement requirement : requirements) {
            if (detail.length() > 0) detail.append(',');
            detail.append(requirement.proof());
            if (!requirement.available()) {
                return register(new Decision(id, true, Compatibility.UNSUPPORTED, requiredParts),
                        "UNSUPPORTED", "fallback=original_game_code missing=" + requirement.label);
            }
            exact &= requirement.exact();
        }

        Compatibility compatibility = exact ? Compatibility.VERIFIED : Compatibility.PROBE;
        String reason = exact ? "exact_class_hashes=" : "unknown_b42_structural_probe=";
        return register(new Decision(id, true, compatibility, requiredParts),
                compatibility.name(), reason + detail);
    }

    public static Decision unsupported(String mechanism, boolean requested, String reason) {
        Decision decision = new Decision(mechanism, requested, Compatibility.UNSUPPORTED, 1);
        return register(decision, requested ? "UNSUPPORTED" : "OFF",
                requested ? "fallback=original_game_code reason=" + token(reason)
                        : "not_requested");
    }

    public static Decision decision(String mechanism) {
        return DECISIONS.get(token(mechanism));
    }

    public static void fallback(String mechanism, String reason) {
        Decision decision = decision(mechanism);
        if (decision != null) decision.fallback(reason);
    }

    public static Map<String, Decision> decisions() {
        return Collections.unmodifiableMap(DECISIONS);
    }

    private static Decision register(Decision decision, String state, String detail) {
        DECISIONS.put(decision.mechanism, decision);
        ProofRuntime.state(decision.mechanism, state, detail);
        return decision;
    }

    private static String property(String name) {
        return token(System.getProperty(name, "UNKNOWN"));
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }

    private static String token(String value) {
        if (value == null || value.trim().isEmpty()) return "UNKNOWN";
        return value.trim().replace('\n', '_').replace('\r', '_').replace(' ', '_');
    }
}
