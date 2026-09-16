package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import forge.game.trigger.TriggerType;

/** Recognizes the first conservative slice of intrinsic self-untap triggers. */
final class IntrinsicUntapTriggerAdapter {
    private static final Set<String> SUPPORTED_PARAMETERS = Set.of(
            "Mode", "ValidCard", "ActivationLimit", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");

    private IntrinsicUntapTriggerAdapter() {
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters) {
        if (!supports(parameters)) {
            return Optional.empty();
        }
        return Optional.of(new IntrinsicEventTrigger(
                IntrinsicReferenceModel.EventType.UNTAPPED,
                IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN,
                parameters.containsKey("ActivationLimit"), 1));
    }

    static boolean supports(final Map<String, String> parameters) {
        if (parameters == null || EventTriggerParser.mode(parameters) != TriggerType.Untaps
                || !SUPPORTED_PARAMETERS.containsAll(parameters.keySet())
                || !"Card.Self".equals(parameters.get("ValidCard"))) {
            return false;
        }
        if (parameters.containsKey("TriggerZones")
                && !"Battlefield".equalsIgnoreCase(parameters.get("TriggerZones"))) {
            return false;
        }
        return !parameters.containsKey("ActivationLimit")
                || "1".equals(parameters.get("ActivationLimit"));
    }
}
