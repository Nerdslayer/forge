package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import forge.game.trigger.TriggerType;

/** Recognizes the first conservative slice of intrinsic activated-ability triggers. */
final class IntrinsicAbilityCastTriggerAdapter {
    private static final Set<String> SUPPORTED_PARAMETERS = Set.of(
            "Mode", "ValidCard", "ValidSA", "ValidActivatingPlayer", "ActivationLimit",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> SUPPORTED_PLAYER_FILTERS = Set.of(
            "You", "Opponent", "Player", "Player.Opponent");
    private static final Set<String> SUPPORTED_ABILITY_FILTERS = Set.of(
            "SpellAbility.!ManaAbility", "Activated.!ManaAbility");

    private IntrinsicAbilityCastTriggerAdapter() {
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters) {
        if (!supports(parameters)) {
            return Optional.empty();
        }
        return Optional.of(new IntrinsicEventTrigger(
                IntrinsicReferenceModel.EventType.ABILITY_CAST,
                turnScope(parameters), atMostOncePerTurn(parameters), occurrenceMultiplier(parameters)));
    }

    static boolean supports(final Map<String, String> parameters) {
        if (parameters == null || EventTriggerParser.mode(parameters) != TriggerType.AbilityCast
                || !SUPPORTED_PARAMETERS.containsAll(parameters.keySet())
                || !SUPPORTED_PLAYER_FILTERS.contains(
                        parameters.getOrDefault("ValidActivatingPlayer", "Player"))) {
            return false;
        }
        if (parameters.containsKey("TriggerZones")
                && !"Battlefield".equalsIgnoreCase(parameters.get("TriggerZones"))) {
            return false;
        }
        if (parameters.containsKey("ValidSA")
                && !SUPPORTED_ABILITY_FILTERS.contains(parameters.get("ValidSA"))) {
            return false;
        }
        // A source-specific or permanent-specific ability population needs live board information.
        // The broad permanent filter is safe because the reference rate already represents a
        // population of activated abilities rather than a guaranteed action by this source.
        if (parameters.containsKey("ValidCard")
                && !"Permanent.inZoneBattlefield".equals(parameters.get("ValidCard"))) {
            return false;
        }
        return !parameters.containsKey("ActivationLimit")
                || "1".equals(parameters.get("ActivationLimit"));
    }

    private static IntrinsicEventTrigger.TurnScope turnScope(
            final Map<String, String> parameters) {
        return switch (parameters.getOrDefault("ValidActivatingPlayer", "Player")) {
        case "You" -> IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN;
        case "Opponent" -> IntrinsicEventTrigger.TurnScope.OPPONENT_TURN;
        default -> IntrinsicEventTrigger.TurnScope.ANY_TURN;
        };
    }

    private static boolean atMostOncePerTurn(final Map<String, String> parameters) {
        return parameters.containsKey("ActivationLimit");
    }

    private static double occurrenceMultiplier(final Map<String, String> parameters) {
        return parameters.containsKey("ValidSA") ? .85 : 1;
    }
}
