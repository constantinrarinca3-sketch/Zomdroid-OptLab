package com.zomdroid.patch;

import android.system.Os;
import android.system.OsConstants;
import android.util.Log;

import com.zomdroid.AppStorage;
import com.zomdroid.game.GameInstance;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/**
 * Restores the device-tested AB3 ARM64 native path before the game starts.
 *
 * <p>The Build 42 Lighting library bundled with the game is an older ABI. AB3 solved that by
 * installing a complete JNI shim as {@code libLighting64.so}; the shim owns the audited legacy
 * ARM64 engine stored beside it as {@code libLightingLegacy64.so}. AB3 also installs the audited
 * ARM64 PZClipper. OPT-LAB R1 accidentally dropped both pieces and disabled Lighting, forcing the
 * x86_64 implementation through Box64. That is not the AB3/P1 baseline and breaks MobileGL on the
 * tested device.</p>
 *
 * <p>Every payload is identity-gated and copied atomically. A foreign active library is preserved
 * before replacement. Failure removes only a payload that is byte-identical to ours.</p>
 */
public final class LightingArm64AbManager {
    private static final String LIGHT_LOG_TAG = "ZD-LIGHT-ARM64";
    private static final String CLIPPER_LOG_TAG = "ZD-PZCLIPPER-ARM64";

    private static final String LIGHTING_SHIM_NAME = "libLightingShim64.so";
    private static final String LIGHTING_LEGACY_NAME = "libLightingLegacy64.so";
    private static final String LIGHTING_ACTIVE_NAME = "libLighting64.so";
    private static final String LIGHTING_MARKER_NAME = ".zomdroid-lighting-arm64-r2";
    private static final long LIGHTING_LEGACY_SIZE = 3_974_984L;
    private static final String LIGHTING_LEGACY_SHA256 =
            "3b180959fa1405867d006bc227a13d337991c578c092e8f8ddaea265651a6551";

    private static final String CLIPPER_SOURCE_NAME = "libPZClipperLegacy64.so";
    private static final String CLIPPER_ACTIVE_NAME = "libPZClipper64.so";
    private static final String CLIPPER_MARKER_NAME = ".zomdroid-pzclipper-arm64-r2";
    private static final long CLIPPER_SIZE = 7_740_424L;
    private static final String CLIPPER_SHA256 =
            "4dc431f94d3f13dce9cf725115e2d77a40681d6cdfb64b84aadc7e073a5acc50";

    private static final long REQUIRED_PAGE_SIZE = 4096L;

    private LightingArm64AbManager() {}

    public static final class InstallResult {
        public final boolean applicable;
        public final boolean lightingActive;
        public final boolean clipperActive;
        public final String lightingSha256;
        public final String clipperSha256;

        private InstallResult(boolean applicable, boolean lightingActive, boolean clipperActive,
                              String lightingSha256, String clipperSha256) {
            this.applicable = applicable;
            this.lightingActive = lightingActive;
            this.clipperActive = clipperActive;
            this.lightingSha256 = lightingSha256;
            this.clipperSha256 = clipperSha256;
        }

        public boolean isComplete() {
            return !applicable || (lightingActive && clipperActive);
        }

        public String machineReadable() {
            return "arm64NativeApplicable=" + bit(applicable)
                    + " arm64Lighting=" + bit(lightingActive)
                    + " arm64LightingSha256=" + lightingSha256
                    + " arm64PzClipper=" + bit(clipperActive)
                    + " arm64PzClipperSha256=" + clipperSha256;
        }
    }

    /** Installs the exact AB3 Lighting and PZClipper path for Build 42. */
    public static InstallResult installRequired(GameInstance gameInstance) {
        if (!"42".equals(gameInstance.getBuildVersion())) {
            return new InstallResult(false, false, false, "NOT_APPLICABLE", "NOT_APPLICABLE");
        }

        File nativeDirectory = nativeDirectory(gameInstance);
        boolean lightingActive = installLighting(nativeDirectory);
        boolean clipperActive = installClipper(nativeDirectory);
        File lighting = new File(nativeDirectory, LIGHTING_ACTIVE_NAME);
        File clipper = new File(nativeDirectory, CLIPPER_ACTIVE_NAME);
        String lightingHash = hashOrState(lighting);
        String clipperHash = hashOrState(clipper);

        InstallResult result = new InstallResult(true, lightingActive, clipperActive,
                lightingHash, clipperHash);
        Log.i("ZD-ARM64-NATIVE", result.machineReadable()
                + " lightingPath=" + lighting.getAbsolutePath()
                + " clipperPath=" + clipper.getAbsolutePath());
        return result;
    }

