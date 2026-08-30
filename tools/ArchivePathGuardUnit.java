package com.zomdroid;

import java.io.File;
import java.io.IOException;

/** Host regression for ZIP/TAR path traversal containment. */
public final class ArchivePathGuardUnit {
    public static void main(String[] args) throws Exception {
        File root = new File(System.getProperty("java.io.tmpdir"),
                "zomdroid-archive-root").getCanonicalFile();
        require(ArchivePathGuard.resolve(root.getPath(), "mods/a/file.txt")
                .equals(new File(root, "mods/a/file.txt").getCanonicalFile()), "valid member");
        require(ArchivePathGuard.resolve(root.getPath(), "./mods/file.txt")
                .equals(new File(root, "mods/file.txt").getCanonicalFile()), "dot member");
        reject(root, "../escape.txt");
        reject(root, "mods/../../escape.txt");
        reject(root, new File(root.getParentFile(), "absolute.txt").getAbsolutePath());
        reject(root, "../" + root.getName() + "-suffix/prefix-collision.txt");
        System.out.println("ARCHIVE_PATH_GUARD_UNIT PASS traversal=4 valid=2");
    }

    private static void reject(File root, String entry) throws Exception {
        try {
            ArchivePathGuard.resolve(root.getPath(), entry);
            throw new AssertionError("accepted traversal: " + entry);
        } catch (IOException expected) {
            // expected
        }
    }

    private static void require(boolean value, String label) {
        if (!value) throw new AssertionError(label);
    }
}
