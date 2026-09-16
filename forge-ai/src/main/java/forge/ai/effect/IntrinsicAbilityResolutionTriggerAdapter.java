package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import forge.game.trigger.TriggerType;

/** Recognizes common battlefield triggers caused by an ability resolving. */
final class IntrinsicAbilityResolutionTriggerAdapter {
    private static final Set<String> SUPPORTED_PARAMETERS = Set.of(
            "Mode", "ValidSource", "ValidSpellAbility", "ActivationLimit", "Execute",
            "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> SUPPORTED_SOURCES = Set.of(
            "Card.Self", "Permanent", "Permanent.YouCtrl", "Creature.YouCtrl", "Saga.YouCtrl");
    private static final Map<String, Double> SUPPORTED_ABILITIES = Map.of(
            "SpellAbility.ManaAbility", .50,
            "Ability.LastChapter", .25,
            "Triggered.LastChapter", .25);

    private IntrinsicAbilityResolutionTriggerAdapter() {
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters) {
        if (!supports(parameters)) {
            return Optional.empty();
        }
        return Optional.of(new IntrinsicEventTrigger(
                IntrinsicReferenceModel.EventType.ABILITY_RESOLVED,
                IntrinsicEventTrigger.TurnScope.ANY_TURN, atMostOncePerTurn(parameters),
                occurrenceMultiplier(parameters)));
    }

    static boolean supports(final Map<String, String> parameters) {
        if (parameters == null || EventTriggerParser.mode(parameters) != TriggerType.AbilityResolves
                || !SUPPORTED_PARAMETERS.containsAll(parameters.keySet())) {
            return false;
        }
        if (parameters.containsKey("TriggerZones")
                && !"Battlefield".equalsIgnoreCase(parameters.get("TriggerZones"))) {
            return false;
        }
        if (parameters.containsKey("ValidSource")
                && !SUPPORTED_SOURCES.contains(parameters.get("ValidSource"))) {
            return false;
        }
        if (parameters.containsKey("ValidSpellAbility")
                && !SUPPORTED_ABILITIES.containsKey(parameters.get("ValidSpellAbility"))) {
            return false;
        }
        return !parameters.containsKey("ActivationLimit")
                || "1".equals(parameters.get("ActivationLimit"));
    }

    private static boolean atMostOncePerTurn(final Map<String, String> parameters) {
        return parameters.containsKey("ActivationLimit");
    }

    private static double occurrenceMultiplier(final Map<String, String> parameters) {
        final String source = parameters.getOrDefault("ValidSource", "Any");
        final double sourceFactor = switch (source) {
        case "Card.Self" -> .90;
        case "Permanent.YouCtrl", "Creature.YouCtrl" -> .70;
        case "Saga.YouCtrl" -> .65;
        case "Permanent" -> .80;
        default -> 1;
        };
        final double abilityFactor = parameters.containsKey("ValidSpellAbility")
                ? SUPPORTED_ABILITIES.get(parameters.get("ValidSpellAbility")) : 1;
        return sourceFactor * abilityFactor;
    }
}