    /** True only when the active Lighting library is the packaged R2 shim. */
    public static boolean isManagedLightingActive(GameInstance gameInstance) {
        File source = new File(AppStorage.requireSingleton().getLibraryPath(), LIGHTING_SHIM_NAME);
        File active = new File(nativeDirectory(gameInstance), LIGHTING_ACTIVE_NAME);
        try {
            return source.isFile() && active.isFile() && sha256(source).equals(sha256(active));
        } catch (IOException ignored) {
            return false;
        }
    }

    private static boolean installLighting(File nativeDirectory) {
        File appLibraryDirectory = new File(AppStorage.requireSingleton().getLibraryPath());
        File sourceShim = new File(appLibraryDirectory, LIGHTING_SHIM_NAME);
        File sourceLegacy = new File(appLibraryDirectory, LIGHTING_LEGACY_NAME);
        File activeShim = new File(nativeDirectory, LIGHTING_ACTIVE_NAME);
        File installedLegacy = new File(nativeDirectory, LIGHTING_LEGACY_NAME);
        File markerFile = new File(nativeDirectory, LIGHTING_MARKER_NAME);
        try {
            requireSupportedPageSize();
            validateExactArmSource(sourceLegacy, LIGHTING_LEGACY_NAME,
                    LIGHTING_LEGACY_SIZE, LIGHTING_LEGACY_SHA256);
            validateElf64Arm(sourceShim, "Lighting shim");
            String shimSha256 = sha256(sourceShim);
            ensureDirectory(nativeDirectory);

            if (activeShim.isFile() && !shimSha256.equals(sha256(activeShim))) {
                preserveForeignActive(activeShim);
            }
            copyVerified(sourceLegacy, installedLegacy, LIGHTING_LEGACY_SHA256);
            copyVerified(sourceShim, activeShim, shimSha256);

            String markerText = "mode=ARM64_LIGHTING_AB3_COMPAT\n"
                    + "base=c21989411bed8209229fc0207c3dc71a054a5b11\n"
                    + "legacy_sha256=" + LIGHTING_LEGACY_SHA256 + "\n"
                    + "shim_sha256=" + shimSha256 + "\n"
                    + "page_size=" + REQUIRED_PAGE_SIZE + "\n"
                    + "transmission=NO_OP\n"
                    + "updateTorch=DROP_RGB_AND_ADAPT_LEGACY_ABI\n";
            writeAtomically(markerFile, markerText.getBytes(StandardCharsets.UTF_8));
            Log.w(LIGHT_LOG_TAG, "ACTIVE: AB3-compatible ARM64 Lighting installed; legacy="
                    + shortHash(LIGHTING_LEGACY_SHA256) + " shim=" + shortHash(shimSha256));
            return true;
        } catch (Exception error) {
            Log.e(LIGHT_LOG_TAG, "ARM64 Lighting install failed", error);
            removeOurActive(activeShim, sourceShim, null);
            deleteMarker(markerFile, LIGHT_LOG_TAG);
            return false;
        }
    }

    private static boolean installClipper(File nativeDirectory) {
        File source = new File(AppStorage.requireSingleton().getLibraryPath(), CLIPPER_SOURCE_NAME);
        File active = new File(nativeDirectory, CLIPPER_ACTIVE_NAME);
        File markerFile = new File(nativeDirectory, CLIPPER_MARKER_NAME);
        try {
            requireSupportedPageSize();
            validateExactArmSource(source, CLIPPER_SOURCE_NAME, CLIPPER_SIZE, CLIPPER_SHA256);
            ensureDirectory(nativeDirectory);

            if (active.isFile() && !CLIPPER_SHA256.equals(sha256(active))) {
                preserveForeignActive(active);
            }
            copyVerified(source, active, CLIPPER_SHA256);

            String markerText = "mode=ARM64_PZCLIPPER_AB3\n"
                    + "clipper_sha256=" + CLIPPER_SHA256 + "\n"
                    + "jni_exports=19_EXACT_MATCH_X86_64\n"
                    + "page_size=" + REQUIRED_PAGE_SIZE + "\n";
            writeAtomically(markerFile, markerText.getBytes(StandardCharsets.UTF_8));
            Log.w(CLIPPER_LOG_TAG, "ACTIVE: audited ARM64 PZClipper installed; hash="
                    + shortHash(CLIPPER_SHA256));
            return true;
        } catch (Exception error) {
            Log.e(CLIPPER_LOG_TAG, "ARM64 PZClipper install failed", error);
            removeOurActive(active, source, CLIPPER_SHA256);
            deleteMarker(markerFile, CLIPPER_LOG_TAG);
            return false;
        }
    }

