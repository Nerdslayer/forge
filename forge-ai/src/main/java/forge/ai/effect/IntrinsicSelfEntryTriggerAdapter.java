package forge.ai.effect;

import java.util.Map;
import java.util.Set;

/** Identifies a one-time entry benefit, rather than a recurring battlefield opportunity. */
final class IntrinsicSelfEntryTriggerAdapter {
    private static final Set<String> SELF_FILTERS = Set.of("Card.Self", "Permanent.Self",
            "Creature.Self", "Artifact.Self", "Enchantment.Self", "Planeswalker.Self", "Land.Self");
    private static final Set<String> PARAMETERS = Set.of("Mode", "Origin", "Destination",
            "ValidCard", "Execute", "TriggerZones", "TriggerDescription", "Secondary");

    private IntrinsicSelfEntryTriggerAdapter() { }

    static boolean isSelfEntry(final Map<String, String> parameters) {
        return "ChangesZone".equals(parameters.get("Mode"))
                && "Battlefield".equalsIgnoreCase(parameters.get("Destination"))
                && SELF_FILTERS.contains(parameters.getOrDefault("ValidCard", ""));
    }

    static boolean supports(final Map<String, String> parameters) {
        if (!AbilityOptionality.trigger(parameters).supported()) { return false; }
        return supportsEvent(AbilityOptionality.triggerParameters(parameters));
    }

    private static boolean supportsEvent(final Map<String, String> parameters) {
        // TODO: Model conditional/payment-dependent ETBs, restricted origins, cast/history predicates,
        // entry replacements, and blink/re-entry opportunities. Other-creature ETBs use their
        // separate entry adapter/population; this adapter must not use recurring survival rates.
        return isSelfEntry(parameters)
                && PARAMETERS.containsAll(parameters.keySet())
                && "Any".equalsIgnoreCase(parameters.getOrDefault("Origin", "Any"))
                && "Battlefield".equalsIgnoreCase(
                        parameters.getOrDefault("TriggerZones", "Battlefield"));
    }
}
