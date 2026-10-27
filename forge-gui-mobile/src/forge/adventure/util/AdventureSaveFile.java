package forge.adventure.util;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Finish writing and closing a new save before replacing the last usable checkpoint. */
public final class AdventureSaveFile {
    private AdventureSaveFile() { }

    @FunctionalInterface
    public interface Writer {
        void write(OutputStream stream) throws IOException;
    }

    public static void write(Path destination, Writer writer) throws IOException {
        Path target = destination.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            try (OutputStream stream = Files.newOutputStream(temporary)) {
                writer.write(stream);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
