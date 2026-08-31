package com.zomdroid.agent.optimization;

/** Runtime gate for the exact CP6.13.4 FBO preparation loop. */
public final class FboInnerLoopRuntime {
    private static volatile boolean enabled;
    private static boolean proof;

    private FboInnerLoopRuntime() {}

    public static void configure(boolean value) {
        enabled = value;
        proof = false;
        ProofRuntime.state("FBO_INNER_LOOP", "CONFIGURED",
                "enabled=" + (value ? 1 : 0)
                        + " mode=exact_work_no_skip_no_defer"
                        + " telemetry=bounded_first_hit_only");
    }

    public static boolean isEnabled() {
        return enabled;
    }

    public static void noteHandled() {
        if (!proof) {
            proof = true;
            ProofRuntime.appliedOnce("FBO_INNER_LOOP",
                    "cp6_13_4_flags_reference_and_player_light_reused"
                            + " skip=0 defer=0");
        }
    }

    public static void disable(String reason) {
        enabled = false;
        FeatureCompatibility.fallback("FBO_INNER_LOOP", reason);
    }
}
