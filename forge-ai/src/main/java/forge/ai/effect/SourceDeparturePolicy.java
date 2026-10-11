package forge.ai.effect;

import java.util.Map;
import java.util.Set;

/** Structural source-departure facts shared by occurrence and lost-future-value analysis. */
final class SourceDeparturePolicy {
    private SourceDeparturePolicy() { }

    static boolean containsDeparture(final AbilityOutcomeDescription outcome) {
        return outcome != null && (departs(outcome)
                || outcome.choices().stream().anyMatch(SourceDeparturePolicy::containsDeparture)
                || containsDeparture(outcome.next()));
    }

    static boolean necessarilyDeparts(final AbilityOutcomeDescription outcome, final boolean indestructible) {
        // TODO: Prove departure through choices, conditions and dynamic/group recipients. A
        // possible sacrifice option is not a guarantee that a modal ability is one-shot.
        return outcome != null && outcome.choices().isEmpty()
                && !outcome.parameters().keySet().stream().anyMatch(key -> Set.of(
                        "Condition", "CheckSVar", "IsPresent", "OptionalDecider").contains(key))
                && (departs(outcome) && !(indestructible && "Destroy".equals(outcome.api()))
                        || necessarilyDeparts(outcome.next(), indestructible));
    }

    private static boolean departs(final AbilityOutcomeDescription outcome) {
        final Map<String, String> parameters = outcome.parameters();
        final boolean self = "Self".equals(parameters.get("Defined"));
        return switch (outcome.api()) {
            case "Sacrifice" -> "Self".equals(parameters.getOrDefault("SacValid", "Self"))
                    && !parameters.containsKey("ValidTgts");
            case "Destroy" -> self;
            case "ChangeZone" -> self
                    && "Battlefield".equals(parameters.getOrDefault("Origin", "Battlefield"))
                    && parameters.containsKey("Destination")
                    && !"Battlefield".equals(parameters.get("Destination"));
            default -> false;
        };
    }
}
