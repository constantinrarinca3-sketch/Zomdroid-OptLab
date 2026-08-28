package com.zomdroid.patch;

import android.util.Log;

import com.zomdroid.AppStorage;
import com.zomdroid.ElfSymbols;
import com.zomdroid.NativeModulesPreferences;
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

/** Exact-identity selector for the official Build 42.20/42.20.3 ARM64 PopMan. */
public final class PopManNativeManager {
    private static final String LOG_TAG = "ZD-POPMAN-ARM64";
    private static final String SOURCE_NAME = "libPZPopManB4220.so";
    private static final String ACTIVE_NAME = "libPZPopMan64.so";
    private static final String BRIDGE_NAME = "libPZPopManSaveCellBridge.so";
    private static final String MARKER_NAME = ".zomdroid-popman-arm64-cp2";
    private static final String RUNTIME_FALLBACK_MARKER =
            ".zomdroid-popman-runtime-fallback";
    private static final long SOURCE_SIZE = 9_539_608L;
    private static final String SOURCE_SHA256 =
            "eec5ac6f8fb60964cbd9b49c1525ac521f52191484439651b48278f5b8b684af";

    private static final String POPMAN_CLASS =
            "zombie/popman/ZombiePopulationManager.class";
    private static final Set<String> SUPPORTED_CLASS_SHA256 = new HashSet<>(Arrays.asList(
            "126fbf46d4b0ee77c6b85a4781e270fa71bcb35d405596bd130f1c276302e8dc",
            "d78514c622b513e5c43b5f6ab2f523f8a6bd64ae4f6efdde16d590351624072e"
    ));

    private static final String BRIDGE_EXPORT =
            "Java_zombie_popman_ZombiePopulationManager_n_1saveCell";
    private static final Set<String> REQUIRED_PAYLOAD_JNI = new HashSet<>(Arrays.asList(
            "Java_zombie_popman_ZombiePopulationManager_n_1init",
            "Java_zombie_popman_ZombiePopulationManager_n_1config",
            "Java_zombie_popman_ZombiePopulationManager_n_1configFloat",
            "Java_zombie_popman_ZombiePopulationManager_n_1configInt",
            "Java_zombie_popman_ZombiePopulationManager_n_1setSpawnOrigins",
            "Java_zombie_popman_ZombiePopulationManager_n_1setOutfitNames",
            "Java_zombie_popman_ZombiePopulationManager_n_1updateMain",
            "Java_zombie_popman_ZombiePopulationManager_n_1hasDataForThread",
            "Java_zombie_popman_ZombiePopulationManager_n_1readyToPause",
            "Java_zombie_popman_ZombiePopulationManager_n_1updateThread",
            "Java_zombie_popman_ZombiePopulationManager_n_1shouldWait",
            "Java_zombie_popman_ZombiePopulationManager_n_1beginSaveRealZombies",
            "Java_zombie_popman_ZombiePopulationManager_n_1saveRealZombies",
            "Java_zombie_popman_ZombiePopulationManager_n_1save",
            "Java_zombie_popman_ZombiePopulationManager_n_1stop",
            "Java_zombie_popman_ZombiePopulationManager_n_1addZombie",
            "Java_zombie_popman_ZombiePopulationManager_n_1aggroTarget",
            "Java_zombie_popman_ZombiePopulationManager_n_1loadChunk",
            "Java_zombie_popman_ZombiePopulationManager_n_1loadedAreas",
            "Java_zombie_popman_ZombiePopulationManager_n_1realZombieCount",
            "Java_zombie_popman_ZombiePopulationManager_n_1spawnHorde",
            "Java_zombie_popman_ZombiePopulationManager_n_1worldSound",
            "Java_zombie_popman_ZombiePopulationManager_n_1getAddZombieCount",
            "Java_zombie_popman_ZombiePopulationManager_n_1getAddZombieData",
            "Java_zombie_popman_ZombiePopulationManager_n_1hasRadarData",
            "Java_zombie_popman_ZombiePopulationManager_n_1requestRadarData",
            "Java_zombie_popman_ZombiePopulationManager_n_1getRadarZombieData"
    ));

    /*
     * CP2-02 statically matched the complete Linux x86_64 ManagerWorker::saveCell lifecycle to
     * the official ARM64 DWARF layouts and internal functions. Runtime/device QA remains an
     * explicit final checkpoint, while all identity mismatches still fail closed to x86_64.
     */
    private static final boolean BRIDGE_SEMANTICS_VERIFIED = true;

