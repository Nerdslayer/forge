package forge.ai.effect;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;

/** Binds a quantity once across a choice/sequence, preserving aliases and branch correlations. */
final class IntrinsicOutcomeQuantityBinder {
    private static final Set<String> AMOUNTS = Set.of("ScryNum", "DigNum", "ChangeNum", "NumCards", "LifeAmount", "NumDmg", "CounterNum",
            "TokenAmount", "NumCopies", "Amount", "NumAtt", "NumDef", "Power", "Toughness",
            "TokenPower", "TokenToughness", "AddPower", "AddToughness", "SetPower", "SetToughness", "Num",
            "CheckSVar", "ConditionCheckSVar", IntrinsicAbilityConditions.PRESENT_COUNT, IntrinsicAbilityConditions.LIFE_COUNT);
    private static final int MAX_VARIANTS = 256;
    private static final Set<String> COMPARISONS = Set.of("SVarCompare", "PresentCompare", "ConditionSVarCompare",
            "ConditionCompare", "ConditionLifeAmount", IntrinsicAbilityConditions.LIFE_COMPARISON);

    private IntrinsicOutcomeQuantityBinder() { }
    record BoundCase(AbilityOutcomeDescription outcome, Map<String, Integer> quantities) {
        BoundCase { quantities = Map.copyOf(quantities); }
    }

    static List<WeightedValue<AbilityOutcomeDescription>> bind(final AbilityOutcomeDescription outcome,
            final Map<String, String> variables, final IntrinsicReferenceModel model, final PermanentProfile source) {
        return bind(outcome, variables, model, source, Map.of());
    }

    static List<WeightedValue<AbilityOutcomeDescription>> bind(final AbilityOutcomeDescription outcome,
            final Map<String, String> variables, final IntrinsicReferenceModel model, final PermanentProfile source,
            final Map<String, Integer> existingQuantities) {
        return bindCases(outcome, variables, model, source, existingQuantities).stream()
                .map(reference -> new WeightedValue<>(reference.value().outcome(), reference.weight())).toList();
    }

    static List<WeightedValue<BoundCase>> bindCases(final AbilityOutcomeDescription outcome,
            final Map<String, String> variables, final IntrinsicReferenceModel model, final PermanentProfile source,
            final Map<String, Integer> existingQuantities) {
        final var prepared = canonicalSourceConditions(outcome, variables, 0);
        final Map<String, IntrinsicQuantityResolver.Binding> bindings = new LinkedHashMap<>();
        collect(prepared, variables, model, source, bindings, 0);
        List<WeightedValue<Map<String, Integer>>> cases = List.of(new WeightedValue<>(Map.copyOf(existingQuantities), 1));
        final Map<String, IntrinsicQuantityResolver.Binding> identities = new LinkedHashMap<>();
        bindings.values().forEach(binding -> identities.putIfAbsent(binding.identity(), binding));
        for (final var binding : identities.values()) {
            // A source CDA may already have sampled this primitive quantity. Reuse its value
            // rather than independently rolling the population again for its ability outcome.
            if (existingQuantities.containsKey(binding.identity())) { continue; }
            if ((long) cases.size() * binding.referenceValues().entries().size() > MAX_VARIANTS) {
                // TODO: Independent marginal aggregation for richer combinations. Retain the
                // original unresolved outcome instead of truncating/renormalizing its cases.
                return List.of(new WeightedValue<>(new BoundCase(prepared, existingQuantities), 1));
            }
            final List<WeightedValue<Map<String, Integer>>> expanded = new ArrayList<>();
            for (final var reference : cases) {
                for (final var value : binding.referenceValues().entries()) {
                    final Map<String, Integer> assigned = new LinkedHashMap<>(reference.value());
                    assigned.put(binding.identity(), value.value());
                    expanded.add(new WeightedValue<>(Map.copyOf(assigned), reference.weight() * value.weight()));
                }
            }
            cases = expanded;
        }
        return cases.stream().map(reference -> new WeightedValue<>(new BoundCase(rewrite(prepared, bindings,
                reference.value(), 0), reference.value()), reference.weight())).toList();
    }

