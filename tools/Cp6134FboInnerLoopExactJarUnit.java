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

/** Exact-jar Byte Buddy gate for the CP6.13.4 FBO inner-loop Advice. */
public final class Cp6134FboInnerLoopExactJarUnit {
    private static final String JAR_4220 =
            "e4661ca9cb168abc995d3cf59994fa17f66ba8a4e2c2899cbfa48f7eacea54b8";
    private static final String JAR_42203 =
            "bda809fb49004a07dbfc560d059c0ee58d0643ab0f33b53351b13bd62f1d8227";
    private static final String CLASS = "zombie/iso/fboRenderChunk/FBORenderCell";
    private static final String METHOD = "prepareChunkForUpdating";
    private static final String DESCRIPTOR = "(ILzombie/iso/IsoChunk;I)V";

    private Cp6134FboInnerLoopExactJarUnit() {}

    public static void main(String[] args) throws Exception {
        require(args.length == 2, "usage: <projectzomboid.jar> <42.20|42.20.3>");
        System.setProperty("net.bytebuddy.experimental", "true");
        String expected = "42.20".equals(args[1]) ? JAR_4220
                : "42.20.3".equals(args[1]) ? JAR_42203 : "";
        require(!expected.isEmpty(), "unsupported version " + args[1]);
        verify(Path.of(args[0]), args[1], expected);
    }

    private static void verify(Path jarPath, String version, String expectedHash)
            throws Exception {
        require(Files.isRegularFile(jarPath), version + " jar missing");
        require(expectedHash.equals(sha256(Files.readAllBytes(jarPath))),
                version + " whole-jar SHA-256 mismatch");
        Path proof = Files.createTempFile("cp6134-fbo-" + version.replace('.', '-'), ".log");
        configure(version, expectedHash, proof);

        List<ClassFileTransformer> transformers = new ArrayList<>();
        Instrumentation instrumentation = (Instrumentation) Proxy.newProxyInstance(
                Cp6134FboInnerLoopExactJarUnit.class.getClassLoader(),
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
            require(entry != null, version + " FBORenderCell missing");
            try (InputStream input = jar.getInputStream(entry)) {
                original = input.readAllBytes();
            }
        }
        require(countMethod(original) == 1, version + " vanilla target shape");
        require(countHelperCall(original) == 0, version + " vanilla already patched");

        byte[] transformed = transformers.get(0).transform(
                Cp6134FboInnerLoopExactJarUnit.class.getClassLoader(), CLASS,
                null, null, original);
        require(transformed != null && transformed.length > original.length,
                version + " FBORenderCell not transformed");
        require(original[6] == transformed[6] && original[7] == transformed[7],
                version + " class-file major changed");
        require(countHelperCall(transformed) == 1,
                version + " FBO helper not injected exactly once");

        String proofText = Files.readString(proof);
        require(proofText.contains("mechanism=fbo_inner_loop state=active"),
                version + " FBO mechanism not active");
        require(proofText.contains("fbo_render_cell_prepare_chunk_cp6134_direct"),
                version + " structural proof missing");
        Files.deleteIfExists(proof);
        System.out.println("CP6134_FBO_INNER_LOOP_EXACT_JAR " + version
                + " PASS bytes=" + transformed.length + " helper_calls=1");
    }

    private static int countMethod(byte[] bytes) {
        int[] count = {0};
        OpenedClassReader.of(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                if (METHOD.equals(name) && DESCRIPTOR.equals(descriptor)) count[0]++;
                return null;
            }
        }, ClassReader.SKIP_CODE | ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
        return count[0];
    }

    private static int countHelperCall(byte[] bytes) {
        int[] count = {0};
        OpenedClassReader.of(bytes).accept(new ClassVisitor(Opcodes.ASM9) {
            @Override public MethodVisitor visitMethod(int access, String name, String descriptor,
                                                       String signature, String[] exceptions) {
                if (!METHOD.equals(name) || !DESCRIPTOR.equals(descriptor)) return null;
                return new MethodVisitor(Opcodes.ASM9) {
                    @Override public void visitMethodInsn(int opcode, String owner, String name,
                                                          String descriptor,
                                                          boolean isInterface) {
                        if ("zombie/iso/fboRenderChunk/ZDOptFboPrepareFast".equals(owner)
                                && "tryPrepare".equals(name)
                                && "(Ljava/lang/Object;ILjava/lang/Object;I)Z"
                                .equals(descriptor)) count[0]++;
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
        System.setProperty("zomdroid.optlab.session", "cp6134-fbo-" + version);
        System.setProperty("zomdroid.optlab.fbo.inner.loop", "1");
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
