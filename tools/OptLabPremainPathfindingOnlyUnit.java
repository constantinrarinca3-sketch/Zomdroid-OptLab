import com.zomdroid.agent.Main;

import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.concurrent.atomic.AtomicInteger;

/** Regression: Pathfinding alone must pass both premain gates and install Byte Buddy. */
public final class OptLabPremainPathfindingOnlyUnit {
    private static final String[] OPT_LAB_FLAGS = {
            "zomdroid.optlab.pacing",
            "zomdroid.optlab.stream.wake",
            "zomdroid.optlab.stream.queue.fast",
            "zomdroid.optlab.stream.velocity.eta",
            "zomdroid.optlab.fbo.dirty.dedup",
            "zomdroid.optlab.fbo.inner.loop",
            "zomdroid.optlab.render.same.program.bind",
            "zomdroid.native.popman.requested",
            "zomdroid.native.popman.active"
    };

    private OptLabPremainPathfindingOnlyUnit() {}

    public static void main(String[] args) {
        for (String flag : OPT_LAB_FLAGS) System.setProperty(flag, "0");
        System.setProperty("zomdroid.renderer", "ZINK");
        System.setProperty("zomdroid.optlab.proof.path", "");
        System.setProperty("zomdroid.native.pathfinding.requested", "1");
        System.setProperty("zomdroid.native.pathfinding.active", "1");

        AtomicInteger transformers = new AtomicInteger();
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                OptLabPremainPathfindingOnlyUnit.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
                (proxy, method, methodArgs) -> {
                    if ("addTransformer".equals(method.getName())) {
                        if (methodArgs == null || methodArgs.length == 0
                                || !(methodArgs[0] instanceof ClassFileTransformer)) {
                            throw new AssertionError("invalid transformer registration");
                        }
                        transformers.incrementAndGet();
                        return null;
                    }
                    Class<?> type = method.getReturnType();
                    if (type == boolean.class) return false;
                    if (type == long.class) return 0L;
                    if (type == int.class) return 0;
                    if (type == Class[].class) return new Class<?>[0];
                    return null;
                });

        Main.premain(null, instrumentation);
        require(transformers.get() == 1,
                "Pathfinding-only premain must register exactly one transformer");
        System.out.println("PREMAIN_PATHFINDING_ONLY PASS transformers=" + transformers.get());
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
