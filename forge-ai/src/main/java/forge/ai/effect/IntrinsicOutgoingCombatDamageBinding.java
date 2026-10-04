package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import forge.ai.effect.CardAbilityTraversal.AbilityDescription;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;

/** Source-specific player combat hits, distinct from an arbitrary incoming damage total. */
final class IntrinsicOutgoingCombatDamageBinding {
    private static final Set<String> PARAMETERS = Set.of("Mode", "ValidSource", "ValidTarget", "CombatDamage",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary");

    private IntrinsicOutgoingCombatDamageBinding() { }

    static Optional<AbilityDescription> playerComponent(final AbilityDescription ability) {
        if (ability.origin() != CardAbilityTraversal.Origin.TRIGGER
                || !Set.of("Player,Battle", "Battle,Player").contains(ability.parameters().getOrDefault("ValidTarget", ""))) {
            return Optional.empty();
        }
        final var parameters = new java.util.LinkedHashMap<>(ability.parameters());
        parameters.put("ValidTarget", "Player");
        if (describe(AbilityOptionality.triggerParameters(parameters), null).isEmpty()) { return Optional.empty(); }
        return Optional.of(new AbilityDescription(ability.path(), ability.origin(), ability.provenance(), parameters, ability.outcome()));
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters, final PermanentProfile source) {
        if (!PARAMETERS.containsAll(parameters.keySet()) || !"DamageDone".equals(parameters.get("Mode"))
                || !"True".equals(parameters.get("CombatDamage"))
                || !Set.of("Card.Self", "Creature.Self").contains(parameters.getOrDefault("ValidSource", ""))
                || !Set.of("Player", "Opponent", "Player.Opponent").contains(parameters.getOrDefault("ValidTarget", ""))
                || !"Battlefield".equals(parameters.getOrDefault("TriggerZones", "Battlefield"))) { return Optional.empty(); }
        double multiplier = 1;
        if (source != null) {
            if (source.power() <= 0 || !Set.of(PermanentKind.CREATURE, PermanentKind.TOKEN).contains(source.kind())) { multiplier = 0; }
            else if (source.keywords().stream().anyMatch("Double strike"::equalsIgnoreCase)) { multiplier = 2; }
        }
        // A successful nontrample player hit deals full power. Trample's partial hits get separate
        // amount cases below. Double strike creates two damage events, not one doubled amount.
        // TODO: Evasion-specific hit rates, split trample/double-strike combat, prevention, attack
        // restrictions, incoming blockers, noncombat production and battle reference populations.
        return Optional.of(new IntrinsicEventTrigger(IntrinsicReferenceModel.EventType.COMBAT_DAMAGE,
                IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN, false, multiplier));
    }

    static Optional<List<WeightedValue<IntrinsicDamageAmountBinding>>> cases(final AbilityDescription ability,
            final Map<String, String> variables, final PermanentProfile source) {
        if (ability.origin() != CardAbilityTraversal.Origin.TRIGGER
                || describe(AbilityOptionality.triggerParameters(ability.parameters()), source).isEmpty()
                || !variables.values().stream().anyMatch(IntrinsicDamageAmountBinding::quantity)
                    && !IntrinsicDamageAmountBinding.needed(ability.outcome())) { return Optional.empty(); }
        return Optional.of(amounts(source));
    }

    static List<WeightedValue<IntrinsicDamageAmountBinding>> amounts(final PermanentProfile source) {
        if (source.power() <= 0 || !Set.of(PermanentKind.CREATURE, PermanentKind.TOKEN).contains(source.kind())) { return List.of(); }
        if (source.keywords().stream().noneMatch("Trample"::equalsIgnoreCase)) {
            return List.of(new WeightedValue<>(new IntrinsicDamageAmountBinding(source.power()), 1));
        }
        // Conditional on connecting, favor a full hit but retain small/partial trample spillover.
        // This is a bounded intrinsic prior, not predicted combat damage or capped player life.
        return List.of(new WeightedValue<>(new IntrinsicDamageAmountBinding(1), .20),
                new WeightedValue<>(new IntrinsicDamageAmountBinding(Math.max(1, source.power() / 2)), .30),
                new WeightedValue<>(new IntrinsicDamageAmountBinding(source.power()), .50));
    }
}
