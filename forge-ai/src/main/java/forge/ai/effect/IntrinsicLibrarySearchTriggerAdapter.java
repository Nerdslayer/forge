package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

import forge.game.trigger.TriggerType;

/** Recognizes common battlefield library-search triggers for intrinsic occurrence estimation. */
final class IntrinsicLibrarySearchTriggerAdapter {
    private static final Set<String> SUPPORTED_PARAMETERS = Set.of(
            "Mode", "ValidPlayer", "SearchOwnLibrary", "ActivationLimit", "Execute",
            "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> PLAYERS =
            Set.of("You", "Controller", "Opponent", "Player", "Player.Opponent");

    private IntrinsicLibrarySearchTriggerAdapter() {
    }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters) {
        if (!supports(parameters)) {
            return Optional.empty();
        }
        return Optional.of(new IntrinsicEventTrigger(
                IntrinsicReferenceModel.EventType.CARD_SEARCHED_OR_SELECTED,
                turnScope(parameters), parameters.containsKey("ActivationLimit"),
                occurrenceMultiplier(parameters)));
    }

    static boolean supports(final Map<String, String> parameters) {
        if (parameters == null || !SUPPORTED_PARAMETERS.containsAll(parameters.keySet())
                || !supportsBattlefield(parameters) || !supportsActivationLimit(parameters)
                || EventTriggerParser.mode(parameters) != TriggerType.SearchedLibrary) {
            return false;
        }
        if (parameters.containsKey("ValidPlayer")
                && !PLAYERS.contains(parameters.get("ValidPlayer"))) {
            return false;
        }
        return !parameters.containsKey("SearchOwnLibrary")
                || "True".equalsIgnoreCase(parameters.get("SearchOwnLibrary"));
    }

    private static IntrinsicEventTrigger.TurnScope turnScope(
            final Map<String, String> parameters) {
        final String player = parameters.get("ValidPlayer");
        if ("You".equalsIgnoreCase(player) || "Controller".equalsIgnoreCase(player)) {
            return IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN;
        }
        if ("Opponent".equalsIgnoreCase(player)
                || "Player.Opponent".equalsIgnoreCase(player)) {
            return IntrinsicEventTrigger.TurnScope.OPPONENT_TURN;
        }
        return IntrinsicEventTrigger.TurnScope.ANY_TURN;
    }

    private static double occurrenceMultiplier(final Map<String, String> parameters) {
        // SearchOwnLibrary is the common form. Searching another player's library is less common
        // and is intentionally kept conservative even when the player scope is broad.
        return parameters.containsKey("SearchOwnLibrary") ? 1 : .75;
    }

    // TODO: Add search-type, library-size, cause, and result-quality estimates once the reference
    // model can represent tutor, dig, mill-like, and opponent-library search populations.
    private static boolean supportsBattlefield(final Map<String, String> parameters) {
        return !parameters.containsKey("TriggerZones")
                || "Battlefield".equalsIgnoreCase(parameters.get("TriggerZones"));
    }

    private static boolean supportsActivationLimit(final Map<String, String> parameters) {
        return !parameters.containsKey("ActivationLimit")
                || "1".equals(parameters.get("ActivationLimit"));
    }
}
