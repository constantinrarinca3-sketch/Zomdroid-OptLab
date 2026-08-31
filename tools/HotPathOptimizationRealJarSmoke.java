import com.zomdroid.agent.Main;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Runs the production unified AgentBuilder transformer against exact Java-25 B42 classes. */
public final class HotPathOptimizationRealJarSmoke {
    private static final String[] TARGETS = {
            "zombie/core/opengl/ShaderPrograms",
            "zombie/core/skinnedmodel/model/VertexBufferObject",
            "zombie/core/SpriteRenderer$RingBuffer",
            "zombie/core/SpriteRenderer",
            "zombie/core/textures/Texture",
            "zombie/GameProfiler",
            "zombie/core/profiling/AbstractPerformanceProfileProbe"
    };

    private HotPathOptimizationRealJarSmoke() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("jar proof sha256");
        configure(args[1], args[2]);

        List<ClassFileTransformer> transformers = new ArrayList<>();
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                HotPathOptimizationRealJarSmoke.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, methodArgs) -> {
                    if ("addTransformer".equals(method.getName())) {
                        transformers.add((ClassFileTransformer) methodArgs[0]);
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
        require(transformers.size() == 1, "expected one unified transformer");
        ClassFileTransformer transformer = transformers.get(0);

        try (JarFile jar = new JarFile(args[0], false)) {
            for (String target : TARGETS) {
                JarEntry entry = jar.getJarEntry(target + ".class");
                require(entry != null, "missing " + target);
                byte[] original;
                try (InputStream input = jar.getInputStream(entry)) {
                    original = input.readAllBytes();
                }
                byte[] transformed = transformer.transform(
                        HotPathOptimizationRealJarSmoke.class.getClassLoader(), target,
                        null, null, original);
                require(transformed != null && transformed.length > 0,
                        "target not transformed " + target);
                require(original[6] == transformed[6] && original[7] == transformed[7],
                        "class major changed " + target);
            }
        }
        System.out.println("CP6_6_HOTPATH_REAL_JAR_SMOKE PASS targets=" + TARGETS.length);
    }

    private static void configure(String proof, String sha256) {
        System.setProperty("zomdroid.renderer", "ZINK");
        System.setProperty("zomdroid.optlab.pz.build.family", "42");
        System.setProperty("zomdroid.optlab.pz.version.hint", "42.20+");
        System.setProperty("zomdroid.optlab.jar.sha256", sha256);
        System.setProperty("zomdroid.optlab.jar.known", "1");
        System.setProperty("zomdroid.optlab.only.build42", "1");
        System.setProperty("zomdroid.optlab.proof.path", proof);
        String[] flags = {
                "zomdroid.optlab.render.shader.lookup",
                "zomdroid.optlab.render.mvp",
                "zomdroid.optlab.render.staterun.texture",
                "zomdroid.optlab.render.texture.bind",
                "zomdroid.optlab.render.profiler.idle",
                "zomdroid.optlab.render.probe.style.idle",
                "zomdroid.optlab.render.build.loop",
                "zomdroid.optlab.render.probe.extended.idle"
        };
        for (String flag : flags) System.setProperty(flag, "1");
    }

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
}
