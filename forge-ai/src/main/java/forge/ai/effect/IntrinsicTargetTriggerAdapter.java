package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import forge.game.trigger.TriggerType;

/** Recognizes common battlefield triggers caused by a permanent or player becoming a target. */
final class IntrinsicTargetTriggerAdapter {
    private static final Set<String> SUPPORTED_PARAMETERS = Set.of(
            "Mode", "ValidSource", "ValidTarget", "FirstTime", "Valiant", "ActivationLimit",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> SUPPORTED_TARGETS = Set.of(
            "Card.Self", "Creature.Self", "You", "Opponent", "Player", "Creature", "Permanent",
            "Creature.YouCtrl", "Creature.OppCtrl", "Permanent.YouCtrl", "Permanent.OppCtrl");
    private static final Set<String> SUPPORTED_SOURCES = Set.of(
            "Spell", "Spell.YouCtrl", "Spell.OppCtrl", "SpellAbility",
            "SpellAbility.YouCtrl", "SpellAbility.OppCtrl", "Activated");

    private IntrinsicTargetTriggerAdapter() {
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters) {
        if (!supports(parameters)) {
            return Optional.empty();
        }
        return Optional.of(new IntrinsicEventTrigger(
                IntrinsicReferenceModel.EventType.BECAME_TARGET,
                IntrinsicEventTrigger.TurnScope.ANY_TURN, atMostOncePerTurn(parameters),
                occurrenceMultiplier(parameters)));
    }

    static boolean supports(final Map<String, String> parameters) {
        if (parameters == null || !SUPPORTED_PARAMETERS.containsAll(parameters.keySet())) {
            return false;
        }
        final TriggerType mode = EventTriggerParser.mode(parameters);
        if (mode != TriggerType.BecomesTarget && mode != TriggerType.BecomesTargetOnce) {
            return false;
        }
        if (parameters.containsKey("TriggerZones")
                && !"Battlefield".equalsIgnoreCase(parameters.get("TriggerZones"))) {
            return false;
        }
        if (parameters.containsKey("ValidTarget")
                && !SUPPORTED_TARGETS.contains(parameters.get("ValidTarget"))) {
            return false;
        }
        if (parameters.containsKey("ValidSource")
                && !SUPPORTED_SOURCES.contains(parameters.get("ValidSource"))) {
            return false;
        }
        if ((parameters.containsKey("FirstTime") && !isBoolean(parameters.get("FirstTime")))
                || (parameters.containsKey("Valiant") && !isBoolean(parameters.get("Valiant")))) {
            return false;
        }
        return !parameters.containsKey("ActivationLimit")
                || "1".equals(parameters.get("ActivationLimit"));
    }

    private static boolean atMostOncePerTurn(final Map<String, String> parameters) {
        return EventTriggerParser.mode(parameters) == TriggerType.BecomesTargetOnce
                || parameters.containsKey("FirstTime")
                || parameters.containsKey("Valiant")
                || parameters.containsKey("ActivationLimit");
    }

    private static double occurrenceMultiplier(final Map<String, String> parameters) {
        final double target = switch (parameters.getOrDefault("ValidTarget", "Player")) {
        case "Card.Self", "Creature.Self" -> .85;
        case "You", "Opponent" -> .55;
        case "Creature", "Permanent" -> .70;
        case "Creature.YouCtrl", "Permanent.YouCtrl", "Creature.OppCtrl", "Permanent.OppCtrl" -> .65;
        default -> 1;
        };
        final double source = switch (parameters.getOrDefault("ValidSource", "Any")) {
        case "Spell", "Spell.YouCtrl", "Spell.OppCtrl" -> .75;
        case "SpellAbility", "SpellAbility.YouCtrl", "SpellAbility.OppCtrl", "Activated" -> .60;
        default -> 1;
        };
        return target * source;
    }

    private static boolean isBoolean(final String value) {
        return "True".equalsIgnoreCase(value) || "False".equalsIgnoreCase(value);
    }
}
