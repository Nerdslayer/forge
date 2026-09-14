package forge.gui.cardcreator;

import forge.card.CardRules;
import forge.card.CardType;
import forge.card.ICardFace;
import forge.gui.card.CardScriptInfo;
import forge.item.PaperCard;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Mutable structured fields for one Card Creator draft. */
public final class CardEditorDraft {
    private static final Pattern SIMPLE_COMPACT_MANA_COST = Pattern.compile("^(\\d+)([WUBRGCS]+)$", Pattern.CASE_INSENSITIVE);

    private String name;
    private String manaCost;
    private String power;
    private String toughness;
    private final Set<String> cardTypes = new LinkedHashSet<>();
    private final Set<String> supertypes = new LinkedHashSet<>();
    private final Set<String> subtypes = new LinkedHashSet<>();
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
        draft.setCardTypes(Set.of(CardType.CoreType.Creature.name()));
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
        draft.setTypes(face.getType().toString());
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
        draft.setTypes(face.getType().toString());
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
    /**
     * Accepts Forge's space-separated mana syntax and the compact simple form used by the
     * structured editor, such as {@code 2B} for two generic and one black mana. Complex hybrid,
     * Phyrexian, split, and other costs remain available through the raw script editor.
     */
    public void setManaCost(final String value) { manaCost = normalizeManaCost(value); }
    /** Returns the canonical Forge type line assembled from all selected type components. */
    public String getTypes() { return getTypeLine(); }

    /** Imports a complete Forge type line for parsed cards and raw-script synchronization. */
    public void setTypes(final String value) { parseTypeLine(value); }

    public Set<String> getCardTypes() { return Collections.unmodifiableSet(cardTypes); }
    public void setCardTypes(final Iterable<String> values) {
        cardTypes.clear();
        if (values != null) {
            for (final String value : values) {
                final CardType.CoreType type = CardType.CoreType.getEnum(value);
                if (type != null) cardTypes.add(type.name());
            }
        }
    }

    public Set<String> getSupertypes() { return Collections.unmodifiableSet(supertypes); }
    public void setSupertypes(final Iterable<String> values) {
        supertypes.clear();
        if (values != null) {
            for (final String value : values) {
                final CardType.Supertype type = CardType.Supertype.getEnum(value);
                if (type != null) supertypes.add(type.name());
            }
        }
    }

    public String getSubtypeLine() { return String.join(" ", subtypes); }
    public void setSubtypes(final String value) {
        subtypes.clear();
        if (value != null) {
            // TODO: Validate subtypes against the selected card types and support special multiword subtype forms.
            for (final String subtype : value.trim().split("\\s+")) {
                if (!subtype.isBlank()) subtypes.add(subtype);
            }
        }
    }

    public boolean isCreature() { return cardTypes.contains(CardType.CoreType.Creature.name()); }
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
        final StringBuilder result = new StringBuilder();
        appendSelectedTypes(result, CardType.Supertype.values(), supertypes);
        appendSelectedTypes(result, CardType.CoreType.values(), cardTypes);
        if (!subtypes.isEmpty()) {
            if (result.length() > 0) result.append(" ");
            result.append("- ").append(getSubtypeLine());
        }
        return result.toString();
    }

    private void parseTypeLine(final String value) {
        cardTypes.clear();
        supertypes.clear();
        subtypes.clear();
        final String typeLine = value == null ? "" : value.trim();
        if (typeLine.isEmpty()) return;

        final String normalized = typeLine.replace('—', '-');
        final String[] sections = normalized.split("\\s+-\\s+", 2);
        parseTypeTokens(sections[0], false);
        if (sections.length > 1) parseTypeTokens(sections[1], true);
    }

    private void parseTypeTokens(final String text, final boolean subtypeSection) {
        for (final String token : text.trim().split("\\s+")) {
            if (token.isBlank()) continue;
            final CardType.CoreType cardType = CardType.CoreType.getEnum(token);
            final CardType.Supertype supertype = CardType.Supertype.getEnum(token);
            if (!subtypeSection && cardType != null) {
                cardTypes.add(cardType.name());
            } else if (!subtypeSection && supertype != null) {
                supertypes.add(supertype.name());
            } else {
                subtypes.add(token);
            }
        }
    }

    private static <E extends Enum<E>> void appendSelectedTypes(final StringBuilder result,
            final E[] allTypes, final Set<String> selectedTypes) {
        for (final E type : allTypes) {
            if (selectedTypes.contains(type.name())) {
                if (result.length() > 0) result.append(" ");
                result.append(type.name());
            }
        }
    }

    private static String normalizeManaCost(final String value) {
        if (value == null) return null;
        final String trimmed = value.trim();
        final Matcher matcher = SIMPLE_COMPACT_MANA_COST.matcher(trimmed);
        if (!matcher.matches()) return value;

        final StringBuilder result = new StringBuilder(matcher.group(1));
        for (final char symbol : matcher.group(2).toUpperCase(Locale.ROOT).toCharArray()) {
            result.append(' ').append(symbol);
        }
        return result.toString();
    }
}
