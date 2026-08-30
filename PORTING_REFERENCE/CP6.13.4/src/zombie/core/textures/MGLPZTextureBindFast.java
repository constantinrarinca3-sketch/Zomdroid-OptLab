package zombie.core.textures;

import mglpz.chunkagent.Optimizer;
import zombie.debug.DebugOptions;

/**
 * CP6.6: short-circuit Texture.bind(int) when vanilla's TextureID.bind() would be a no-op.
 * Fails closed for bindAlways, invalid/not-ready/destroyed textures, non-positive IDs, and
 * bound-texture debug checking so diagnostic semantics remain exact.
 */
public final class MGLPZTextureBindFast {
    private static long calls, hits, debugFallback, stateFallback, idFallback;
    private static final long MASK = 65535L;
    private MGLPZTextureBindFast() {}

    public static boolean tryAlreadyBound(Texture texture, int target) {
        final long n = ++calls;
        if (!Optimizer.rthreadTextureBindFastEnabled() || target != 3553 || texture == null) {
            publish(n); return false;
        }
        try {
            if (texture.bindAlways || texture.isDestroyed() || !texture.isValid() || !texture.isReady()) {
                ++stateFallback; publish(n); return false;
            }
            DebugOptions opts = DebugOptions.instance;
            if (opts != null && opts.checks != null && opts.checks.boundTextures != null
                    && opts.checks.boundTextures.getValue()) {
                ++debugFallback; publish(n); return false;
            }
            int id = texture.getID();
            if (id <= 0 || id != Texture.lastTextureID) {
                ++idFallback; publish(n); return false;
            }
            ++hits;
            publish(n);
            return true;
        } catch (Throwable ignored) {
            ++stateFallback; publish(n); return false;
        }
    }

    private static void publish(long n) {
        if ((n & MASK) == 0L) {
            Optimizer.publishTextureBindFast(calls,hits,debugFallback,stateFallback,idFallback);
        }
    }
}
