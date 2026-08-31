import com.zomdroid.agent.Main;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.pool.TypePool;

import java.io.File;

import static net.bytebuddy.matcher.ElementMatchers.hasDescriptor;
import static net.bytebuddy.matcher.ElementMatchers.named;

/** Offline Byte Buddy transform of the real Java 25 PZ classes; no class loading is required. */
public final class OptLabPathfindingBytecodeSmoke {
    private static final String NATIVE = "zombie.pathfind.nativeCode.PathfindNative";
    private static final String THREAD = "zombie.pathfind.nativeCode.PathfindNativeThread";
    private static final String FIND_PATH_DESCRIPTOR =
            "(Lzombie/pathfind/nativeCode/PathFindRequest;Ljava/nio/ByteBuffer;Z)I";
    private static final String RUN_INNER_DESCRIPTOR = "()V";

    private OptLabPathfindingBytecodeSmoke() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("projectzomboid.jar required");
        File jar = new File(args[0]);
        if (!jar.isFile()) throw new IllegalArgumentException("missing jar: " + jar);

        try (ClassFileLocator locator = new ClassFileLocator.Compound(
                ClassFileLocator.ForJarFile.of(jar),
                ClassFileLocator.ForClassLoader.ofSystemLoader())) {
            TypePool pool = TypePool.Default.of(locator);
            transform(pool, locator, NATIVE, "findPath", FIND_PATH_DESCRIPTOR,
                    Main.PathfindingRequestAdvice.class);
            transform(pool, locator, THREAD, "runInner", RUN_INNER_DESCRIPTOR,
                    Main.PathfindingThreadAdvice.class);
        }
        System.out.println("PATHFINDING_REAL_BYTECODE_TRANSFORM PASS jar=" + jar.getName());
    }

    private static void transform(TypePool pool, ClassFileLocator locator, String className,
                                  String methodName, String descriptor,
                                  Class<?> adviceClass) {
        TypeDescription type = pool.describe(className).resolve();
        int matches = type.getDeclaredMethods()
                .filter(named(methodName).and(hasDescriptor(descriptor))).size();
        if (matches != 1) {
            throw new AssertionError(className + " exact wrapper count=" + matches);
        }

        DynamicType.Unloaded<?> transformed = new ByteBuddy()
                .redefine(type, locator)
                .visit(Advice.to(adviceClass).on(named(methodName).and(hasDescriptor(descriptor))))
                .make();
        byte[] output = transformed.getBytes();
        if (output.length == 0) throw new AssertionError("empty transform for " + className);
        System.out.println("TRANSFORMED class=" + className + " bytes=" + output.length);
    }
}
