package com.zomdroid.patch;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

/** Keeps every byte-distinct native payload while collapsing exact duplicate backups. */
final class DistinctFileBackup {
    private DistinctFileBackup() {}

    static File preserve(File source, String suffix) throws IOException {
        if (!source.isFile()) return null;
        File directory = source.getParentFile();
        File candidate = new File(directory, source.getName() + suffix);
        int sequence = 2;
        while (candidate.exists()) {
            if (candidate.isFile() && sameBytes(source, candidate)) {
                // The exact bytes already have a recoverable copy. Removing the duplicate active
                // entry performs the requested disable without overwriting any earlier payload.
                Files.delete(source.toPath());
                return candidate;
            }
            candidate = new File(directory, source.getName() + suffix + "-" + sequence++);
        }
        move(source, candidate);
        return candidate;
    }

    private static boolean sameBytes(File first, File second) throws IOException {
        if (first.length() != second.length()) return false;
        byte[] left = new byte[128 * 1024];
        byte[] right = new byte[left.length];
        try (InputStream a = new FileInputStream(first);
             InputStream b = new FileInputStream(second)) {
            while (true) {
                int countA = readChunk(a, left);
                int countB = readChunk(b, right);
                if (countA != countB) return false;
                if (countA < 0) return true;
                for (int i = 0; i < countA; i++) {
                    if (left[i] != right[i]) return false;
                }
            }
        }
    }

    private static int readChunk(InputStream input, byte[] buffer) throws IOException {
        int offset = 0;
        while (offset < buffer.length) {
            int count = input.read(buffer, offset, buffer.length - offset);
            if (count < 0) return offset == 0 ? -1 : offset;
            if (count > 0) offset += count;
        }
        return offset;
    }

    private static void move(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source.toPath(), target.toPath());
        }
    }
}
