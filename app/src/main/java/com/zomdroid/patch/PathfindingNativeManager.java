package com.zomdroid.patch;

import android.util.Log;

import com.zomdroid.AppStorage;
import com.zomdroid.ElfSymbols;
import com.zomdroid.NativeModulesPreferences;
import com.zomdroid.OptLabLaunchContract;
import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** Identity-gated installer and selector for PZ's official Build 42 ARM64 pathfinder. */
public final class PathfindingNativeManager {
    private static final String LOG_TAG = "ZD-PATHFIND-ARM64";
    private static final String SOURCE_NAME = "libPZPathFindB4220.so";
    private static final String ACTIVE_NAME = "libPZPathFind64.so";
    private static final String MARKER_NAME = ".zomdroid-pathfinding-arm64-cp1";
    private static final String RUNTIME_FALLBACK_MARKER =
            ".zomdroid-pathfinding-runtime-fallback";
    private static final long SOURCE_SIZE = 11_995_392L;
    private static final String SOURCE_SHA256 =
            "a6719506323898dd79277f08869e9c803886717d92308e531dc34720a581b2dc";

    private static final String PATHFIND_NATIVE_CLASS =
            "zombie/pathfind/nativeCode/PathfindNative.class";
    private static final String PATHFIND_NATIVE_CLASS_SHA256 =
            "7fbc06c9757083c318dcc136cfcb31c2ad4941d15926c9de6f7792dcef3d6022";
    private static final String PATHFIND_RENDERER_CLASS =
            "zombie/pathfind/nativeCode/PathfindNativeRenderer.class";
    private static final String PATHFIND_RENDERER_CLASS_SHA256 =
            "070c67fcaa68c63f20a6d7d52967e334f38c5b1bfb9b3f7f09e3fc0383cc8c08";
    private static final String PATHFIND_THREAD_CLASS =
            "zombie/pathfind/nativeCode/PathfindNativeThread.class";
    private static final String PATHFIND_THREAD_CLASS_SHA256 =
            "bbf51396746b4047a9f5c3a53d484d61b5a05a6a5eb3c6286dcf90864bc21d20";

    private static final Set<String> REQUIRED_JNI = new HashSet<>(Arrays.asList(
            "Java_zombie_pathfind_nativeCode_PathfindNative_initWorld",
            "Java_zombie_pathfind_nativeCode_PathfindNative_destroyWorld",
            "Java_zombie_pathfind_nativeCode_PathfindNative_freeMemoryAtExit",
            "Java_zombie_pathfind_nativeCode_PathfindNative_update",
            "Java_zombie_pathfind_nativeCode_PathfindNative_updateChunk",
            "Java_zombie_pathfind_nativeCode_PathfindNative_removeChunk",
            "Java_zombie_pathfind_nativeCode_PathfindNative_updateSquare",
            "Java_zombie_pathfind_nativeCode_PathfindNative_addVehicle",
            "Java_zombie_pathfind_nativeCode_PathfindNative_removeVehicle",
            "Java_zombie_pathfind_nativeCode_PathfindNative_teleportVehicle",
            "Java_zombie_pathfind_nativeCode_PathfindNative_findPath",
            "Java_zombie_pathfind_nativeCode_PathfindNativeRenderer_renderNative",
            "Java_zombie_pathfind_nativeCode_PathfindNativeRenderer_setDebugOption"
    ));

    private PathfindingNativeManager() {}

    public static final class Result {
        public final boolean applicable;
        public final boolean requested;
        public final boolean available;
        public final boolean active;
        public final String reason;
        public final String activeSha256;

        private Result(boolean applicable, boolean requested, boolean available,
                       boolean active, String reason, String activeSha256) {
            this.applicable = applicable;
            this.requested = requested;
            this.available = available;
            this.active = active;
            this.reason = token(reason);
            this.activeSha256 = token(activeSha256);
        }

        public String machineReadable() {
            return "pathfindingNativeApplicable=" + bit(applicable)
                    + " pathfindingNativeRequested=" + bit(requested)
                    + " pathfindingNativeAvailable=" + bit(available)
                    + " pathfindingNativeActive=" + bit(active)
                    + " pathfindingNativeSha256=" + activeSha256
                    + " pathfindingNativeReason=" + reason;
        }

