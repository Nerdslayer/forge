package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import forge.ai.effect.CardAbilityTraversal.AbilityDescription;

/** Conditional attacker counts, not a prediction that every reference creature attacks. */
final class IntrinsicAttackCountBinding {
    private IntrinsicAttackCountBinding() { }

    static Optional<List<WeightedValue<IntrinsicEventScalarBinding>>> cases(final AbilityDescription ability,
            final Map<String, String> variables, final IntrinsicReferenceModel model) {
        final var parameters = AbilityOptionality.triggerParameters(ability.parameters());
        if (ability.origin() != CardAbilityTraversal.Origin.TRIGGER
                || !"AttackersDeclared".equals(parameters.get("Mode"))
                || !IntrinsicCombatTriggerAdapter.supports(parameters)
                || parameters.containsKey("ValidAttackers") || parameters.containsKey("AttackedTarget")) {
            return Optional.empty();
        }
        final String controller = switch (parameters.getOrDefault("AttackingPlayer", "")) {
        case "You", "Controller" -> "YouCtrl";
        case "Opponent", "Player.Other" -> "OppCtrl";
        default -> null;
        };
        if (controller == null) { return Optional.empty(); }
        final var scalar = new IntrinsicEventScalarBinding("Count$Valid Creature.attacking+" + controller, 0);
        if (!variables.values().stream().anyMatch(scalar::matches)
                && !parameters.values().stream().anyMatch(scalar::matches) && !scalar.needed(ability.outcome())) {
            return Optional.empty();
        }
        final var entries = model.quantities().distribution(IntrinsicReferenceQuantities.Quantity.ATTACKING_CREATURES).entries();
        final double positive = entries.stream().filter(entry -> entry.value() > 0).mapToDouble(WeightedValue::weight).sum();
        if (positive == 0) { return Optional.of(List.of()); }
        // TODO: Filtered/subset counts, one-target declarations, blockers, attack restrictions,
        // extra combats and joint population/amount/occurrence predictions need richer event state.
        // New event scopes do not inherit this count; independent distributions are intentional.
        return Optional.of(entries.stream().filter(entry -> entry.value() > 0)
                .map(entry -> new WeightedValue<>(new IntrinsicEventScalarBinding(scalar.expression(), entry.value()),
                        entry.weight() / positive)).toList());
    }
}
