package com.zomdroid.patch;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/** Host proof that repeated native OFF/ON cycles cannot overwrite distinct originals. */
public final class BackupPreservationUnit {
    public static void main(String[] args) throws Exception {
        File root = Files.createTempDirectory("zomdroid-distinct-backup").toFile();
        File active = new File(root, "libNative.so");

        write(active, "foreign-A");
        File first = DistinctFileBackup.preserve(active, ".disabled");
        require(first.getName().equals("libNative.so.disabled"), "first backup name");
        require(read(first).equals("foreign-A"), "first backup bytes");

        write(active, "foreign-A");
        File duplicate = DistinctFileBackup.preserve(active, ".disabled");
        require(duplicate.equals(first), "duplicate collapses to existing backup");
        require(!active.exists(), "duplicate active removed");

        write(active, "managed-B");
        File second = DistinctFileBackup.preserve(active, ".disabled");
        require(second.getName().equals("libNative.so.disabled-2"), "second backup name");
        require(read(first).equals("foreign-A"), "first backup retained");
        require(read(second).equals("managed-B"), "second backup bytes");

        write(active, "foreign-C");
        File third = DistinctFileBackup.preserve(active, ".disabled");
        require(third.getName().equals("libNative.so.disabled-3"), "third backup name");
        require(read(first).equals("foreign-A"), "original still retained");
        require(read(second).equals("managed-B"), "managed backup still retained");
        require(read(third).equals("foreign-C"), "third backup bytes");
        System.out.println("BACKUP_PRESERVATION_UNIT PASS");
    }

    private static void write(File file, String value) throws Exception {
        Files.write(file.toPath(), value.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws Exception {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