        public String proofLines(String session) {
            String prefix = "[ZD-OPT-PROOF] session=" + token(session) + " mechanism=";
            StringBuilder output = new StringBuilder(512);
            output.append(prefix).append("PATHFINDING_NATIVE_AVAILABLE state=")
                    .append(available ? "AVAILABLE" : "UNAVAILABLE")
                    .append(" detail=reason_").append(reason).append('\n');
            output.append(prefix).append("PATHFINDING_NATIVE_ACTIVE state=")
                    .append(active ? "ACTIVE" : (requested ? "FALLBACK" : "OFF"))
                    .append(" detail=sha256_").append(activeSha256).append('\n');
            if (requested && !active) {
                output.append(prefix).append("PATHFINDING_NATIVE_FALLBACK state=FALLBACK")
                        .append(" detail=original_java_polygonalmap2_reason_")
                        .append(reason).append('\n');
            }
            return output.toString();
        }

        public void addAgentProperties(java.util.List<String> jvmArgs) {
            OptLabLaunchContract.putProperty(jvmArgs,
                    "zomdroid.native.pathfinding.requested",
                    Integer.toString(bit(requested)));
            OptLabLaunchContract.putProperty(jvmArgs,
                    "zomdroid.native.pathfinding.active", Integer.toString(bit(active)));
        }
    }

    public static Result apply(GameInstance gameInstance,
                               NativeModulesPreferences preferences) {
        boolean requested = preferences.isPathfindingEnabled();
        File jar = new File(gameInstance.getGamePath(), "projectzomboid.jar");
        if (!"42".equals(gameInstance.getBuildVersion()) || !jar.isFile()) {
            return new Result(false, requested, false, false,
                    "BUILD_42_FAT_JAR_REQUIRED", "NOT_APPLICABLE");
        }

        File source = new File(AppStorage.requireSingleton().getLibraryPath(), SOURCE_NAME);
        File nativeDirectory = new File(gameInstance.getGamePath(), "android/arm64-v8a");
        File active = new File(nativeDirectory, ACTIVE_NAME);
        boolean available = false;
        String reason = "AVAILABLE";
        try {
            validateClasses(jar);
            validatePayload(source);
            available = true;
        } catch (Exception error) {
            reason = error.getMessage() == null ? error.getClass().getSimpleName()
                    : error.getMessage();
            Log.e(LOG_TAG, "PATHFINDING_NATIVE_AVAILABLE=0 reason=" + reason, error);
        }

        if (!requested) {
            boolean selected = PathfindingWorkaround.selectPathfinder(gameInstance, false);
            return new Result(true, false, available, false,
                    selected ? "USER_DISABLED" : "JAVA_FALLBACK_WRITE_FAILED", hashOrState(active));
        }

        if (!available) {
            PathfindingWorkaround.selectPathfinder(gameInstance, false);
            return new Result(true, true, false, false, reason, hashOrState(active));
        }

        File runtimeFallback = new File(gameInstance.getHomePath(), RUNTIME_FALLBACK_MARKER);
        if (runtimeFallback.isFile()) {
            PathfindingWorkaround.selectPathfinder(gameInstance, false);
            if (!runtimeFallback.delete()) {
                Log.w(LOG_TAG, "Could not clear one-shot runtime fallback marker");
            }
            return new Result(true, true, true, false,
                    "PREVIOUS_RUNTIME_FAILURE_ONE_SAFE_JAVA_START", hashOrState(active));
        }

        try {
            ensureDirectory(nativeDirectory);
            if (!active.isFile() || !SOURCE_SHA256.equals(sha256(active))) {
                preserveForeignActive(active);
                copyVerified(source, active);
            }
            writeMarker(nativeDirectory);
            if (!PathfindingWorkaround.selectPathfinder(gameInstance, true)) {
                throw new IOException("NATIVE_OPTION_WRITE_FAILED");
            }
            String activeHash = sha256(active);
            Log.i(LOG_TAG, "PATHFINDING_NATIVE_AVAILABLE=1 PATHFINDING_NATIVE_ACTIVE=1 hash="
                    + activeHash);
            return new Result(true, true, true, true, "AUDITED_B42_ABI", activeHash);
        } catch (Exception error) {
            PathfindingWorkaround.selectPathfinder(gameInstance, false);
            String failure = error.getMessage() == null ? error.getClass().getSimpleName()
                    : error.getMessage();
            Log.e(LOG_TAG, "PATHFINDING_NATIVE_FALLBACK=1 reason=" + failure, error);
            return new Result(true, true, true, false, failure, hashOrState(active));
        }
    }