    private static File nativeDirectory(GameInstance gameInstance) {
        return new File(gameInstance.getGamePath(), "android/arm64-v8a");
    }

    private static void requireSupportedPageSize() throws IOException {
        long pageSize = Os.sysconf(OsConstants._SC_PAGESIZE);
        if (pageSize != REQUIRED_PAGE_SIZE) {
            throw new IOException("AB3 ARM64 native payloads require 4096-byte pages; device="
                    + pageSize);
        }
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("cannot create " + directory);
        }
    }

    private static void validateExactArmSource(File file, String name, long expectedSize,
                                               String expectedSha256) throws IOException {
        if (!file.isFile()) throw new IOException("packaged " + name + " is missing");
        if (file.length() != expectedSize) {
            throw new IOException(name + " size mismatch: expected=" + expectedSize
                    + " actual=" + file.length());
        }
        String actual = sha256(file);
        if (!expectedSha256.equals(actual)) {
            throw new IOException(name + " SHA-256 mismatch: expected=" + expectedSha256
                    + " actual=" + actual);
        }
        validateElf64Arm(file, name);
    }

    private static void validateElf64Arm(File file, String role) throws IOException {
        if (!file.isFile()) throw new IOException(role + " is missing: " + file);
        byte[] header = new byte[20];
        try (InputStream input = new FileInputStream(file)) {
            int offset = 0;
            while (offset < header.length) {
                int read = input.read(header, offset, header.length - offset);
                if (read < 0) break;
                offset += read;
            }
            if (offset != header.length) throw new IOException(role + " ELF header is truncated");
        }
        int machine = (header[18] & 0xff) | ((header[19] & 0xff) << 8);
        if ((header[0] & 0xff) != 0x7f || header[1] != 'E' || header[2] != 'L'
                || header[3] != 'F' || header[4] != 2 || header[5] != 1 || machine != 183) {
            throw new IOException(role + " is not a little-endian ELF64 AArch64 library");
        }
    }

    private static void preserveForeignActive(File active) throws IOException {
        File backup = new File(active.getParentFile(), active.getName() + ".zomdroid-arm64-original");
        if (backup.isFile()) {
            if (!active.delete()) throw new IOException("cannot remove foreign " + active);
            return;
        }
        moveReplacing(active, backup);
        Log.w("ZD-ARM64-NATIVE", "Preserved foreign " + active.getName()
                + " as " + backup.getName());
    }

    private static void copyVerified(File source, File target, String expectedSha256)
            throws IOException {
        if (target.isFile() && expectedSha256.equals(sha256(target))) return;
        File temporary = new File(target.getParentFile(), target.getName() + ".r2-tmp");
        Files.copy(source.toPath(), temporary.toPath(), StandardCopyOption.REPLACE_EXISTING);
        if (!expectedSha256.equals(sha256(temporary))) {
            //noinspection ResultOfMethodCallIgnored
            temporary.delete();
            throw new IOException("post-copy SHA-256 mismatch for " + target.getName());
        }
        //noinspection ResultOfMethodCallIgnored
        temporary.setReadable(true, false);
        //noinspection ResultOfMethodCallIgnored
        temporary.setExecutable(true, false);
        moveReplacing(temporary, target);
    }

    private static void writeAtomically(File target, byte[] content) throws IOException {
        File temporary = new File(target.getParentFile(), target.getName() + ".tmp");
        Files.write(temporary.toPath(), content);
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

    private static void removeOurActive(File active, File source, String fixedSourceSha256) {
        try {
            if (!active.isFile() || !source.isFile()) return;
            String sourceHash = fixedSourceSha256 != null ? fixedSourceSha256 : sha256(source);
            if (sourceHash.equals(sha256(active)) && !active.delete()) {
                Log.e("ZD-ARM64-NATIVE", "Could not remove failed managed library " + active);
            }
        } catch (IOException ignored) {
            // Never delete an active file whose identity cannot be proven.
        }
    }

    private static void deleteMarker(File marker, String logTag) {
        if (marker.isFile() && !marker.delete()) {
            Log.e(logTag, "Could not remove stale marker " + marker);
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

    private static String sha256(File file) throws IOException {
        final MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException impossible) {
            throw new AssertionError(impossible);
        }
        byte[] buffer = new byte[128 * 1024];
        try (InputStream input = new FileInputStream(file)) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                if (read != 0) digest.update(buffer, 0, read);
            }
        }
        StringBuilder result = new StringBuilder(64);
        for (byte value : digest.digest()) {
            result.append(String.format(Locale.ROOT, "%02x", value & 0xff));
        }
        return result.toString();
    }

    private static String shortHash(String value) {
        return value.substring(0, 12);
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }
}
