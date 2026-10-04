package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import forge.ai.effect.CardAbilityTraversal.AbilityDescription;

/** One actually-dealt damage total shared by all outcomes of a received-damage trigger. */
record IntrinsicReceivedDamageBinding(int amount) {
    private static final Set<String> PARAMETERS = Set.of("Mode", "ValidTarget", "CombatDamage",
            "DamageSource", "Execute", "TriggerZones", "TriggerDescription", "Secondary");

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters) {
        if (!"DamageDoneOnce".equals(parameters.get("Mode")) || !PARAMETERS.containsAll(parameters.keySet())
                || !Set.of("Card.Self", "Creature.Self", "Permanent.YouCtrl", "Creature.YouCtrl")
                    .contains(parameters.getOrDefault("ValidTarget", ""))
                || !"Battlefield".equals(parameters.getOrDefault("TriggerZones", "Battlefield"))
                || parameters.containsKey("DamageSource") && !"Any".equals(parameters.get("DamageSource"))
                || parameters.containsKey("CombatDamage") && !Set.of("True", "False").contains(parameters.get("CombatDamage"))) {
            return Optional.empty();
        }
        // DamageDoneOnce totals one recipient's simultaneous sources, not all recipients and
        // not all damage for a turn. Multiple batches/recipients can produce multiple triggers.
        // TODO: Distinct received-damage frequencies, recipient counts/types, incoming source
        // profiles, prevention/replacements, lethal-event LKI and joint amount/occurrence cases.
        // DamageDone/outgoing totals, amount/history filters and damaged-object outcome targets
        // need separate bindings; never infer a generic event amount outside this recognized scope.
        return Optional.of(new IntrinsicEventTrigger("True".equals(parameters.get("CombatDamage"))
                ? IntrinsicReferenceModel.EventType.COMBAT_DAMAGE : IntrinsicReferenceModel.EventType.DAMAGE_DEALT,
                IntrinsicEventTrigger.TurnScope.ANY_TURN, false, 1));
    }

    static Optional<List<WeightedValue<IntrinsicReceivedDamageBinding>>> cases(final AbilityDescription ability,
            final Map<String, String> variables, final IntrinsicReferenceModel model) {
        if (ability.origin() != CardAbilityTraversal.Origin.TRIGGER
                || describe(AbilityOptionality.triggerParameters(ability.parameters())).isEmpty()
                || !variables.values().stream().anyMatch(IntrinsicDamageAmountBinding::quantity)
                    && !ability.parameters().values().stream().anyMatch(IntrinsicDamageAmountBinding::quantity)
                    && !IntrinsicDamageAmountBinding.needed(ability.outcome())) { return Optional.empty(); }
        final var entries = model.quantities().distribution(IntrinsicReferenceQuantities.Quantity.RECEIVED_DAMAGE_AMOUNT).entries();
        final double positive = entries.stream().filter(entry -> entry.value() > 0).mapToDouble(WeightedValue::weight).sum();
        if (positive == 0) { return Optional.of(List.of()); }
        return Optional.of(entries.stream().filter(entry -> entry.value() > 0)
                .map(entry -> new WeightedValue<>(new IntrinsicReceivedDamageBinding(entry.value()), entry.weight() / positive)).toList());
    }

    Map<String, String> bindVariables(final Map<String, String> variables) {
        return new IntrinsicDamageAmountBinding(amount).bindVariables(variables);
    }

    AbilityOutcomeDescription bindOutcome(final AbilityOutcomeDescription node) {
        return new IntrinsicDamageAmountBinding(amount).bindOutcome(node);
    }
}
