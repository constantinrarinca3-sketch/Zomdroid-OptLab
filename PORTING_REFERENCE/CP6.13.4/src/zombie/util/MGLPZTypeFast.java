package zombie.util;

import zombie.characters.IsoZombie;
import mglpz.chunkagent.Optimizer;

/** CP6.7: specialize the MovingObject scheduler's hot IsoZombie cast, generic fallback exact. */
public final class MGLPZTypeFast {
    private static long calls, zombieClassCalls, zombieHits;
    private static final long MASK = 65535L;
    private MGLPZTypeFast() {}

    @SuppressWarnings("unchecked")
    public static <R, I> R tryCast(I value, Class<R> clazz) {
        long n = ++calls;
        if (Optimizer.javaZombieCastFastEnabled() && clazz == IsoZombie.class) {
            ++zombieClassCalls;
            if (value instanceof IsoZombie) {
                ++zombieHits;
                publish(n);
                return (R)value;
            }
            publish(n);
            return null;
        }
        // Exact vanilla semantics, including NPE when clazz is null.
        R out = clazz.isInstance(value) ? clazz.cast(value) : null;
        publish(n);
        return out;
    }

    private static void publish(long n) {
        if ((n & MASK) == 0L) Optimizer.publishZombieCastFast(calls, zombieClassCalls, zombieHits);
    }
}
