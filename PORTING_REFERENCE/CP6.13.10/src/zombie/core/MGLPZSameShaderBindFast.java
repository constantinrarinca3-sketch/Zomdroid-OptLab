package zombie.core;

import zombie.debug.DebugOptions;

/**
 * CP6.13.10 same-program bind fastpath.
 *
 * The transformed ShaderHelper.glUseProgramObjectARB(int) wrapper passes both the requested
 * program and ShaderHelper.currentlyBound.  That private field is the game's authoritative
 * program state, so no independent shadow can drift when another route changes shaders.
 *
 * Only the call into the original bind helper is skipped.  StartShader callbacks, shader lookup,
 * id==0 texture-state handling and ShaderUniformSetter.invokeAll() remain in their existing order.
 */
public final class MGLPZSameShaderBindFast {
    public static final boolean ENABLED = flag("mglpz.cp61310.sameShaderBindFast", true);
    private static long calls, skipped, nonPositive, debugFallback;

    private MGLPZSameShaderBindFast() {}

    public static boolean shouldSkip(int requested, int currentlyBound) {
        ++calls;
        if (!ENABLED) return false;
        // Keep program 0 on the exact vanilla path for the first production checkpoint.
        if (requested <= 0) { ++nonPositive; return false; }
        if (DebugOptions.instance != null && DebugOptions.instance.checks != null
                && DebugOptions.instance.checks.boundShader != null
                && DebugOptions.instance.checks.boundShader.getValue()) {
            ++debugFallback;
            return false;
        }
        if (requested == currentlyBound) {
            ++skipped;
            return true;
        }
        return false;
    }

    public static String summary() {
        return "cp61310_same_shader_bind_fast="+ENABLED
                +" bind_calls="+calls
                +" bind_skipped="+skipped
                +" bind_skip_pct="+pct(skipped,calls)
                +" bind_non_positive="+nonPositive
                +" bind_debug_fallback="+debugFallback;
    }

    private static String pct(long a,long b) {
        if (b<=0L) return "0.00";
        return String.format(java.util.Locale.ROOT,"%.2f",100.0*a/b);
    }
    private static boolean flag(String k,boolean d) {
        String v=System.getProperty(k); if(v==null)return d;
        return !("0".equals(v)||"false".equalsIgnoreCase(v)||"off".equalsIgnoreCase(v));
    }
}
