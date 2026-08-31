package com.zomdroid;

import androidx.annotation.NonNull;

import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Owns the complete set of JVM properties written by OPT-LAB.
 *
 * <p>Game instances and the free-form JVM field can outlive an APK upgrade.  They must never be
 * allowed to retain an older requested state beside the current registry snapshot.  Every launch
 * therefore removes the complete managed namespace first and writes each property exactly once.</p>
 */
public final class OptLabLaunchContract {
    private static final String PROPERTY_PREFIX = "-D";
    private static final String[] MANAGED_PREFIXES = {
            "zomdroid.optlab.",
            "zomdroid.native.pathfinding.",
            "zomdroid.native.popman."
    };

    private OptLabLaunchContract() {}

    /** Removes every persisted/free-form value owned by OPT-LAB before a fresh snapshot is added. */
    public static void clearManagedProperties(@NonNull List<String> jvmArgs) {
        for (Iterator<String> iterator = jvmArgs.iterator(); iterator.hasNext();) {
            String argument = iterator.next();
            String key = propertyKey(argument);
            if (key != null && isManagedKey(key)) iterator.remove();
        }
    }

    /** Replaces one property rather than relying on JVM duplicate-option precedence. */
    public static void putProperty(@NonNull List<String> jvmArgs,
                                   @NonNull String key, @NonNull String value) {
        if (!isManagedKey(key)) {
            throw new IllegalArgumentException("Property is outside OPT-LAB contract: " + key);
        }
        for (Iterator<String> iterator = jvmArgs.iterator(); iterator.hasNext();) {
            String existing = propertyKey(iterator.next());
            if (key.equals(existing)) iterator.remove();
        }
        jvmArgs.add(PROPERTY_PREFIX + key + "=" + value);
    }

    /** Fails before JNI/JVM startup if the authoritative contract contains a duplicate. */
    public static void requireUniqueManagedProperties(@NonNull List<String> jvmArgs) {
        Set<String> seen = new HashSet<>();
        for (String argument : jvmArgs) {
            String key = propertyKey(argument);
            if (key != null && isManagedKey(key) && !seen.add(key)) {
                throw new IllegalStateException("Duplicate OPT-LAB JVM property: " + key);
            }
        }
    }

    public static String valueOf(@NonNull List<String> jvmArgs, @NonNull String key) {
        String prefix = PROPERTY_PREFIX + key + "=";
        String value = null;
        for (String argument : jvmArgs) {
            if (!argument.startsWith(prefix)) continue;
            if (value != null) {
                throw new IllegalStateException("Duplicate OPT-LAB JVM property: " + key);
            }
            value = argument.substring(prefix.length());
        }
        return value;
    }

    public static void requireValue(@NonNull List<String> jvmArgs, @NonNull String key,
                                    @NonNull String expected) {
        String actual = valueOf(jvmArgs, key);
        if (!expected.equals(actual)) {
            throw new IllegalStateException("OPT-LAB launch value mismatch for " + key
                    + ": expected=" + expected + " actual=" + actual);
        }
    }

    private static boolean isManagedKey(String key) {
        for (String prefix : MANAGED_PREFIXES) {
            if (key.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String propertyKey(String argument) {
        if (argument == null || !argument.startsWith(PROPERTY_PREFIX)) return null;
        int equals = argument.indexOf('=', PROPERTY_PREFIX.length());
        // The JVM also accepts -Dkey without an equals sign.  Treat it as managed too so a
        // persisted shorthand flag cannot survive beside the explicit current -Dkey=0 value.
        if (equals < 0) {
            return argument.length() > PROPERTY_PREFIX.length()
                    ? argument.substring(PROPERTY_PREFIX.length()) : null;
        }
        if (equals == PROPERTY_PREFIX.length()) return null;
        return argument.substring(PROPERTY_PREFIX.length(), equals);
    }
}
