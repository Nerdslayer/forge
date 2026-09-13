package forge.gui.cardcreator;

import forge.localinstance.properties.ForgeConstants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;

/** Owns safe persistence of user-created card scripts. */
public final class CustomCardRepository {
    private final Path root;

    public CustomCardRepository() {
        this(Path.of(ForgeConstants.USER_CUSTOM_CARDS_DIR));
    }

    public CustomCardRepository(final Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public Path getRoot() {
        return root;
    }

    public boolean isOwnedPath(final Path path) {
        return path != null && path.toAbsolutePath().normalize().startsWith(root);
    }

    public Path pathForName(final String cardName) {
        final String filename = normalizeFilename(cardName) + ".txt";
        final String first = filename.substring(0, 1);
        return root.resolve(first).resolve(filename).normalize();
    }

    public boolean exists(final String cardName) {
        return Files.exists(pathForName(cardName));
    }

    public Path save(final String cardName, final String script) throws IOException {
        final Path target = pathForName(cardName);
        if (!isOwnedPath(target)) {
            throw new IOException("Refusing to write a card outside the custom-card directory.");
        }
        Files.createDirectories(target.getParent());
        final Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, script == null ? "" : script, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (final IOException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
        return target;
    }

    public void delete(final String cardName) throws IOException {
        final Path target = pathForName(cardName);
        if (!isOwnedPath(target)) throw new IOException("Refusing to delete a card outside the custom-card directory.");
        Files.deleteIfExists(target);
    }

    public static String normalizeFilename(final String name) {
        final String safeName = name == null ? "card" : name.toLowerCase(Locale.ROOT);
        final String normalized = safeName.replaceAll("[^-a-z0-9_\\s]", "")
                .replaceAll("[-\\s]", "_").replaceAll("__+", "_");
        return normalized.isBlank() ? "card" : normalized;
    }
}
