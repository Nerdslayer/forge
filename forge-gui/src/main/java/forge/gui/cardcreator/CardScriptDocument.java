package forge.gui.cardcreator;

import forge.card.CardRules;
import forge.card.ICardFace;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Line-preserving editor for the basic records owned by the structured creator. */
public final class CardScriptDocument {
    private final List<String> lines;
    private final boolean generatedOracle;

    private CardScriptDocument(final List<String> lines, final boolean generatedOracle) {
        this.lines = new ArrayList<>(lines);
        this.generatedOracle = generatedOracle;
    }

    public static CardScriptDocument fromText(final String text) {
        final String safeText = text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n');
        final List<String> lines = new ArrayList<>(List.of(safeText.split("\\n", -1)));
        if (!lines.isEmpty() && lines.get(lines.size() - 1).isEmpty()) lines.remove(lines.size() - 1);
        return new CardScriptDocument(lines, false);
    }

    public static CardScriptDocument fromDraft(final CardEditorDraft draft) {
        final List<String> lines = new ArrayList<>();
        lines.add("Name:" + nullToEmpty(draft.getName()));
        lines.add("ManaCost:" + nullToEmpty(draft.getManaCost()));
        lines.add("Types:" + nullToEmpty(draft.getTypes()));
        if (draft.isCreature() && isInteger(draft.getPower()) && isInteger(draft.getToughness())) {
            lines.add("PT:" + draft.getPower() + "/" + draft.getToughness());
        }
        for (final String keyword : draft.getKeywords()) lines.add("K:" + keyword);
        lines.add("Oracle:" + String.join(", ", draft.getKeywords()));
        return new CardScriptDocument(lines, true);
    }

    /**
     * Reconstructs a usable source script when the original file cannot be located. This keeps
     * raw abilities available to preview and evaluation for bundled cards.
     * TODO: Preserve additional faces and specialized/functional variants in this fallback.
     */
    public static CardScriptDocument fromRules(final CardRules rules) {
        final ICardFace face = rules.getMainPart();
        final List<String> lines = new ArrayList<>();
        lines.add("Name:" + nullToEmpty(face.getName()));
        lines.add("ManaCost:" + nullToEmpty(face.getManaCost() == null ? null : face.getManaCost().toString()));
        lines.add("Types:" + nullToEmpty(face.getType() == null ? null : face.getType().toString()));
        if (face.getType() != null && face.getType().isCreature()
                && face.getPower() != null && face.getToughness() != null) {
            lines.add("PT:" + face.getPower() + "/" + face.getToughness());
        }
        if (!isBlank(face.getInitialLoyalty())) lines.add("Loyalty:" + face.getInitialLoyalty());
        if (!isBlank(face.getDefense())) lines.add("Defense:" + face.getDefense());
        appendPrefixed(lines, "SVar:", face.getVariables(), entry -> entry.getKey() + ":" + entry.getValue());
        appendPrefixed(lines, "R:", face.getReplacements(), value -> value);
        appendPrefixed(lines, "S:", face.getStaticAbilities(), value -> value);
        appendPrefixed(lines, "T:", face.getTriggers(), value -> value);
        appendPrefixed(lines, "A:", face.getAbilities(), value -> value);
        appendPrefixed(lines, "K:", face.getKeywords(), value -> value);
        appendPrefixed(lines, "Draft:", face.getDraftActions(), value -> value);
        appendPrefixed(lines, "DeckRule:", face.getDeckRules(), value -> value);
        if (!isBlank(face.getNonAbilityText())) lines.add("Text:" + face.getNonAbilityText());
        lines.add("Oracle:" + nullToEmpty(face.getOracleText()));
        return new CardScriptDocument(lines, false);
    }

    /** Applies only the fields owned by the structured form and preserves all other records. */
    public void apply(final CardEditorDraft draft) {
        replaceFirstOrAdd("Name", "Name:" + nullToEmpty(draft.getName()), 0);
        replaceFirstOrAdd("ManaCost", "ManaCost:" + nullToEmpty(draft.getManaCost()), 1);
        replaceFirstOrAdd("Types", "Types:" + nullToEmpty(draft.getTypes()), 2);
        removeLine("PT");
        if (draft.isCreature() && isInteger(draft.getPower()) && isInteger(draft.getToughness())) {
            int insertAt = indexOfPrefix("Types:");
            if (insertAt < 0) insertAt = Math.min(2, lines.size() - 1);
            lines.add(Math.min(lines.size(), insertAt + 1), "PT:" + draft.getPower() + "/" + draft.getToughness());
        }

        final List<String> unmanaged = new ArrayList<>();
        for (final String line : lines) {
            if (line.regionMatches(true, 0, "K:", 0, 2)) {
                final String keyword = line.substring(2).trim();
                if (CardCreatorKeywords.isManaged(keyword)) continue;
            }
            unmanaged.add(line);
        }
        lines.clear();
        lines.addAll(unmanaged);
        int insertAt = indexOfPrefix("PT:");
        if (insertAt < 0) insertAt = indexOfPrefix("Types:");
        insertAt = Math.min(lines.size(), insertAt + 1);
        int offset = 0;
        for (final String keyword : draft.getKeywords()) lines.add(insertAt + offset++, "K:" + keyword);
        if (generatedOracle) {
            // TODO: Once arbitrary abilities are editable, only replace Oracle text generated by
            // this form; preserving user-authored Oracle text is required for richer cards.
            final String oracle = "Oracle:" + String.join(", ", draft.getKeywords());
            final int oracleIndex = indexOfPrefix("Oracle:");
            if (oracleIndex >= 0) lines.set(oracleIndex, oracle);
            else lines.add(oracle);
        }
    }

    public String getText() {
        return String.join("\n", lines) + (lines.isEmpty() ? "" : "\n");
    }

    private void replaceFirstOrAdd(final String key, final String value, final int preferredIndex) {
        final int index = indexOfPrefix(key + ":");
        if (index >= 0) lines.set(index, value);
        else lines.add(Math.min(preferredIndex, lines.size()), value);
    }

    private void removeLine(final String key) {
        lines.removeIf(line -> line.regionMatches(true, 0, key + ":", 0, key.length() + 1));
    }

    private int indexOfPrefix(final String prefix) {
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT))) return i;
        }
        return -1;
    }

    private static <T> void appendPrefixed(final List<String> lines, final String prefix,
            final Iterable<T> values, final java.util.function.Function<T, String> formatter) {
        if (values == null) return;
        for (final T value : values) {
            if (value != null) lines.add(prefix + formatter.apply(value));
        }
    }

    private static String nullToEmpty(final String value) { return value == null ? "" : value.trim(); }
    private static boolean isBlank(final String value) { return value == null || value.isBlank(); }
    private static boolean isInteger(final String value) { return value != null && value.trim().matches("-?\\d+"); }
}
