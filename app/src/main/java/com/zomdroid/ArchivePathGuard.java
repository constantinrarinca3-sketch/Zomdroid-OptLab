package com.zomdroid;

import androidx.annotation.NonNull;

import java.io.File;
import java.io.IOException;

/** Resolves an archive member without allowing it to escape the extraction root. */
final class ArchivePathGuard {
    private ArchivePathGuard() {}

    @NonNull
    static File resolve(@NonNull String destination, @NonNull String entryName)
            throws IOException {
        if (entryName.indexOf('\0') >= 0) {
            throw new IOException("Archive entry contains NUL");
        }
        if (new File(entryName).isAbsolute()) {
            throw new IOException("Archive entry is absolute: " + entryName);
        }
        File root = new File(destination).getCanonicalFile();
        File target = new File(root, entryName).getCanonicalFile();
        String rootPrefix = root.getPath() + File.separator;
        if (!target.equals(root) && !target.getPath().startsWith(rootPrefix)) {
            throw new IOException("Archive entry escapes destination: " + entryName);
        }
        return target;
    }
}
