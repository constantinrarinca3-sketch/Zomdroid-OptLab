package com.zomdroid.agent.optimization;

import java.io.File;
import java.util.Locale;

/**
 * Repairs the Android-only duplicated absolute path produced while Project Zomboid loads
 * mod scripts. The resolver is deliberately narrow: it only returns a replacement that is an
 * existing regular file below the launcher-provided mods root. Every other input is returned
 * unchanged.
 */
public final class ModPathRuntime {
    private static volatile boolean enabled;
    private static volatile File modsRoot;

    private ModPathRuntime() {}

    public static void configureFromProperties() {
        enabled = "1".equals(System.getProperty("zomdroid.modpath.fix", "0"));
        String configuredRoot = System.getProperty("zomdroid.modpath.root", "").trim();
        modsRoot = configuredRoot.isEmpty() ? null : new File(configuredRoot).getAbsoluteFile();
    }

    public static boolean isReady() {
        return enabled && modsRoot != null;
    }

    public static void disable() {
        enabled = false;
    }

    /**
     * Return a real, casing-preserving mod file only when the input can be proven to point below
     * the configured mods root. Failures and ambiguous case-insensitive matches are fail-open.
     */
    public static String normalize(String input) {
        if (!isReady() || input == null || input.isEmpty()) return input;

        try {
            String path = normalizeSeparators(input);
            String rootPath = stripTrailingSlash(normalizeSeparators(modsRoot.getAbsolutePath()));
            String rootPrefix = rootPath + "/";

            if (!path.regionMatches(true, 0, rootPrefix, 0, rootPrefix.length())) {
                return input;
            }

            String relative = path.substring(rootPrefix.length());

            // Confirm the exact duplicated Android shape before removing anything:
            //   <real mods root>/data/user/.../files/instances/.../zomboid/mods/<mod file>
            String relativeLower = relative.toLowerCase(Locale.ROOT);
            boolean androidAbsolute = relativeLower.startsWith("data/user/")
                    || relativeLower.startsWith("data/data/");
            if (androidAbsolute && relativeLower.contains("/files/instances/")) {
                int nestedMods = relativeLower.lastIndexOf("/zomboid/mods/");
                if (nestedMods >= 0) {
                    String realRelative = relative.substring(
                            nestedMods + "/zomboid/mods/".length());
                    File resolved = resolveExistingFile(modsRoot, realRelative);
                    // Prefer the live mod even if a stale legacy shadow file still exists.
                    return resolved == null ? input : applied(input, resolved);
                }
            }

            File original = new File(input);
            if (original.isFile()) return input;

            // Also repair an ordinary absolute mod path whose casing alone was damaged.
            File resolved = resolveExistingFile(modsRoot, relative);
            return resolved == null ? input : applied(input, resolved);
        } catch (Throwable ignored) {
            // A path fix must never become a game-start or file-loading dependency.
            return input;
        }
    }

    public static File normalizeFile(File input) {
        if (input == null) return null;
        String original = input.getPath();
        String normalized = normalize(original);
        return original.equals(normalized) ? input : new File(normalized);
    }

    private static String applied(String original, File resolved) {
        String replacement = resolved.getPath();
        if (!replacement.equals(original)) {
            ProofRuntime.appliedOnce("MOD_PATH_RESOLVER",
                    "duplicate_or_lowercased_absolute_mod_path");
        }
        return replacement;
    }

    private static File resolveExistingFile(File root, String relative) {
        if (root == null || relative == null || relative.isEmpty() || !root.isDirectory()) {
            return null;
        }

        String normalized = normalizeSeparators(relative);
        while (normalized.startsWith("/")) normalized = normalized.substring(1);
        if (normalized.isEmpty()) return null;

        String[] segments = normalized.split("/");
        File current = root;
        for (int index = 0; index < segments.length; index++) {
            String segment = segments[index];
            if (segment.isEmpty() || ".".equals(segment)) continue;
            if ("..".equals(segment)
                    || segment.toLowerCase(Locale.ROOT).startsWith(".fmtrashed")) {
                return null;
            }

            File exact = new File(current, segment);
            if (exact.exists()) {
                current = exact;
            } else {
                File[] children = current.listFiles();
                if (children == null) return null;

                File match = null;
                for (File child : children) {
                    if (!child.getName().equalsIgnoreCase(segment)) continue;
                    if (match != null && !match.getName().equals(child.getName())) {
                        // Two differently-cased entries match. Guessing would be unsafe.
                        return null;
                    }
                    match = child;
                }
                if (match == null) return null;
                current = match;
            }

            if (index < segments.length - 1 && !current.isDirectory()) return null;
        }

        if (!current.isFile()) return null;
        try {
            File canonicalRoot = root.getCanonicalFile();
            File canonicalFile = current.getCanonicalFile();
            String rootBoundary = canonicalRoot.getPath() + File.separator;
            if (!canonicalFile.getPath().startsWith(rootBoundary)) return null;
            return canonicalFile;
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String normalizeSeparators(String value) {
        String normalized = value.replace('\\', '/');
        while (normalized.contains("//")) normalized = normalized.replace("//", "/");
        return normalized;
    }

    private static String stripTrailingSlash(String value) {
        while (value.length() > 1 && value.endsWith("/")) {
            value = value.substring(0, value.length() - 1);
        }
        return value;
    }
}
