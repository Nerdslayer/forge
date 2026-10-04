package forge.ai.effect;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/** Pure one-opponent reference expansion; never guesses remembered card or target identities. */
final class IntrinsicRepeatedOutcomeNormalizer {
    private static final Set<String> PARAMETERS = Set.of("DB", "RepeatPlayers", "RepeatSubAbility",
            "ChangeZoneTable", "SubAbility", "SpellDescription", "StackDescription");

    private IntrinsicRepeatedOutcomeNormalizer() { }

    static AbilityOutcomeDescription normalize(final AbilityOutcomeDescription node) {
        return normalize(node, 0);
    }

    private static AbilityOutcomeDescription normalize(final AbilityOutcomeDescription node, final int depth) {
        if (node == null) { return null; }
        if (depth > 24) { return AbilityOutcomeDescription.unresolved(node.path(), "Repeated outcome depth limit"); }
        final var next = normalize(node.next(), depth + 1);
        if ("RepeatEach".equals(node.api()) && node.issue().isEmpty()
                && PARAMETERS.containsAll(node.parameters().keySet())
                && "Opponent".equals(node.parameters().get("RepeatPlayers"))
                && (!node.parameters().containsKey("ChangeZoneTable")
                        || "True".equalsIgnoreCase(node.parameters().get("ChangeZoneTable")))
                && node.choices().size() == 1) {
            // The intrinsic reference environment is 1v1. A single opponent means exactly one
            // iteration; ChangeZoneTable does not alter first-order token/body outcome value.
            return append(normalize(bindOpponent(node.choices().get(0), 0), depth + 1), next, 0);
        }
        // TODO: Multiple opponents, card/ability loops, ordering, remembered accumulation and
        // event-table reactions need explicit cases rather than arbitrary loop multipliers.
        return new AbilityOutcomeDescription(node.path(), node.api(), node.parameters(),
                node.choices().stream().map(child -> normalize(child, depth + 1)).toList(), next, node.issue());
    }

    private static AbilityOutcomeDescription bindOpponent(final AbilityOutcomeDescription node, final int depth) {
        if (node == null || depth > 24) { return node; }
        if (Set.of("RepeatEach", "ImmediateTrigger", "DelayedTrigger").contains(node.api())) {
            return node; // A new scope must bind its own event/remembered object.
        }
        final var parameters = new LinkedHashMap<>(node.parameters());
        for (final String field : List.of("Defined", "TokenOwner")) {
            if (Set.of("Remembered", "RememberedPlayer").contains(parameters.getOrDefault(field, ""))) {
                parameters.put(field, "Opponent");
            }
        }
        return new AbilityOutcomeDescription(node.path(), node.api(), parameters,
                node.choices().stream().map(child -> bindOpponent(child, depth + 1)).toList(),
                bindOpponent(node.next(), depth + 1), node.issue());
    }

    private static AbilityOutcomeDescription append(final AbilityOutcomeDescription node,
            final AbilityOutcomeDescription continuation, final int depth) {
        if (node == null) { return continuation; }
        if (depth > 24) { return AbilityOutcomeDescription.unresolved(node.path(), "Repeated continuation depth limit"); }
        return new AbilityOutcomeDescription(node.path(), node.api(), node.parameters(), node.choices(),
                append(node.next(), continuation, depth + 1), node.issue());
    }
}
