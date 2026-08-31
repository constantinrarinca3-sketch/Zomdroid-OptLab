import com.zomdroid.agent.Main;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class ChunkOptimizationRealJarSmoke {
    private static final String[] TARGETS = {
            "zombie/iso/worldgen/zones/ZoneGenerator",
            "zombie/LoadGridsquarePerformanceWorkaround",
            "zombie/Lua/MapObjects",
            "zombie/iso/worldgen/WorldGenChunk",
            "zombie/iso/IsoChunk",
            "zombie/iso/IsoGridSquare"
    };

    private ChunkOptimizationRealJarSmoke() {}

    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("jar proof sha256");
        configure(args[1], args[2]);

        List<ClassFileTransformer> transformers = new ArrayList<>();
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                ChunkOptimizationRealJarSmoke.class.getClassLoader(),
                new Class<?>[]{Instrumentation.class},
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
                        ChunkOptimizationRealJarSmoke.class.getClassLoader(), target,
                        null, null, original);
                require(transformed != null && transformed.length > 0,
                        "target not transformed " + target);
                require(transformed[0] == (byte) 0xCA && transformed[1] == (byte) 0xFE,
                        "bad transformed class " + target);
            }
        }
        System.out.println("CHUNK_OPTIMIZATION_REAL_JAR_SMOKE PASS targets=" + TARGETS.length);
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
                "zomdroid.optlab.chunk.forage",
                "zomdroid.optlab.chunk.neighbour.worker",
                "zomdroid.optlab.chunk.neighbour.main",
                "zomdroid.optlab.chunk.grid.load",
                "zomdroid.optlab.chunk.vehicles",
                "zomdroid.optlab.chunk.randomized.buildings",
                "zomdroid.optlab.chunk.lua.mapobjects",
                "zomdroid.optlab.chunk.worldgen.biome",
                "zomdroid.optlab.chunk.cp2c.dirty.clear"
        };
        for (String flag : flags) System.setProperty(flag, "1");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
