package forge.gui.cardcreator;

import forge.card.CardRules;

import java.util.ArrayList;
import java.util.List;

/** Validation result used by both structured and raw Workshop views. */
public record CardCreatorValidation(List<String> errors, List<String> warnings) {
    public CardCreatorValidation {
        errors = List.copyOf(errors);
        warnings = List.copyOf(warnings);
    }

    public boolean isValid() { return errors.isEmpty(); }

    public static CardCreatorValidation validate(final CardEditorDraft draft, final String script) {
        final List<String> errors = new ArrayList<>();
        final List<String> warnings = new ArrayList<>();
        if (draft.getName() == null || draft.getName().isBlank()) errors.add("A card name is required.");
        if (draft.getManaCost() == null || draft.getManaCost().isBlank()) errors.add("A mana cost is required.");
        if (draft.getTypes() == null || !draft.getTypes().toLowerCase().contains("creature")) {
            errors.add("The initial creator supports Creature cards only.");
        }
        if (draft.getPower() == null || !draft.getPower().matches("\\d+")) errors.add("Power must be a nonnegative integer.");
        if (draft.getToughness() == null || !draft.getToughness().matches("\\d+")) errors.add("Toughness must be a nonnegative integer.");
        if (draft.getCustomSetCode() == null || draft.getCustomSetCode().isBlank()) errors.add("A custom set is required.");
        if (draft.getName() != null && draft.getName().contains("//")) warnings.add("Multi-face names are not supported by the initial creator.");
        try {
            final CardRules rules = CardRules.fromScript(List.of(script.split("\\R", -1)));
            if (rules.getMainPart() == null || !rules.getMainPart().getType().isCreature()) {
                errors.add("The generated script is not a creature card.");
            }
        } catch (final RuntimeException ex) {
            errors.add("Forge could not parse the generated card script: " + ex.getMessage());
        }
        return new CardCreatorValidation(errors, warnings);
    }
}
