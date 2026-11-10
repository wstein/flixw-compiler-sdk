package dev.flixw.sdk.kit;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Atomic publication of a plugin report, or ordinary stdout when no path is requested. */
public final class AtomicOutput {
    private AtomicOutput() { }
    /** Writes a report beside a temporary file, then publishes it with one atomic rename. */
    public static void write(Path projectRoot, Path requested, String contents) throws IOException {
        if (requested == null) {
            System.out.print(contents);
            return;
        }
        Path target = (requested.isAbsolute() ? requested : projectRoot.resolve(requested))
            .toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent))
            throw new IOException("output directory does not exist: " + parent);
        Path temporary = Files.createTempFile(parent, ".flixw-metrics-", ".tmp");
        try {
            Files.writeString(temporary, contents);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("output filesystem does not support atomic replacement: "
                    + target, e);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

}
