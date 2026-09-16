package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import forge.game.trigger.TriggerType;

/** Recognizes bounded battlefield triggers caused by transformation or turning face up. */
final class IntrinsicStateChangeTriggerAdapter {
    // TODO: Add source-specific transformation/face-up likelihood without inspecting hidden faces.
    private static final Set<String> SUPPORTED_PARAMETERS = Set.of(
            "Mode", "ValidCard", "ValidCause", "ActivationLimit", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> SUPPORTED_CARDS = Set.of(
            "Card.Self", "Creature.Self", "Permanent", "Permanent.YouCtrl", "Creature.YouCtrl");
    private static final Set<String> SUPPORTED_CAUSES = Set.of(
            "Spell", "SpellAbility", "SpellAbility.YouCtrl", "SpellAbility.OppCtrl");

    private IntrinsicStateChangeTriggerAdapter() {
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters) {
        if (!supports(parameters)) {
            return Optional.empty();
        }
        final TriggerType mode = EventTriggerParser.mode(parameters);
        final IntrinsicReferenceModel.EventType eventType = mode == TriggerType.Transformed
                ? IntrinsicReferenceModel.EventType.TRANSFORMED
                : IntrinsicReferenceModel.EventType.TURNED_FACE_UP;
        return Optional.of(new IntrinsicEventTrigger(eventType,
                IntrinsicEventTrigger.TurnScope.ANY_TURN, atMostOncePerTurn(parameters),
                occurrenceMultiplier(parameters)));
    }

    static boolean supports(final Map<String, String> parameters) {
        if (parameters == null || !SUPPORTED_PARAMETERS.containsAll(parameters.keySet())) {
            return false;
        }
        final TriggerType mode = EventTriggerParser.mode(parameters);
        if (mode != TriggerType.Transformed && mode != TriggerType.TurnFaceUp) {
            return false;
        }
        if (parameters.containsKey("TriggerZones")
                && !"Battlefield".equalsIgnoreCase(parameters.get("TriggerZones"))) {
            return false;
        }
        if (parameters.containsKey("ValidCard")
                && !SUPPORTED_CARDS.contains(parameters.get("ValidCard"))) {
            return false;
        }
        if (parameters.containsKey("ValidCause")
                && (mode != TriggerType.TurnFaceUp
                    || !SUPPORTED_CAUSES.contains(parameters.get("ValidCause")))) {
            return false;
        }
        return !parameters.containsKey("ActivationLimit")
                || "1".equals(parameters.get("ActivationLimit"));
    }

    private static boolean atMostOncePerTurn(final Map<String, String> parameters) {
        return parameters.containsKey("ActivationLimit");
    }

    private static double occurrenceMultiplier(final Map<String, String> parameters) {
        final TriggerType mode = EventTriggerParser.mode(parameters);
        final double event = mode == TriggerType.Transformed ? .80 : .70;
        final double card = switch (parameters.getOrDefault("ValidCard", "Permanent")) {
        case "Card.Self", "Creature.Self" -> .90;
        case "Permanent.YouCtrl", "Creature.YouCtrl" -> .65;
        default -> 1;
        };
        final double cause = parameters.containsKey("ValidCause") ? .75 : 1;
        return event * card * cause;
    }
}
