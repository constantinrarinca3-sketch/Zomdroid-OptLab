import com.zomdroid.agent.Main;

import net.bytebuddy.jar.asm.ClassReader;
import net.bytebuddy.jar.asm.ClassVisitor;
import net.bytebuddy.jar.asm.MethodVisitor;
import net.bytebuddy.jar.asm.Opcodes;
import net.bytebuddy.utility.OpenedClassReader;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Exact Java-25 bytecode gate for the integrated CP6.13.4 buildDrawBuffer Advice. */
public final class Cp6134BuildDirectExactJarUnit {
    private static final String JAR_4220 =
            "e4661ca9cb168abc995d3cf59994fa17f66ba8a4e2c2899cbfa48f7eacea54b8";
    private static final String JAR_42203 =
            "bda809fb49004a07dbfc560d059c0ee58d0643ab0f33b53351b13bd62f1d8227";
    private static final String CLASS = "zombie/core/SpriteRenderer";
    private static final String METHOD = "buildDrawBuffer";
    private static final String METHOD_DESCRIPTOR =
            "([Lzombie/core/textures/TextureDraw;[Lzombie/core/Styles/Style;I)V";

    private Cp6134BuildDirectExactJarUnit() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "usage: <projectzomboid.jar> <42.20|42.20.3>");
        System.setProperty("net.bytebuddy.experimental", "true");
        String expected = "42.20".equals(args[1]) ? JAR_4220
                : "42.20.3".equals(args[1]) ? JAR_42203 : "";
        require(!expected.isEmpty(), "unsupported version label " + args[1]);
        verify(Path.of(args[0]), args[1], expected);
    }

    private static void verify(Path jarPath, String version, String expectedHash) throws Exception {
        require(Files.isRegularFile(jarPath), version + " jar missing");
        require(expectedHash.equals(sha256(Files.readAllBytes(jarPath))),
                version + " whole-jar SHA-256 mismatch");
        Path proof = Files.createTempFile("cp6134-" + version.replace('.', '-'), ".log");
        configure(version, expectedHash, proof);

        List<ClassFileTransformer> transformers = new ArrayList<>();
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                Cp6134BuildDirectExactJarUnit.class.getClassLoader(),
                new Class<?>[] {Instrumentation.class},
                (proxy, method, methodArgs) -> {
                    if ("addTransformer".equals(method.getName())) {
                        transformers.add((ClassFileTransformer) methodArgs[0]);
                        return null;
                    }
                    Class<?> result = method.getReturnType();
                    if (result == boolean.class) return false;
                    if (result == long.class) return 0L;
                    if (result == int.class) return 0;
                    if (result == Class[].class) return new Class<?>[0];
                    return null;
                });
        Main.premain(null, instrumentation);
        require(transformers.size() == 1, version + " unified transformer count");

        byte[] original;
        try (JarFile jar = new JarFile(jarPath.toFile(), false)) {
            JarEntry entry = jar.getJarEntry(CLASS + ".class");
            require(entry != null, version + " SpriteRenderer missing");
            try (InputStream input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        require(countCall(original, "zombie/core/SpriteRenderer$RingBuffer", "add",
                "(Lzombie/core/textures/TextureDraw;Lzombie/core/textures/TextureDraw;"
                        + "Lzombie/core/Styles/Style;)V") == 1,
                version + " vanilla buildDrawBuffer shape");

        byte[] transformed = transformers.get(0).transform(
                Cp6134BuildDirectExactJarUnit.class.getClassLoader(), CLASS,
                null, null, original);
        require(transformed != null && transformed.length > original.length,
                version + " SpriteRenderer not transformed");
        require(original[6] == transformed[6] && original[7] == transformed[7],
                version + " class-file major changed");
        require(countCall(transformed, "zombie/core/ZDOptBuildFast", "tryBuild",
                "(Ljava/lang/Object;Ljava/lang/Object;I)Z") == 1,
                version + " direct helper not injected exactly once");

        String proofText = Files.readString(proof);
        require(proofText.contains("mechanism=rthread_build_loop state=active"),
                version + " build-loop mechanism not active");
        require(proofText.contains("sprite_renderer_build_draw_buffer_cp6134_direct"),
                version + " CP6.13.4 structural proof missing");
        Files.deleteIfExists(proof);
        System.out.println("CP6134_BUILD_DIRECT_EXACT_JAR " + version
                + " PASS bytes=" + transformed.length + " helper_calls=1");
    }

    private static int countCall(byte[] bytes, String owner, String name, String descriptor) {
        int[] count = {0};
        OpenedClassReader.of(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String currentName,
                                                       String currentDescriptor, String signature,
                                                       String[] exceptions) {
                if (!METHOD.equals(currentName)
                        || !METHOD_DESCRIPTOR.equals(currentDescriptor)) return null;
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

    private static void configure(String version, String hash, Path proof) {
        System.setProperty("zomdroid.renderer", "ZINK");
        System.setProperty("zomdroid.optlab.pz.build.family", "42");
        System.setProperty("zomdroid.optlab.pz.version.hint", version);
        System.setProperty("zomdroid.optlab.jar.sha256", hash);
        System.setProperty("zomdroid.optlab.jar.known", "1");
        System.setProperty("zomdroid.optlab.only.build42", "1");
        System.setProperty("zomdroid.optlab.proof.path", proof.toString());
        System.setProperty("zomdroid.optlab.session", "cp6134-" + version);
        System.setProperty("zomdroid.optlab.render.build.loop", "1");
    }

    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest) result.append(String.format("%02x", value & 0xff));
        return result.toString();
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
