package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Shared fixed draw interpretation; legality and resources are backend responsibilities. */
public record DrawOutcomeDescription(int amount, boolean controller) {
    public static Optional<DrawOutcomeDescription> parse(final String api, final Map<String, String> params) {
        // TODO: Dynamic amounts, targets, optional draws, replacements and remembered cards.
        if (!"Draw".equals(api) || !Set.of("DB", "AB", "SP", "Cost", "Defined", "NumCards",
                "SubAbility", "SpellDescription", "StackDescription").containsAll(params.keySet())) {
            return Optional.empty();
        }
        final String recipient = params.getOrDefault("Defined", "You");
        if (!Set.of("You", "Opponent").contains(recipient)) { return Optional.empty(); }
        try {
            return Optional.of(new DrawOutcomeDescription(Integer.parseInt(params.getOrDefault("NumCards", "1")),
                    "You".equals(recipient)));
        } catch (final NumberFormatException invalid) { return Optional.empty(); }
    }
}
