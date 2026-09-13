package forge.gui.cardcreator;

import java.util.List;

/** Curated simple keywords that can be round-tripped by the initial creator. */
public final class CardCreatorKeywords {
    public static final List<CardCreatorKeyword> SUPPORTED = List.of(
            new CardCreatorKeyword("Flying", "Flying"),
            new CardCreatorKeyword("Vigilance", "Vigilance"),
            new CardCreatorKeyword("Trample", "Trample"),
            new CardCreatorKeyword("Lifelink", "Lifelink"),
            new CardCreatorKeyword("Deathtouch", "Deathtouch"),
            new CardCreatorKeyword("Reach", "Reach"),
            new CardCreatorKeyword("Haste", "Haste"),
            new CardCreatorKeyword("First strike", "First strike"),
            new CardCreatorKeyword("Double strike", "Double strike"),
            new CardCreatorKeyword("Defender", "Defender"),
            new CardCreatorKeyword("Menace", "Menace"),
            new CardCreatorKeyword("Fear", "Fear"),
            new CardCreatorKeyword("Intimidate", "Intimidate"),
            new CardCreatorKeyword("Flash", "Flash"),
            new CardCreatorKeyword("Hexproof", "Hexproof"),
            new CardCreatorKeyword("Shroud", "Shroud"),
            new CardCreatorKeyword("Indestructible", "Indestructible"));

    private CardCreatorKeywords() {
    }

    public static boolean isManaged(final String keyword) {
        return SUPPORTED.stream().anyMatch(k -> k.scriptName().equalsIgnoreCase(keyword.trim()));
    }
}
