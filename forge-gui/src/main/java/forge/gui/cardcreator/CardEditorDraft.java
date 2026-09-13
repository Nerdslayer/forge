package forge.gui.cardcreator;

import forge.card.CardRules;
import forge.card.ICardFace;
import forge.gui.card.CardScriptInfo;
import forge.item.PaperCard;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/** Mutable structured fields for one Card Creator draft. */
public final class CardEditorDraft {
    private String name;
    private String manaCost;
    private String types;
    private String power;
    private String toughness;
    private final Set<String> keywords = new LinkedHashSet<>();
    private String customSetCode;
    private String sourceSetCode;
    private String collectorNumber = "1";
    private String rarity = "C";
    private String sourceName;
    private String sourceScript;
    private boolean newCard;

    public static CardEditorDraft newCard() {
        final CardEditorDraft draft = new CardEditorDraft();
        draft.name = "New Card";
        draft.manaCost = "2";
        draft.types = "Creature";
        draft.power = "2";
        draft.toughness = "2";
        draft.newCard = true;
        return draft;
    }

    public static CardEditorDraft from(final PaperCard card) {
        final CardEditorDraft draft = new CardEditorDraft();
        final CardRules rules = card.getRules();
        final ICardFace face = rules.getMainPart();
        draft.name = face.getName();
        draft.manaCost = face.getManaCost().toString();
        draft.types = face.getType().toString();
        draft.power = face.getPower();
        draft.toughness = face.getToughness();
        for (final String keyword : face.getKeywords()) {
            if (CardCreatorKeywords.isManaged(keyword)) {
                draft.keywords.add(keyword.trim());
            }
        }
        draft.customSetCode = card.getEdition();
        draft.sourceSetCode = card.getEdition();
        draft.collectorNumber = card.getCollectorNumber();
        draft.rarity = card.getRarity() == null ? "C" : card.getRarity().toString();
        draft.sourceName = card.getName();
        final CardScriptInfo script = CardScriptInfo.getScriptFor(rules.getNormalizedName());
        draft.sourceScript = script == null ? null : script.getText();
        draft.newCard = false;
        return draft;
    }

    public static CardEditorDraft fromRules(final CardRules rules) {
        final CardEditorDraft draft = new CardEditorDraft();
        final ICardFace face = rules.getMainPart();
        draft.name = face.getName();
        draft.manaCost = face.getManaCost().toString();
        draft.types = face.getType().toString();
        draft.power = face.getPower();
        draft.toughness = face.getToughness();
        for (final String keyword : face.getKeywords()) {
            if (CardCreatorKeywords.isManaged(keyword)) draft.keywords.add(keyword.trim());
        }
        return draft;
    }

    public String getName() { return name; }
    public void setName(final String value) { name = value; }
    public String getManaCost() { return manaCost; }
    public void setManaCost(final String value) { manaCost = value; }
    public String getTypes() { return types; }
    public void setTypes(final String value) { types = value; }
    public String getPower() { return power; }
    public void setPower(final String value) { power = value; }
    public String getToughness() { return toughness; }
    public void setToughness(final String value) { toughness = value; }
    public Set<String> getKeywords() { return keywords; }
    public void setKeywords(final Iterable<String> values) {
        keywords.clear();
        if (values != null) {
            for (final String value : values) {
                if (value != null && !value.isBlank()) keywords.add(value.trim());
            }
        }
    }
    public String getCustomSetCode() { return customSetCode; }
    public void setCustomSetCode(final String value) { customSetCode = value; }
    public String getCollectorNumber() { return collectorNumber; }
    public void setCollectorNumber(final String value) { collectorNumber = value; }
    public String getRarity() { return rarity; }
    public void setRarity(final String value) { rarity = value; }
    public String getSourceName() { return sourceName; }
    public String getSourceSetCode() { return sourceSetCode; }
    public void setSourceIdentity(final String originalName, final String originalSetCode) {
        sourceName = originalName;
        sourceSetCode = originalSetCode;
    }
    public String getSourceScript() { return sourceScript; }
    public boolean isNewCard() { return newCard; }
    public void markAsNew() { newCard = true; }
    public void markAsSaved(final String savedName, final String savedScript) {
        sourceName = savedName;
        sourceScript = savedScript;
        newCard = false;
    }

    public void markAsSaved(final String savedName, final String savedSetCode, final String savedScript) {
        markAsSaved(savedName, savedScript);
        sourceSetCode = savedSetCode;
    }

    public String getTypeLine() {
        return Arrays.stream((types == null ? "" : types.trim()).split("\\s+"))
                .filter(s -> !s.isBlank()).collect(Collectors.joining(" "));
    }
}
