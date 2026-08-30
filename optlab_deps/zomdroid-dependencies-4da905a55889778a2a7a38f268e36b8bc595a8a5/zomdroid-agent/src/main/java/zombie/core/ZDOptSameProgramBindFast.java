package zombie.core;

import com.zomdroid.agent.optimization.SameProgramBindRuntime;

import zombie.debug.BooleanDebugOption;
import zombie.debug.DebugOptions;

/** CP6.13.10 guard: skip only a redundant positive bind against PZ's own state. */
public final class ZDOptSameProgramBindFast {
    private ZDOptSameProgramBindFast() {}

    public static boolean shouldSkip(int requested, int currentlyBound) {
        if (!SameProgramBindRuntime.isEnabled()) return false;
        if (requested <= 0 || requested != currentlyBound) return false;
        try {
            DebugOptions options = DebugOptions.instance;
            if (options == null || options.checks == null) return false;
            BooleanDebugOption boundShader = options.checks.boundShader;
            if (boundShader == null || boundShader.getValue()) return false;
            SameProgramBindRuntime.noteSkipped();
            return true;
        } catch (Throwable error) {
            SameProgramBindRuntime.disable(
                    "runtime_guard=" + error.getClass().getSimpleName());
            return false;
        }
    }
}
