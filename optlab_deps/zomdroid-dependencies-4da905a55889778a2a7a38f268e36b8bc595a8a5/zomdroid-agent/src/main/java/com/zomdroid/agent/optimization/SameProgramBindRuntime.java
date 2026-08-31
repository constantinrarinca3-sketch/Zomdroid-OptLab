package com.zomdroid.agent.optimization;

/** Runtime gate and bounded proof for CP6.13.10 same-program bind. */
public final class SameProgramBindRuntime {
    private static volatile boolean enabled;
    private static boolean proof;

    private SameProgramBindRuntime() {}

    public static void configure(boolean value) {
        enabled = value;
        proof = false;
        ProofRuntime.state("RTHREAD_SAME_PROGRAM_BIND", "CONFIGURED",
                "enabled=" + (value ? 1 : 0)
                        + " positive_program_only=1 debug_fallback=1"
                        + " telemetry=bounded_first_hit_only");
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void noteSkipped() {
        if (!proof) {
            proof = true;
            ProofRuntime.appliedOnce("RTHREAD_SAME_PROGRAM_BIND",
                    "cp6_13_10_authoritative_currently_bound_match");
        }
    }

    public static void disable(String reason) {
        enabled = false;
        FeatureCompatibility.fallback("RTHREAD_SAME_PROGRAM_BIND", reason);
    }
}