    private PopManNativeManager() {}

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
            return "popManNativeApplicable=" + bit(applicable)
                    + " popManNativeRequested=" + bit(requested)
                    + " popManNativeAvailable=" + bit(available)
                    + " popManNativeActive=" + bit(active)
                    + " popManNativeSha256=" + activeSha256
                    + " popManNativeReason=" + reason;
        }

        public String proofLines(String session) {
            String prefix = "[ZD-OPT-PROOF] session=" + token(session) + " mechanism=";
            StringBuilder output = new StringBuilder(512);
            output.append(prefix).append("POPMAN_NATIVE_AVAILABLE state=")
                    .append(available ? "AVAILABLE" : "UNAVAILABLE")
                    .append(" detail=reason_").append(reason).append('\n');
            output.append(prefix).append("POPMAN_NATIVE_ACTIVE state=")
                    .append(active ? "ACTIVE" : (requested ? "FALLBACK" : "OFF"))
                    .append(" detail=sha256_").append(activeSha256).append('\n');
            if (requested && !active) {
                output.append(prefix).append("POPMAN_NATIVE_FALLBACK state=FALLBACK")
                        .append(" detail=original_x86_64_box64_reason_")
                        .append(reason).append('\n');
            }
            return output.toString();
        }

        public void addAgentProperties(java.util.List<String> jvmArgs) {
            jvmArgs.add("-Dzomdroid.native.popman.requested=" + bit(requested));
            jvmArgs.add("-Dzomdroid.native.popman.active=" + bit(active));
        }
    }

    public static Result apply(GameInstance gameInstance,
                               NativeModulesPreferences preferences) {
        boolean requested = preferences.isPopManEnabled();
        File jar = new File(gameInstance.getGamePath(), "projectzomboid.jar");
        if (!"42".equals(gameInstance.getBuildVersion()) || !jar.isFile()) {
            selectX86Fallback(gameInstance);
            return new Result(false, requested, false, false,
                    "BUILD_42_FAT_JAR_REQUIRED", "NOT_APPLICABLE");
        }

        File source = new File(AppStorage.requireSingleton().getLibraryPath(), SOURCE_NAME);
        File bridge = new File(AppStorage.requireSingleton().getLibraryPath(), BRIDGE_NAME);
        File nativeDirectory = new File(gameInstance.getGamePath(), "android/arm64-v8a");
        File active = new File(nativeDirectory, ACTIVE_NAME);
        boolean available = false;
        String reason = "AVAILABLE";
        try {
            validateClass(jar);
            validatePayload(source);
            validateBridge(bridge);
            available = true;
        } catch (Exception error) {
            reason = message(error);
            Log.e(LOG_TAG, "POPMAN_NATIVE_AVAILABLE=0 reason=" + reason, error);
        }

        if (!requested) {
            selectX86Fallback(gameInstance);
            return new Result(true, false, available, false,
                    "USER_DISABLED", hashOrState(active));
        }
        if (!available) {
            selectX86Fallback(gameInstance);
            return new Result(true, true, false, false, reason, hashOrState(active));
        }
        if (!BRIDGE_SEMANTICS_VERIFIED) {
            selectX86Fallback(gameInstance);
            return new Result(true, true, true, false,
                    "SAVE_CELL_BRIDGE_SEMANTICS_PENDING_CP2_02", hashOrState(active));
        }

        File runtimeFallback = new File(gameInstance.getHomePath(), RUNTIME_FALLBACK_MARKER);
        if (runtimeFallback.isFile()) {
            selectX86Fallback(gameInstance);
            if (!runtimeFallback.delete()) {
                Log.w(LOG_TAG, "Could not clear one-shot PopMan runtime fallback marker");
            }
            return new Result(true, true, true, false,
                    "PREVIOUS_RUNTIME_FAILURE_ONE_SAFE_X86_START", hashOrState(active));
        }

        try {
            ensureDirectory(nativeDirectory);
            if (!active.isFile() || !SOURCE_SHA256.equals(sha256(active))) {
                preserveForeignActive(active);
                copyVerified(source, active);
            }
            writeMarker(nativeDirectory);
            String activeHash = sha256(active);
            Log.i(LOG_TAG, "POPMAN_NATIVE_AVAILABLE=1 POPMAN_NATIVE_ACTIVE=1 hash="
                    + activeHash);
            return new Result(true, true, true, true,
                    "AUDITED_B42_20_ABI_WITH_FULL_SAVE_CELL_BRIDGE", activeHash);
        } catch (Exception error) {
            selectX86Fallback(gameInstance);
            String failure = message(error);
            Log.e(LOG_TAG, "POPMAN_NATIVE_FALLBACK=1 reason=" + failure, error);
            return new Result(true, true, true, false, failure, hashOrState(active));
        }
    }

    private static void validateClass(File jar) throws IOException {
        try (ZipFile zip = new ZipFile(jar)) {
            ZipEntry entry = zip.getEntry(POPMAN_CLASS);
            if (entry == null) throw new IOException("MISSING_CLASS_" + POPMAN_CLASS);
            try (InputStream input = zip.getInputStream(entry)) {
                String actual = sha256(input);
                if (!SUPPORTED_CLASS_SHA256.contains(actual)) {
                    throw new IOException("POPMAN_CLASS_SHA_MISMATCH_" + actual);
                }
            }
        }
    }

    private static void validatePayload(File source) throws IOException {
        if (!source.isFile()) throw new IOException("PACKAGED_POPMAN_LIBRARY_MISSING");
        if (source.length() != SOURCE_SIZE) {
            throw new IOException("POPMAN_LIBRARY_SIZE_" + source.length());
        }
        if (!SOURCE_SHA256.equals(sha256(source))) {
            throw new IOException("POPMAN_LIBRARY_SHA_MISMATCH");
        }
        validateElf64Arm(source, "POPMAN");
        Set<String> present = ElfSymbols.readExportedJniSymbols(source);
        if (present == null) throw new IOException("POPMAN_JNI_EXPORTS_UNREADABLE");
        Set<String> missing = new HashSet<>(REQUIRED_PAYLOAD_JNI);
        missing.removeAll(present);
        if (!missing.isEmpty()) {
            throw new IOException("POPMAN_JNI_MISSING_" + missing.iterator().next());
        }
        if (present.contains(BRIDGE_EXPORT)) {
            throw new IOException("POPMAN_UNEXPECTED_SAVE_CELL_EXPORT_IDENTITY_CHANGED");
        }
    }

    private static void validateBridge(File bridge) throws IOException {
        if (!bridge.isFile()) throw new IOException("PACKAGED_SAVE_CELL_BRIDGE_MISSING");
        validateElf64Arm(bridge, "POPMAN_BRIDGE");
        Set<String> present = ElfSymbols.readExportedJniSymbols(bridge);
        if (present == null || !present.contains(BRIDGE_EXPORT)) {
            throw new IOException("SAVE_CELL_BRIDGE_EXPORT_MISSING");
        }
    }

    private static void validateElf64Arm(File file, String label) throws IOException {
        byte[] header = new byte[20];
        try (InputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < header.length) {
                int read = input.read(header, offset, header.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset != header.length) throw new IOException(label + "_ELF_TRUNCATED");
        }
        int machine = (header[18] & 0xff) | ((header[19] & 0xff) << 8);
        if ((header[0] & 0xff) != 0x7f || header[1] != 'E' || header[2] != 'L'
                || header[3] != 'F' || header[4] != 2 || header[5] != 1 || machine != 183) {
            throw new IOException(label + "_NOT_ELF64_AARCH64");
        }
    }

    private static void selectX86Fallback(GameInstance gameInstance) {
        File active = new File(gameInstance.getGamePath(),
                "android/arm64-v8a/" + ACTIVE_NAME);
        if (!active.isFile()) return;
        File disabled = new File(active.getParentFile(), ACTIVE_NAME + ".disabled");
        try {
            Files.move(active.toPath(), disabled.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException error) {
            throw new IllegalStateException("POPMAN_X86_FALLBACK_SELECT_FAILED", error);
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("POPMAN_NATIVE_DIRECTORY_CREATE_FAILED");
        }
    }

    private static void preserveForeignActive(File active) throws IOException {
        if (active.isFile()) DistinctFileBackup.preserve(active, ".zomdroid-popman-original");
    }

    private static void copyVerified(File source, File target) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".cp2-tmp");
        Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (!SOURCE_SHA256.equals(sha256(temporary))) {
            throw new IOException("POPMAN_POST_COPY_SHA_MISMATCH");
        }
        //noinspection ResultOfMethodCallIgnored
        temporary.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        temporary.setExecutable(true, false);
        moveReplacing(temporary, target);
    }

    private static void writeMarker(File nativeDirectory) throws IOException {
        String marker = "mode=ARM64_POPMAN_B42_CP2\n"
                + "library_sha256=" + SOURCE_SHA256 + "\n"
                + "java_abi=42.20_AND_42.20.3_VERIFIED\n"
                + "save_cell_bridge=CP2_02_FULL_SEMANTICS_EXACT_BUILD_ID\n";
        File target = new File(nativeDirectory, MARKER_NAME);
        File temporary = new File(nativeDirectory, MARKER_NAME + ".tmp");
        Files.write(temporary.toPath(),
                marker.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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

    private static String sha256(InputStream input) {
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
        } catch (IOException error) {
            throw new IllegalStateException(error);
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

    private static String message(Exception error) {
        return error.getMessage() == null ? error.getClass().getSimpleName()
                : error.getMessage();
    }

    private static String token(String value) {
        if (value == null || value.trim().isEmpty()) return "UNKNOWN";
        return value.trim().replace('\n', '_').replace('\r', '_').replace(' ', '_');
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }
}