    private static AbilityOutcomeDescription canonicalSourceConditions(final AbilityOutcomeDescription node,
            final Map<String, String> variables, final int depth) {
        if (node == null || depth > 24 || Set.of("ImmediateTrigger", "DelayedTrigger").contains(node.api())) { return node; }
        final var parameters = new LinkedHashMap<>(node.parameters());
        String expression = parameters.get("ConditionCheckSVar");
        final Set<String> visited = new java.util.HashSet<>();
        while (expression != null && visited.size() < 24 && visited.add(expression) && variables.containsKey(expression)) {
            expression = variables.get(expression);
        }
        if (expression != null && Set.of("Count$CardPower", "Count$CardToughness").contains(expression)) {
            // Preserve a live projected-state read, not a frozen initial characteristic.
            parameters.put("ConditionCheckSVar", expression);
        }
        // TODO: Arithmetic over mutable characteristics and mutable right-hand comparators
        // require an explicit projected expression evaluator, not pre-sequence scalar binding.
        return new AbilityOutcomeDescription(node.path(), node.api(), parameters,
                node.choices().stream().map(choice -> canonicalSourceConditions(choice, variables, depth + 1)).toList(),
                canonicalSourceConditions(node.next(), variables, depth + 1), node.issue());
    }

    private static void collect(final AbilityOutcomeDescription node, final Map<String, String> variables,
            final IntrinsicReferenceModel model, final PermanentProfile source,
            final Map<String, IntrinsicQuantityResolver.Binding> bindings, final int depth) {
        if (node == null || depth > 24) { return; }
        for (final String parameter : AMOUNTS) {
            final String amount = node.parameters().get(parameter);
            if (amount != null && !amount.matches("\\d+")) {
                IntrinsicQuantityResolver.resolve(amount, variables, model, source)
                        .filter(binding -> !"ConditionCheckSVar".equals(parameter) || immutableConditionQuantity(binding))
                        .ifPresent(binding -> bindings.put(amount, binding));
            }
        }
        for (final String parameter : COMPARISONS) {
            final String expression = comparisonExpression(node.parameters().get(parameter));
            if (expression != null && !expression.matches("-?\\d+")) {
                IntrinsicQuantityResolver.resolve(expression, variables, model, source)
                        .filter(binding -> !parameter.startsWith("Condition") || immutableConditionQuantity(binding))
                        .ifPresent(binding -> bindings.put(expression, binding));
            }
        }
        node.choices().forEach(choice -> collect(choice, variables, model, source, bindings, depth + 1));
        collect(node.next(), variables, model, source, bindings, depth + 1);
    }

    private static AbilityOutcomeDescription rewrite(final AbilityOutcomeDescription node,
            final Map<String, IntrinsicQuantityResolver.Binding> bindings, final Map<String, Integer> values,
            final int depth) {
        if (node == null || depth > 24) { return node; }
        final Map<String, String> parameters = new LinkedHashMap<>(node.parameters());
        for (final String parameter : AMOUNTS) {
            final var binding = bindings.get(parameters.get(parameter));
            if (binding != null && (!"ConditionCheckSVar".equals(parameter) || immutableConditionQuantity(binding))) {
                parameters.put(parameter, String.valueOf(binding.at(values.get(binding.identity()))));
            }
        }
        for (final String parameter : COMPARISONS) {
            final String comparison = parameters.get(parameter);
            final String expression = comparisonExpression(comparison);
            final var binding = bindings.get(expression);
            if (binding != null && (!parameter.startsWith("Condition") || immutableConditionQuantity(binding))) {
                parameters.put(parameter, comparison.substring(0, 2) + binding.at(values.get(binding.identity())));
            }
        }
        return new AbilityOutcomeDescription(node.path(), node.api(), parameters,
                node.choices().stream().map(choice -> rewrite(choice, bindings, values, depth + 1)).toList(),
                rewrite(node.next(), bindings, values, depth + 1), node.issue());
    }

    private static boolean immutableConditionQuantity(final IntrinsicQuantityResolver.Binding binding) {
        // Mutable quantities must be read at this step's resolution, never frozen before the
        // whole sequence. X paid and literal arithmetic are unchanged by preceding effects.
        return "X_PAID".equals(binding.identity()) || "STARTING_LIFE".equals(binding.identity())
                || binding.identity().startsWith("literal:");
    }

    private static String comparisonExpression(final String comparison) {
        return comparison != null && comparison.length() > 2
                && Set.of("GE", "GT", "LE", "LT", "EQ", "NE").contains(comparison.substring(0, 2))
                ? comparison.substring(2) : null;
    }
}
