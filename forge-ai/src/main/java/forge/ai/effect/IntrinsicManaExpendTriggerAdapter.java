package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import forge.game.trigger.TriggerType;

/** Recognizes fixed cumulative-mana thresholds used by {@code ManaExpend} triggers. */
final class IntrinsicManaExpendTriggerAdapter {
    private static final Set<String> SUPPORTED_PARAMETERS = Set.of(
            "Mode", "Amount", "Player", "TriggerZones", "Execute", "TriggerDescription",
            "Secondary");

    private IntrinsicManaExpendTriggerAdapter() {
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters) {
        if (!supports(parameters)) {
            return Optional.empty();
        }
        final int amount = Integer.parseInt(parameters.get("Amount"));
        final IntrinsicEventTrigger.TurnScope turnScope = switch (
                parameters.getOrDefault("Player", "You")) {
        case "You" -> IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN;
        case "Opponent" -> IntrinsicEventTrigger.TurnScope.OPPONENT_TURN;
        default -> IntrinsicEventTrigger.TurnScope.ANY_TURN;
        };
        return Optional.of(new IntrinsicEventTrigger(
                IntrinsicReferenceModel.EventType.MANA_EXPENDED, turnScope, true, 1, amount));
    }

    static boolean supports(final Map<String, String> parameters) {
        if (parameters == null || !SUPPORTED_PARAMETERS.containsAll(parameters.keySet())
                || EventTriggerParser.mode(parameters) != TriggerType.ManaExpend) {
            return false;
        }
        if (parameters.containsKey("TriggerZones")
                && !"Battlefield".equalsIgnoreCase(parameters.get("TriggerZones"))) {
            return false;
        }
        if (parameters.containsKey("Player")
                && !Set.of("You", "Opponent", "Player").contains(parameters.get("Player"))) {
            return false;
        }
        try {
            final int amount = Integer.parseInt(parameters.get("Amount"));
            // The default reference distribution includes a small tail through eight mana. A
            // higher threshold is left unsupported until the model has a longer-horizon tail.
            return amount > 0 && amount <= 8;
        } catch (final NumberFormatException ignored) {
            return false;
        }
    }
}
