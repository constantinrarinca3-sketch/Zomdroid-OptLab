package com.zomdroid.patch;

import android.util.Log;

import com.zomdroid.AppStorage;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;

/** Ensures that the APK uses the audited MobileGL OPT-LAB V3 022 renderer by default. */
public final class MobileGlDefaultRendererManager {
    private static final String LOG_TAG = "ZD-MOBILEGL-DEFAULT";
    private static final String SOURCE_NAME = "libMobileGLPZDefault.so";
    private static final String ACTIVE_NAME = "libMobileGLPZ.so";
    private static final long DEFAULT_SIZE = 14_876_840L;
    private static final long MIN_CUSTOM_SIZE = 512L * 1024L;
    private static final String DEFAULT_SHA256 =
            "8dc064f0386d01fccab8b94681921fdf9c304ed8995e87e07f1906119728310f";
    private static final String LEGACY_PZF23D4_SHA256 =
            "f5b280fac78f2189daeac41d7d6b456747e1e1970754bcf807135ae33af82e8d";

    private MobileGlDefaultRendererManager() {}

    public static final class Result {
        public final boolean required;
        public final boolean ready;
        public final String mode;
        public final String activeSha256;
        public final long activeBytes;
        public final String activePath;

        private Result(boolean required, boolean ready, String mode, String activeSha256,
                       long activeBytes, String activePath) {
            this.required = required;
            this.ready = ready;
            this.mode = mode;
            this.activeSha256 = activeSha256;
            this.activeBytes = activeBytes;
            this.activePath = activePath;
        }

        public String machineReadable() {
            return "mobileGlRequired=" + bit(required)
                    + " mobileGlReady=" + bit(ready)
                    + " mobileGlMode=" + mode
                    + " mobileGlBytes=" + activeBytes
                    + " mobileGlSha256=" + activeSha256
                    + " mobileGlPath=" + activePath;
        }
    }

    /**
     * Keeps a valid user-injected AArch64 renderer. If none exists, installs exact OPT-LAB V3
     * 022 from the APK. The previous packaged PZF23D4 default is upgraded automatically, while
     * an unknown custom or invalid renderer is preserved.
     */
    public static Result ensureAvailable(boolean required) {
        File target = new File(AppStorage.requireSingleton().getHomePath(),
                "dependencies/libs/android-arm64-v8a/" + ACTIVE_NAME);
        if (!required) {
            return new Result(false, true, "NOT_SELECTED", "NOT_CHECKED", 0L,
                    target.getAbsolutePath());
        }

        String invalidReason = null;
        boolean upgradeLegacyDefault = false;
        if (target.isFile()) {
            try {
                validateArm64Renderer(target, "active renderer");
                String activeHash = sha256(target);
                if (DEFAULT_SHA256.equals(activeHash)) {
                    Result result = new Result(true, true, "MGL022_DEFAULT_PRESENT", activeHash,
                            target.length(), target.getAbsolutePath());
                    Log.i(LOG_TAG, result.machineReadable());
                    return result;
                }
                if (LEGACY_PZF23D4_SHA256.equals(activeHash)) {
                    upgradeLegacyDefault = true;
                    invalidReason = "LEGACY_PZF23D4_DEFAULT";
                } else {
                    Result result = new Result(true, true, "CUSTOM_ARM64_PRESERVED", activeHash,
                            target.length(), target.getAbsolutePath());
                    Log.i(LOG_TAG, result.machineReadable());
                    return result;
                }
            } catch (IOException invalid) {
                invalidReason = invalid.getMessage();
            }
        }

        File source = new File(AppStorage.requireSingleton().getLibraryPath(), SOURCE_NAME);
        try {
            validateExactDefault(source);
            ensureDirectory(target.getParentFile());
            if (target.isFile()) {
                if (upgradeLegacyDefault) {
                    Log.i(LOG_TAG, "Upgrading packaged PZF23D4 default to MobileGL 022");
                } else {
                    File backup = preserveInvalid(target);
                    Log.w(LOG_TAG, "Preserved invalid injected renderer as " + backup.getName()
                            + "; reason=" + sanitize(invalidReason));
                }
            }
            copyVerified(source, target, DEFAULT_SHA256);
            validateArm64Renderer(target, "installed default renderer");

            Result result = new Result(true, true, upgradeLegacyDefault
                    ? "MGL022_DEFAULT_UPGRADED" : "MGL022_DEFAULT_INSTALLED",
                    sha256(target), target.length(), target.getAbsolutePath());
            Log.w(LOG_TAG, result.machineReadable());
            return result;
        } catch (Exception error) {
            String hash = hashOrState(target);
            Result result = new Result(true, false, "INSTALL_FAILED", hash,
                    target.isFile() ? target.length() : 0L, target.getAbsolutePath());
            Log.e(LOG_TAG, result.machineReadable() + " invalidReason="
                    + sanitize(invalidReason), error);
            return result;
        }
    }

    private static void validateExactDefault(File source) throws IOException {
        if (!source.isFile()) throw new IOException("packaged " + SOURCE_NAME + " is missing");
        if (source.length() != DEFAULT_SIZE) {
            throw new IOException("default renderer size mismatch: expected=" + DEFAULT_SIZE
                    + " actual=" + source.length());
        }
        String hash = sha256(source);
        if (!DEFAULT_SHA256.equals(hash)) {
            throw new IOException("default renderer SHA-256 mismatch: expected=" + DEFAULT_SHA256
                    + " actual=" + hash);
        }
        validateArm64Renderer(source, "packaged default renderer");
    }

    private static void validateArm64Renderer(File file, String role) throws IOException {
        if (!file.isFile()) throw new IOException(role + " is missing");
        if (file.length() < MIN_CUSTOM_SIZE) {
            throw new IOException(role + " is implausibly small: " + file.length());
        }
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

    private static File preserveInvalid(File target) throws IOException {
        File directory = target.getParentFile();
        File backup = new File(directory, target.getName() + ".zomdroid-r2-invalid");
        int suffix = 2;
        while (backup.exists()) {
            backup = new File(directory, target.getName() + ".zomdroid-r2-invalid-" + suffix++);
        }
        moveReplacing(target, backup);
        return backup;
    }

    private static void ensureDirectory(File directory) throws IOException {
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("cannot create " + directory);
        }
    }

    private static void copyVerified(File source, File target, String expectedSha256)
            throws IOException {
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

    private static void moveReplacing(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
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

    private static String sanitize(String value) {
        return value == null ? "NONE" : value.replace('\n', '_').replace('\r', '_');
    }

    private static int bit(boolean value) {
        return value ? 1 : 0;
    }
}
