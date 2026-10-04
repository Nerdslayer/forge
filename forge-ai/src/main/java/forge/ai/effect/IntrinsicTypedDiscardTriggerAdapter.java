package forge.ai.effect;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Coarse card-category eligibility for individual discards, without reading a live hand. */
final class IntrinsicTypedDiscardTriggerAdapter {
    private static final Set<String> PARAMETERS = Set.of("Mode", "ValidCard", "ValidPlayer",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> TYPES = Set.of("Land", "Creature", "Artifact", "Enchantment",
            "Planeswalker", "Instant", "Sorcery");

    private IntrinsicTypedDiscardTriggerAdapter() { }

    static Optional<IntrinsicEventTrigger> describe(final Map<String, String> parameters,
            final IntrinsicLibraryReference library) {
        if (!"Discarded".equals(parameters.get("Mode"))
                || !PARAMETERS.containsAll(parameters.keySet())
                || !"Battlefield".equals(parameters.getOrDefault("TriggerZones", "Battlefield"))) {
            return Optional.empty();
        }
        final String filter = parameters.getOrDefault("ValidCard", "");
        final Set<String> categories = new HashSet<>(TYPES);
        String recipient = null;
        String positiveType = null;
        boolean hasType = false;
        for (final String part : filter.split("[.+]", -1)) {
            if (TYPES.contains(part)) {
                if (positiveType != null && !positiveType.equals(part)) { return Optional.empty(); }
                positiveType = part;
                categories.retainAll(Set.of(part));
                hasType = true;
            } else if (part.startsWith("non") && TYPES.contains(part.substring(3))) {
                categories.remove(part.substring(3));
            } else if ("Card".equals(part)) {
                hasType = true;
            } else if (Set.of("YouOwn", "YouCtrl", "OppOwn", "OppCtrl").contains(part)) {
                final String scope = part.startsWith("You") ? "You" : "Opponent";
                if (recipient != null && !recipient.equals(scope)) { return Optional.empty(); }
                recipient = scope;
            } else {
                return Optional.empty();
            }
        }
        final String player = parameters.get("ValidPlayer");
        if (player != null) {
            final String scope = switch (player) {
            case "You" -> "You";
            case "Opponent", "Player.Opponent" -> "Opponent";
            default -> null;
            };
            if (scope == null || recipient != null && !recipient.equals(scope)) { return Optional.empty(); }
            recipient = scope;
        }
        if (!hasType || recipient == null) { return Optional.empty(); }
        // Primary types are disjoint reference buckets, not exact card characteristics. In
        // particular artifact creatures/multitype intersections are not modeled by this prior.
        // TODO: Discard-specific selection bias, subtype/color/mana-value predicates, event-card
        // bindings, batches and per-turn caps need richer cases. Apply eligibility before a cap,
        // not by multiplying the probability after the unfiltered events have already been capped.
        final double probability = categories.isEmpty() ? 0
                : library.hitProbability(String.join(",", categories)).orElseThrow();
        return Optional.of(new IntrinsicEventTrigger(IntrinsicReferenceModel.EventType.CARD_DISCARDED,
                IntrinsicEventTrigger.TurnScope.ANY_TURN, false, probability));
    }
}
