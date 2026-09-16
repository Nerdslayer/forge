package forge.ai.effect;

import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import forge.game.trigger.TriggerType;

/** Recognizes common battlefield triggers caused by another ability triggering. */
final class IntrinsicAbilityTriggeredTriggerAdapter {
    // TODO: Model exact causes, copied abilities, stack timing, and source-specific willingness.
    private static final Set<String> SUPPORTED_PARAMETERS = Set.of(
            "Mode", "ValidMode", "ValidDestination", "ValidSpellAbility", "ValidSource",
            "TriggeredOwnAbility", "ActivationLimit", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> SUPPORTED_SOURCES = Set.of(
            "Card.Self", "Permanent", "Permanent.YouCtrl", "Creature.YouCtrl",
            "Creature.OppCtrl", "Saga.YouCtrl");
    private static final Set<String> SUPPORTED_MODES = Set.of(
            "Attacks", "Blocks", "ChangesZone", "ChangesZoneAll", "CounterAdded",
            "DamageDone", "LifeGained", "LifeLost", "TokenCreated", "Taps", "Untaps");
    private static final Set<String> SUPPORTED_DESTINATIONS = Set.of(
            "Battlefield", "Graveyard", "Hand", "Exile", "Library");
    private static final Map<String, Double> SUPPORTED_ABILITY_FILTERS = Map.of(
            "Triggered.LastChapter", .25,
            "Ability.LastChapter", .25,
            "SpellAbility.ManaAbility", .50);

    private IntrinsicAbilityTriggeredTriggerAdapter() {
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters) {
        if (!supports(parameters)) {
            return Optional.empty();
        }
        return Optional.of(new IntrinsicEventTrigger(
                IntrinsicReferenceModel.EventType.ABILITY_TRIGGERED,
                IntrinsicEventTrigger.TurnScope.ANY_TURN, atMostOncePerTurn(parameters),
                occurrenceMultiplier(parameters)));
    }

    static boolean supports(final Map<String, String> parameters) {
        if (parameters == null || EventTriggerParser.mode(parameters) != TriggerType.AbilityTriggered
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
                && !SUPPORTED_ABILITY_FILTERS.containsKey(parameters.get("ValidSpellAbility"))) {
            return false;
        }
        if (parameters.containsKey("ValidMode")
                && !hasSupportedValues(parameters.get("ValidMode"), SUPPORTED_MODES)) {
            return false;
        }
        if (parameters.containsKey("ValidDestination")
                && !hasSupportedValues(parameters.get("ValidDestination"), SUPPORTED_DESTINATIONS)) {
            return false;
        }
        if (parameters.containsKey("TriggeredOwnAbility")
                && !isBoolean(parameters.get("TriggeredOwnAbility"))) {
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
        case "Creature.OppCtrl" -> .55;
        case "Saga.YouCtrl" -> .65;
        case "Permanent" -> .80;
        default -> 1;
        };
        final double modeFactor = parameters.containsKey("ValidMode") ? .70 : 1;
        final double destinationFactor = parameters.containsKey("ValidDestination") ? .75 : 1;
        final double ownFactor = "True".equalsIgnoreCase(parameters.get("TriggeredOwnAbility"))
                ? .75 : 1;
        final double abilityFactor = parameters.containsKey("ValidSpellAbility")
                ? SUPPORTED_ABILITY_FILTERS.get(parameters.get("ValidSpellAbility")) : 1;
        return sourceFactor * modeFactor * destinationFactor * ownFactor * abilityFactor;
    }

    private static boolean hasSupportedValues(final String encoded, final Set<String> supported) {
        return encoded != null && Arrays.stream(encoded.split(","))
                .allMatch(value -> supported.contains(value));
    }

    private static boolean isBoolean(final String value) {
        return "True".equalsIgnoreCase(value) || "False".equalsIgnoreCase(value);
    }
}
