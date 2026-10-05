package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;

/** Literal mana-value predicates; variable values and card identity stay unresolved. */
final class IntrinsicManaValueFilter {
    private static final Pattern COMPARISON = Pattern.compile("cmc(EQ|NE|LT|LE|GT|GE)([0-9]+)");

    private IntrinsicManaValueFilter() { }

    record Comparison(String operator, int value) {
        boolean matches(final int manaValue) {
            return switch (operator) {
            case "EQ" -> manaValue == value;
            case "NE" -> manaValue != value;
            case "LT" -> manaValue < value;
            case "LE" -> manaValue <= value;
            case "GT" -> manaValue > value;
            case "GE" -> manaValue >= value;
            default -> false;
            };
        }
    }

    record Branch(String types, List<Comparison> comparisons) {
        boolean matches(final int manaValue) { return comparisons.stream().allMatch(test -> test.matches(manaValue)); }
    }

    static Optional<List<Branch>> parse(final String validity) {
        if (validity == null) { return Optional.empty(); }
        final List<Branch> result = new ArrayList<>();
        for (final String raw : validity.split(",", -1)) {
            final List<String> types = new ArrayList<>();
            final List<Comparison> comparisons = new ArrayList<>();
            for (final String part : raw.trim().split("[.+]", -1)) {
                if (!part.startsWith("cmc")) { types.add(part); continue; }
                final var matcher = COMPARISON.matcher(part);
                if (!matcher.matches()) { return Optional.empty(); }
                try { comparisons.add(new Comparison(matcher.group(1), Integer.parseInt(matcher.group(2)))); }
                catch (final NumberFormatException unsupported) { return Optional.empty(); }
            }
            if (types.isEmpty() || types.get(0).isEmpty()) { return Optional.empty(); }
            result.add(new Branch(String.join(".", types), List.copyOf(comparisons)));
        }
        // TODO: Variable thresholds, alternate mana values, color/X/split-card correlations
        // and actual deck composition require explicit reference characteristics.
        return Optional.of(List.copyOf(result));
    }
}
