package forge.gui.cardcreator;

import java.nio.file.Path;

/** A custom edition that can receive creator cards. */
public record CustomSetInfo(String code, String name, Path file) {
    @Override
    public String toString() {
        return name + " (" + code + ")";
    }
}
