package forge.ai.effect;

import java.util.Map;
import java.util.Set;

/** Recognizes self-only death events, not general leaves-the-battlefield events. */
final class IntrinsicSelfDeathTriggerAdapter {
    private static final Set<String> PARAMETERS = Set.of("Mode", "Origin", "Destination", "ValidCard",
            "Execute", "TriggerZones", "TriggerDescription", "Secondary");

    private IntrinsicSelfDeathTriggerAdapter() { }

    static boolean bindableSourceEvent(final Map<String, String> parameters) {
        if (parameters == null) { return false; }
        final var event = new java.util.LinkedHashMap<>(parameters);
        // Only the existing root-condition binder owns these fields. This establishes the
        // departing object's identity, not that a condition is understood or met; unresolved
        // conditions still remain on the original description and block evaluation.
        for (final String condition : Set.of("CheckSVar", "SVarCompare", "IsPresent", "PresentCompare",
                "PresentZone", "PresentPlayer", "PresentDefined", "LifeTotal", "LifeAmount")) { event.remove(condition); }
        return supports(event);
    }

    static boolean supports(final Map<String, String> parameters) {
        // TODO: Unmodeled conditions/self-sacrifice, mixed self/other, noncreature, replacement and
        // recursion-aware death cases need explicit event and departure bindings.
        return parameters != null && PARAMETERS.containsAll(parameters.keySet())
                && "ChangesZone".equals(parameters.get("Mode"))
                && "Battlefield".equalsIgnoreCase(parameters.get("Origin"))
                && "Graveyard".equalsIgnoreCase(parameters.get("Destination"))
                && Set.of("Card.Self", "Creature.Self").contains(parameters.getOrDefault("ValidCard", ""))
                && "Battlefield".equalsIgnoreCase(parameters.getOrDefault("TriggerZones", "Battlefield"));
    }

    static Map<String, String> bindSourceQuantities(final Map<String, String> variables) {
        final Map<String, String> bound = new java.util.LinkedHashMap<>(variables);
        bound.replaceAll((name, expression) -> {
            // This is justified only by an already-recognized self-death event. Other
            // TriggeredCard quantities must not be globally reinterpreted as the source.
            if (expression.matches("TriggeredCard\\$Card(Power|Toughness)(/.*)?")) {
                return "Count$" + expression.substring("TriggeredCard$".length());
            }
            return expression;
        });
        return Map.copyOf(bound);
    }
}
