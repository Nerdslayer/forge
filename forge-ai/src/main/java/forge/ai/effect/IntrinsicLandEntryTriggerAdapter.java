package forge.ai.effect;

import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Uses the land opportunity prior for ordinary land-entry triggers, not only land plays. */
final class IntrinsicLandEntryTriggerAdapter {
    private static final Set<String> PARAMETERS = Set.of("Mode", "Origin", "Destination", "ValidCard",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary");

    private IntrinsicLandEntryTriggerAdapter() { }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters) {
        if (parameters == null || !PARAMETERS.containsAll(parameters.keySet())
                || !"ChangesZone".equals(parameters.get("Mode"))
                || !Set.of("Any", "Hand", "Library", "Graveyard", "Exile").contains(parameters.getOrDefault("Origin", "Any"))
                || !"Battlefield".equals(parameters.get("Destination"))
                || !"Battlefield".equals(parameters.getOrDefault("TriggerZones", "Battlefield"))) {
            return Optional.empty();
        }
        final IntrinsicEventTrigger.TurnScope scope = switch (parameters.getOrDefault("ValidCard", "")) {
        case "Land.YouCtrl" -> IntrinsicEventTrigger.TurnScope.CONTROLLER_TURN;
        case "Land.OppCtrl" -> IntrinsicEventTrigger.TurnScope.OPPONENT_TURN;
        case "Land" -> IntrinsicEventTrigger.TurnScope.ANY_TURN;
        default -> null;
        };
        // TODO: Separate ramp/fetch/extra-land and instant-speed entries from the coarse land
        // opportunity prior. Typed, conditional and batch land entries need population bindings.
        final double multiplier = switch (parameters.getOrDefault("Origin", "Any")) {
        case "Library" -> .35;
        case "Graveyard", "Exile" -> .15;
        case "Hand" -> .85;
        default -> 1;
        };
        return scope == null ? Optional.empty() : Optional.of(new IntrinsicEventTrigger(
                IntrinsicReferenceModel.EventType.LAND_PLAYED, scope, false, multiplier));
    }
}
