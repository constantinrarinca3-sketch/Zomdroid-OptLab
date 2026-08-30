import com.zomdroid.agent.Main;
import com.zomdroid.agent.optimization.FeatureCompatibility;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.asm.Advice;
import net.bytebuddy.asm.AsmVisitorWrapper;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.ClassFileLocator;
import net.bytebuddy.dynamic.scaffold.TypeValidation;
import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.pool.TypePool;
import net.bytebuddy.utility.OpenedClassReader;

import java.io.File;
import java.lang.reflect.Constructor;
import java.security.MessageDigest;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.returns;
import static net.bytebuddy.matcher.ElementMatchers.takesArguments;

/** Offline transform verification against the two exact Java-25 Build 42 jars. */
public final class Cp62ExactJarBytecodeUnit {
    private static final String JAR_4220 =
            "e4661ca9cb168abc995d3cf59994fa17f66ba8a4e2c2899cbfa48f7eacea54b8";
    private static final String JAR_42203 =
            "bda809fb49004a07dbfc560d059c0ee58d0643ab0f33b53351b13bd62f1d8227";

    private Cp62ExactJarBytecodeUnit() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "usage: <projectzomboid-42.20.jar> <42.20.3.jar>");
        System.setProperty("net.bytebuddy.experimental", "true");
        System.setProperty("zomdroid.optlab.pz.build.family", "42");
        System.setProperty("zomdroid.optlab.only.build42", "1");
        FeatureCompatibility.configureFromProperties();
        verify(new File(args[0]), "42.20", JAR_4220);
        verify(new File(args[1]), "42.20.3", JAR_42203);
        System.out.println("CP6_2_EXACT_JAR_BYTECODE PASS versions=42.20,42.20.3");
    }

    private static void verify(File jar, String version, String expectedJarHash) throws Exception {
        require(jar.isFile(), version + " jar missing");
        require(expectedJarHash.equals(sha256(java.nio.file.Files.readAllBytes(jar.toPath()))),
                version + " whole-jar SHA-256 mismatch");

        try (JarFile jarFile = new JarFile(jar, false);
             ClassFileLocator jarLocator = ClassFileLocator.ForJarFile.of(jar)) {
            ClassFileLocator locator = new ClassFileLocator.Compound(jarLocator,
                    ClassFileLocator.ForClassLoader.ofSystemLoader());
            TypePool pool = TypePool.Default.of(locator);

            byte[] defaultOriginal = bytes(jarFile, "zombie/core/DefaultShader.class");
            require(countCall(defaultOriginal, "setChunkDepth", "(F)V",
                    "zombie/core/opengl/ShaderProgram", "setValue",
                    "(Ljava/lang/String;F)V") == 1,
                    version + " DefaultShader vanilla shape");
            byte[] defaultPatched = new ByteBuddy().with(TypeValidation.DISABLED)
                    .redefine(pool.describe("zombie.core.DefaultShader").resolve(), locator)
                    .visit(Advice.to(Main.ChunkDepthAdvice.class).on(
                            named("setChunkDepth").and(takesArguments(float.class))
                                    .and(returns(void.class))))
                    .visit(Advice.to(Main.DefaultShaderCompileAdvice.class).on(
                            named("onCompileSuccess").and(takesArguments(1))
                                    .and(returns(void.class))))
                    .make().getBytes();
            require(countCall(defaultPatched, "setChunkDepth", "(F)V",
                    "com/zomdroid/agent/optimization/RenderOptimizationRuntime",
                    "handleChunkDepth", "(Ljava/lang/Object;F)Z") == 1,
                    version + " ChunkDepth Advice not injected exactly once");
            require(countCall(defaultPatched, "onCompileSuccess",
                    "(Lzombie/core/opengl/ShaderProgram;)V",
                    "com/zomdroid/agent/optimization/RenderOptimizationRuntime",
                    "afterDefaultShaderCompile", "()V") == 1,
                    version + " compile invalidation hook not injected exactly once");

            byte[] ringOriginal = bytes(jarFile,
                    "zombie/core/SpriteRenderer$RingBuffer.class");
            require(countCall(ringOriginal, "render", "()V",
                    "gnu/trove/map/hash/TObjectIntHashMap", "clear", "()V") == 1,
                    version + " RingBuffer.render vanilla clear shape");

            FeatureCompatibility.Decision decision = FeatureCompatibility.evaluate(
                    "RTHREAD_RING_RENDER_CLEAR", true, 1,
                    new FeatureCompatibility.Requirement("ring", "exact", "exact"));
            AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper wrapper =
                    productionRingVisitor(decision);
            TypeDescription ringType = pool.describe(
                    "zombie.core.SpriteRenderer$RingBuffer").resolve();
            byte[] ringPatched = new ByteBuddy().with(TypeValidation.DISABLED)
                    .redefine(ringType, locator)
                    .visit(new AsmVisitorWrapper.ForDeclaredMethods().method(
                            named("render").and(takesArguments(0)).and(returns(void.class)),
                            wrapper))
                    .make().getBytes();
            require(countCall(ringPatched, "render", "()V",
                    "com/zomdroid/agent/optimization/RenderOptimizationRuntime",
                    "clearModelDrawCountsIfNotEmpty",
                    "(Lgnu/trove/map/hash/TObjectIntHashMap;)V") == 1,
                    version + " Ring render helper not injected exactly once");
            require(countCall(ringPatched, "render", "()V",
                    "gnu/trove/map/hash/TObjectIntHashMap", "clear", "()V") == 0,
                    version + " original Ring render clear still present");

            require(defaultOriginal[6] == defaultPatched[6]
                            && defaultOriginal[7] == defaultPatched[7]
                            && ringOriginal[6] == ringPatched[6]
                            && ringOriginal[7] == ringPatched[7],
                    version + " class-file major version changed");
            System.out.println("CP6_2_EXACT_JAR " + version
                    + " PASS defaultShaderBytes=" + defaultPatched.length
                    + " ringBufferBytes=" + ringPatched.length);
        }
    }

    @SuppressWarnings("unchecked")
    private static AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper
            productionRingVisitor(FeatureCompatibility.Decision decision) throws Exception {
        Class<?> type = Class.forName("com.zomdroid.agent.Main$RingRenderClearVisitor");
        Constructor<?> constructor = type.getDeclaredConstructor(
                FeatureCompatibility.Decision.class);
        constructor.setAccessible(true);
        return (AsmVisitorWrapper.ForDeclaredMethods.MethodVisitorWrapper)
                constructor.newInstance(decision);
    }

    private static int countCall(byte[] bytes, String methodName, String methodDescriptor,
                                 String owner, String name, String descriptor) {
        int[] count = {0};
        OpenedClassReader.of(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String currentName,
                                                       String currentDescriptor, String signature,
                                                       String[] exceptions) {
                if (!methodName.equals(currentName)
                        || !methodDescriptor.equals(currentDescriptor)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String currentOwner,
                                                          String currentName,
                                                          String currentDescriptor,
                                                          boolean isInterface) {
                        if (owner.equals(currentOwner) && name.equals(currentName)
                                && descriptor.equals(currentDescriptor)) count[0]++;
                    }
                };
            }
        }, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static byte[] bytes(JarFile jar, String name) throws Exception {
        JarEntry entry = jar.getJarEntry(name);
        require(entry != null, name + " missing");
        try (java.io.InputStream input = jar.getInputStream(entry)) {
            return input.readAllBytes();
        }
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder output = new StringBuilder(64);
        for (byte value : hash) output.append(String.format("%02x", value & 0xff));
        return output.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
