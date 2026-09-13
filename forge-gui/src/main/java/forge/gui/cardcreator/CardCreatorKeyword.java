package forge.gui.cardcreator;

/** A keyword offered by the first structured Card Creator form. */
public record CardCreatorKeyword(String displayName, String scriptName) {
    @Override
    public String toString() {
        return displayName;
    }
}
