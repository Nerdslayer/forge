package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import forge.game.trigger.TriggerType;

/** Recognizes common battlefield triggers caused by a permanent changing controller. */
final class IntrinsicControlChangeTriggerAdapter {
    private static final Set<String> SUPPORTED_PARAMETERS = Set.of(
            "Mode", "ValidCard", "ValidOriginalController", "ActivationLimit", "Execute",
            "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> SUPPORTED_CARDS = Set.of(
            "Card.Self", "Creature", "Creature.YouCtrl", "Creature.OppCtrl", "Permanent",
            "Permanent.YouCtrl", "Permanent.OppCtrl");
    private static final Set<String> SUPPORTED_CONTROLLERS = Set.of(
            "You", "Opponent", "Player", "Player.Opponent");

    private IntrinsicControlChangeTriggerAdapter() {
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters) {
        if (!supports(parameters)) {
            return Optional.empty();
        }
        return Optional.of(new IntrinsicEventTrigger(
                IntrinsicReferenceModel.EventType.CONTROL_CHANGED,
                IntrinsicEventTrigger.TurnScope.ANY_TURN, atMostOncePerTurn(parameters),
                occurrenceMultiplier(parameters)));
    }

    static boolean supports(final Map<String, String> parameters) {
        if (parameters == null || EventTriggerParser.mode(parameters) != TriggerType.ChangesController
                || !SUPPORTED_PARAMETERS.containsAll(parameters.keySet())) {
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
        if (parameters.containsKey("ValidOriginalController")
                && !SUPPORTED_CONTROLLERS.contains(parameters.get("ValidOriginalController"))) {
            return false;
        }
        // TODO: Add exchanges, temporary control, player control, and source-specific movement.
        return !parameters.containsKey("ActivationLimit")
                || "1".equals(parameters.get("ActivationLimit"));
    }

    private static boolean atMostOncePerTurn(final Map<String, String> parameters) {
        return parameters.containsKey("ActivationLimit");
    }

    private static double occurrenceMultiplier(final Map<String, String> parameters) {
        final String card = parameters.getOrDefault("ValidCard", "Permanent");
        final double cardFactor = switch (card) {
        case "Card.Self" -> .90;
        case "Creature", "Permanent" -> .65;
        case "Creature.YouCtrl", "Permanent.YouCtrl" -> .70;
        case "Creature.OppCtrl", "Permanent.OppCtrl" -> .55;
        default -> 1;
        };
        final String controller = parameters.getOrDefault("ValidOriginalController", "Player");
        final double controllerFactor = "Player.Opponent".equals(controller) ? .85 : 1;
        return cardFactor * controllerFactor;
    }
}