    private static void validateClasses(File jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            requireHash(zip, PATHFIND_NATIVE_CLASS, PATHFIND_NATIVE_CLASS_SHA256);
            requireHash(zip, PATHFIND_RENDERER_CLASS, PATHFIND_RENDERER_CLASS_SHA256);
            requireHash(zip, PATHFIND_THREAD_CLASS, PATHFIND_THREAD_CLASS_SHA256);
        }
    }

    private static void requireHash(ZipFile zip, String name, String expected) throws IOException {
        ZipEntry entry = zip.getEntry(name);
        if (entry == null) throw new IOException("MISSING_CLASS_" + name);
        try (InputStream input = zip.getInputStream(entry)) {
            String actual = sha256(input);
            if (!expected.equals(actual)) {
                throw new IOException("CLASS_SHA_MISMATCH_" + name + "_" + actual);
            }
        }
    }

    private static void validatePayload(File source) throws IOException {
        if (!source.isFile()) throw new IOException("PACKAGED_PATHFIND_LIBRARY_MISSING");
        if (source.length() != SOURCE_SIZE) {
            throw new IOException("PATHFIND_LIBRARY_SIZE_" + source.length());
        }
        if (!SOURCE_SHA256.equals(sha256(source))) {
            throw new IOException("PATHFIND_LIBRARY_SHA_MISMATCH");
        }
        validateElf64Arm(source);
        Set<String> present = ElfSymbols.readExportedJniSymbols(source);
        if (present == null) throw new IOException("PATHFIND_JNI_EXPORTS_UNREADABLE");
        Set<String> missing = new HashSet<>(REQUIRED_JNI);
        missing.removeAll(present);
        if (!missing.isEmpty()) {
            throw new IOException("PATHFIND_JNI_MISSING_" + missing.iterator().next());
        }
    }

    private static void validateElf64Arm(File file) throws IOException {
        byte[] header = new byte[20];
        try (InputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < header.length) {
                int read = input.read(header, offset, header.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset != header.length) throw new IOException("PATHFIND_ELF_TRUNCATED");
        }
        int machine = (header[18] & 0xff) | ((header[19] & 0xff) << 8);
        if ((header[0] & 0xff) != 0x7f || header[1] != 'E' || header[2] != 'L'
                || header[3] != 'F' || header[4] != 2 || header[5] != 1 || machine != 183) {
            throw new IOException("PATHFIND_NOT_ELF64_AARCH64");
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("PATHFIND_NATIVE_DIRECTORY_CREATE_FAILED");
        }
    }

    private static void preserveForeignActive(File active) throws IOException {
        if (!active.isFile()) return;
        DistinctFileBackup.preserve(active, ".zomdroid-pathfinding-original");
    }

    private static void copyVerified(File source, File target) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".cp1-tmp");
        Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (!SOURCE_SHA256.equals(sha256(temporary))) {
            throw new IOException("PATHFIND_POST_COPY_SHA_MISMATCH");
        }
        //noinspection ResultOfMethodCallIgnored
        temporary.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        temporary.setExecutable(true, false);
        moveReplacing(temporary, target);
    }

    private static void writeMarker(File nativeDirectory) throws IOException {
        String marker = "mode=ARM64_PATHFIND_B42_CP1\n"
                + "library_sha256=" + SOURCE_SHA256 + "\n"
                + "jni_exports=13_VERIFIED\n"
                + "java_abi=42.20_AND_42.20.3_VERIFIED\n";
        File target = new File(nativeDirectory, MARKER_NAME);
        File temporary = new File(nativeDirectory, MARKER_NAME + ".tmp");
        Files.write(temporary.toPath(), marker.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        moveReplacing(temporary, target);
    }

    private static void moveReplacing(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(File file) throws IOException {
        try (InputStream input = new FileInputStream(file)) {
            return sha256(input);
        }
    }

    private static String sha256(InputStream input) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[128 * 1024];
            int count;
            while ((count = input.read(buffer)) >= 0) {
                if (count > 0) digest.update(buffer, 0, count);
            }
            StringBuilder output = new StringBuilder(64);
            for (byte value : digest.digest()) {
                output.append(String.format(Locale.ROOT, "%02x", value & 0xff));
            }
            return output.toString();
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
    }

    private static String hashOrState(File file) {
        if (!file.isFile()) return "MISSING";
        try {
            return sha256(file);
        } catch (IOException error) {
            return "ERROR";
        }
    }

    private static String token(String value) {
        if (value == null || value.trim().isEmpty()) return "UNKNOWN";
        return value.trim().replace('\n', '_').replace('\r', '_').replace(' ', '_');
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }
}
