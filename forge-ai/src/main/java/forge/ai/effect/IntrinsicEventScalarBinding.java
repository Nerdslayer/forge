package forge.ai.effect;

import java.util.Map;
import java.util.Set;

/** Scoped event quantity substitution, preserving arithmetic and nested event boundaries. */
record IntrinsicEventScalarBinding(String expression, int amount) {
    boolean matches(final String value) {
        return value != null && (value.equals(expression) || value.startsWith(expression + "/"));
    }

    boolean needed(final AbilityOutcomeDescription node) {
        if (node == null || Set.of("ImmediateTrigger", "DelayedTrigger").contains(node.api())) { return false; }
        return node.parameters().values().stream().anyMatch(this::matches)
                || node.choices().stream().anyMatch(this::needed) || needed(node.next());
    }

    Map<String, String> bindVariables(final Map<String, String> variables) {
        final var result = new java.util.LinkedHashMap<>(variables);
        result.replaceAll((key, value) -> bind(value));
        return Map.copyOf(result);
    }

    AbilityOutcomeDescription bindOutcome(final AbilityOutcomeDescription node) {
        if (node == null || Set.of("ImmediateTrigger", "DelayedTrigger").contains(node.api())) { return node; }
        final var parameters = new java.util.LinkedHashMap<>(node.parameters());
        parameters.replaceAll((key, value) -> bind(value));
        return new AbilityOutcomeDescription(node.path(), node.api(), parameters,
                node.choices().stream().map(this::bindOutcome).toList(), bindOutcome(node.next()), node.issue());
    }

    private String bind(final String value) {
        return matches(value) ? "Number$" + amount + value.substring(expression.length()) : value;
    }
}
