package zombie.core;

import com.zomdroid.agent.optimization.SameProgramBindRuntime;

import zombie.debug.BooleanDebugOption;
import zombie.debug.DebugOptions;

/** Semantic gate for the CP6.13.10 decision helper. */
public final class Cp61310SameProgramBindUnit {
    private Cp61310SameProgramBindUnit() {}

    public static void main(String[] args) {
        DebugOptions original = DebugOptions.instance;
        try {
            DebugOptions.instance = new DebugOptions();
            SameProgramBindRuntime.configure(true);

            require(ZDOptSameProgramBindFast.shouldSkip(7, 7),
                    "positive authoritative match must skip");
            require(!ZDOptSameProgramBindFast.shouldSkip(7, 8),
                    "different program must use vanilla");
            require(!ZDOptSameProgramBindFast.shouldSkip(0, 0),
                    "program zero must use vanilla");
            require(!ZDOptSameProgramBindFast.shouldSkip(-1, -1),
                    "negative program must use vanilla");

            DebugOptions.instance.checks.boundShader.value = true;
            require(!ZDOptSameProgramBindFast.shouldSkip(7, 7),
                    "bound-shader debug must use vanilla");
            DebugOptions.instance.checks.boundShader.value = false;
            DebugOptions.instance.checks.boundShader = null;
            require(!ZDOptSameProgramBindFast.shouldSkip(7, 7),
                    "missing debug shape must use vanilla");

            DebugOptions.instance.checks.boundShader = new BooleanDebugOption() {
                @Override public boolean getValue() { throw new RuntimeException("test"); }
            };
            require(!ZDOptSameProgramBindFast.shouldSkip(7, 7),
                    "debug failure must use vanilla");
            require(!SameProgramBindRuntime.isEnabled(),
                    "runtime failure must disable only this mechanism");

            DebugOptions.instance = new DebugOptions();
            SameProgramBindRuntime.configure(false);
            require(!ZDOptSameProgramBindFast.shouldSkip(7, 7),
                    "disabled feature must use vanilla");
            System.out.println("CP61310_SAME_PROGRAM_BIND_UNIT PASS"
                    + " positive_match=1 zero_fallback=1 debug_fallback=1"
                    + " runtime_isolation=1");
        } finally {
            DebugOptions.instance = original;
            SameProgramBindRuntime.configure(false);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
