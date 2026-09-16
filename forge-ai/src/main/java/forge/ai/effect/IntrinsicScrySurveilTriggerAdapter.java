package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import forge.game.trigger.TriggerType;

/** Recognizes common battlefield triggers caused by scrying or surveilling. */
final class IntrinsicScrySurveilTriggerAdapter {
    private static final Set<String> SUPPORTED_PARAMETERS = Set.of(
            "Mode", "ValidPlayer", "FirstTime", "ActivationLimit", "Execute",
            "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> SUPPORTED_PLAYERS = Set.of(
            "You", "Opponent", "Player", "Player.Opponent");

    private IntrinsicScrySurveilTriggerAdapter() {
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters) {
        if (!supports(parameters)) {
            return Optional.empty();
        }
        return Optional.of(new IntrinsicEventTrigger(
                IntrinsicReferenceModel.EventType.SCRIED_OR_SURVEILLED,
                turnScope(parameters), atMostOncePerTurn(parameters), occurrenceMultiplier(parameters)));
    }

    static boolean supports(final Map<String, String> parameters) {
        if (parameters == null || !SUPPORTED_PARAMETERS.containsAll(parameters.keySet())) {
            return false;
        }
        final TriggerType mode = EventTriggerParser.mode(parameters);
        if (mode != TriggerType.Scry && mode != TriggerType.Surveil) {
            return false;
        }
        if (parameters.containsKey("TriggerZones")
                && !"Battlefield".equalsIgnoreCase(parameters.get("TriggerZones"))) {
            return false;
        }
        if (parameters.containsKey("ValidPlayer")
                && !SUPPORTED_PLAYERS.contains(parameters.get("ValidPlayer"))) {
            return false;
        }
        if ((parameters.containsKey("FirstTime") && !isBoolean(parameters.get("FirstTime")))
                || parameters.containsKey("ActivationLimit")
                && !"1".equals(parameters.get("ActivationLimit"))) {
            return false;
        }
        return true;
    }

    private static IntrinsicEventTrigger.TurnScope turnScope(
            final Map<String, String> parameters) {
        return switch (parameters.getOrDefault("ValidPlayer", "Player")) {
        case "You" -> IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN;
        case "Opponent" -> IntrinsicEventTrigger.TurnScope.OPPONENT_TURN;
        default -> IntrinsicEventTrigger.TurnScope.ANY_TURN;
        };
    }

    private static boolean atMostOncePerTurn(final Map<String, String> parameters) {
        return parameters.containsKey("FirstTime") || parameters.containsKey("ActivationLimit");
    }

    private static double occurrenceMultiplier(final Map<String, String> parameters) {
        return "Player.Opponent".equals(parameters.get("ValidPlayer")) ? .85 : 1;
    }

    private static boolean isBoolean(final String value) {
        return "True".equalsIgnoreCase(value) || "False".equalsIgnoreCase(value);
    }
}
