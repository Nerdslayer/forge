package forge.gui.cardcreator;

import forge.localinstance.properties.ForgeConstants;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Reads and safely updates the simple custom-edition files used by Card Creator. */
public final class CustomEditionRepository {
    private final Path root;

    public CustomEditionRepository() {
        this(Path.of(ForgeConstants.USER_CUSTOM_EDITIONS_DIR));
    }

    public CustomEditionRepository(final Path root) {
        this.root = root.toAbsolutePath().normalize();
    }

    public List<CustomSetInfo> list() throws IOException {
        if (!Files.isDirectory(root)) return List.of();
        final List<CustomSetInfo> result = new ArrayList<>();
        try (var files = Files.list(root)) {
            files.filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".txt"))
                    .sorted(Comparator.comparing(path -> path.getFileName().toString(), String.CASE_INSENSITIVE_ORDER))
                    .forEach(path -> {
                        try {
                            final List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                            final String code = metadata(lines, "Code");
                            final String name = metadata(lines, "Name");
                            if (!code.isBlank() && !name.isBlank()) result.add(new CustomSetInfo(code, name, path));
                        } catch (IOException ignored) {
                            // An invalid set is reported when the user selects or validates it.
                        }
                    });
        }
        return result;
    }

    public CustomSetInfo create(final String code, final String name) throws IOException {
        final String normalizedCode = normalizeCode(code);
        if (normalizedCode.isBlank() || name == null || name.isBlank()) {
            throw new IOException("A custom set needs a nonempty code and name.");
        }
        Files.createDirectories(root);
        final Path target = root.resolve(normalizedCode + ".txt").normalize();
        if (!target.getParent().equals(root) || Files.exists(target)) {
            throw new IOException("A custom set with that code already exists.");
        }
        final String contents = "[metadata]\nCode=" + normalizedCode + "\nDate=" + LocalDate.now()
                + "\nName=" + name.trim() + "\nType=Custom Set\nFoil=NotSupported\n\n[cards]\n";
        atomicWrite(target, contents);
        return new CustomSetInfo(normalizedCode, name.trim(), target);
    }

    /** Returns the next numeric collector number after the highest entry in the set. */
    public String nextCollectorNumber(final CustomSetInfo set) throws IOException {
        if (set == null || set.file() == null || !set.file().toAbsolutePath().normalize().startsWith(root)) {
            throw new IOException("Refusing to inspect an invalid custom set.");
        }
        final List<String> lines = Files.readAllLines(set.file(), StandardCharsets.UTF_8);
        boolean cardsSection = false;
        int highest = 0;
        for (final String line : lines) {
            if (line.trim().equalsIgnoreCase("[cards]")) cardsSection = true;
            else if (line.trim().startsWith("[") && line.trim().endsWith("]")) cardsSection = false;
            if (!cardsSection || line.isBlank() || line.trim().startsWith("[")) continue;
            try {
                highest = Math.max(highest, Integer.parseInt(entryCollectorNumber(line)));
            } catch (final NumberFormatException ignored) {
                // Non-numeric collector numbers do not affect the next numeric number.
            }
        }
        return Integer.toString(highest + 1);
    }

    public void saveCardEntry(final CustomSetInfo set, final String cardName, final String oldCardName,
            final String collectorNumber, final String rarity) throws IOException {
        if (set == null || set.file() == null || !set.file().toAbsolutePath().normalize().startsWith(root)
                || cardName == null || cardName.isBlank()) {
            throw new IOException("Refusing to update an invalid custom set.");
        }
        final List<String> lines = Files.readAllLines(set.file(), StandardCharsets.UTF_8);
        final String requestedNumber = collectorNumber == null || collectorNumber.isBlank()
                ? "1" : collectorNumber.trim();
        final List<String> output = new ArrayList<>();
        boolean cardsSection = false;
        boolean added = false;
        for (final String line : lines) {
            if (line.trim().equalsIgnoreCase("[cards]")) cardsSection = true;
            else if (line.trim().startsWith("[") && line.trim().endsWith("]")) cardsSection = false;
            if (cardsSection && !line.isBlank() && !line.trim().startsWith("[")
                    && (sameCardName(line, oldCardName) || sameCardName(line, cardName))) {
                if (!added) {
                    output.add(entryLine(cardName, collectorNumber, rarity));
                    added = true;
                }
                continue;
            }
            if (cardsSection && !line.isBlank() && !line.trim().startsWith("[")
                    && requestedNumber.equals(entryCollectorNumber(line))) {
                throw new IOException("Collector number " + requestedNumber + " is already used by another card in " + set.code() + ".");
            }
            output.add(line);
            if (cardsSection && !added && line.trim().equalsIgnoreCase("[cards]")) {
                // Insert after the section marker, keeping unrelated entries below it intact.
                output.add(entryLine(cardName, collectorNumber, rarity));
                added = true;
            }
        }
        if (!output.stream().anyMatch(line -> line.trim().equalsIgnoreCase("[cards]"))) {
            if (!output.isEmpty() && !output.get(output.size() - 1).isBlank()) output.add("");
            output.add("[cards]");
            output.add(entryLine(cardName, collectorNumber, rarity));
        } else if (!added) {
            output.add(entryLine(cardName, collectorNumber, rarity));
        }
        atomicWrite(set.file(), String.join("\n", output) + "\n");
    }

    public void removeCardEntry(final CustomSetInfo set, final String cardName) throws IOException {
        if (set == null || set.file() == null || !set.file().toAbsolutePath().normalize().startsWith(root)) {
            throw new IOException("Refusing to update an invalid custom set.");
        }
        final List<String> lines = Files.readAllLines(set.file(), StandardCharsets.UTF_8);
        final List<String> output = new ArrayList<>();
        boolean cardsSection = false;
        for (final String line : lines) {
            if (line.trim().equalsIgnoreCase("[cards]")) cardsSection = true;
            else if (line.trim().startsWith("[") && line.trim().endsWith("]")) cardsSection = false;
            if (cardsSection && !line.isBlank() && !line.trim().startsWith("[") && sameCardName(line, cardName)) continue;
            output.add(line);
        }
        atomicWrite(set.file(), String.join("\n", output) + "\n");
    }

    public Path getRoot() {
        return root;
    }

    private static String entryLine(final String cardName, final String collectorNumber, final String rarity) {
        final String number = collectorNumber == null || collectorNumber.isBlank() ? "1" : collectorNumber.trim();
        final String cardRarity = rarity == null || rarity.isBlank() ? "C" : rarity.trim();
        return number + " " + cardRarity + " " + cardName.trim();
    }

    private static String entryCollectorNumber(final String line) {
        final String candidate = line.trim();
        final int separator = candidate.indexOf(' ');
        return separator < 0 ? candidate : candidate.substring(0, separator);
    }

    private static boolean sameCardName(final String line, final String cardName) {
        if (cardName == null || cardName.isBlank()) return false;
        String candidate = line.trim();
        if (candidate.matches("^\\S+\\s+\\S+\\s+.*$")) candidate = candidate.replaceFirst("^\\S+\\s+\\S+\\s+", "");
        else if (candidate.matches("^\\S+\\s+.*$")) candidate = candidate.replaceFirst("^\\S+\\s+", "");
        final int artist = candidate.indexOf(" @");
        if (artist >= 0) candidate = candidate.substring(0, artist);
        return candidate.trim().equalsIgnoreCase(cardName.trim());
    }

    private static String metadata(final List<String> lines, final String key) {
        boolean metadata = false;
        for (final String line : lines) {
            if (line.trim().equalsIgnoreCase("[metadata]")) metadata = true;
            else if (line.trim().startsWith("[") && line.trim().endsWith("]")) metadata = false;
            else if (metadata && line.startsWith(key + "=")) return line.substring(key.length() + 1).trim();
        }
        return "";
    }

    private static String normalizeCode(final String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]", "");
    }

    private static void atomicWrite(final Path target, final String contents) throws IOException {
        Files.createDirectories(target.getParent());
        final Path temporary = Files.createTempFile(target.getParent(), target.getFileName().toString(), ".tmp");
        try {
            Files.writeString(temporary, contents, StandardCharsets.UTF_8);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ex) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
