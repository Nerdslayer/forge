package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.IntPredicate;
import java.util.regex.Pattern;

import forge.ai.effect.IntrinsicReferenceQuantities.Quantity;

/** Counter ranges use one counter sample, not multiplied independent threshold probabilities. */
final class IntrinsicCounterPredicates {
    private static final Pattern CLAUSE = Pattern.compile("counters_(GE|GT|LE|LT|EQ|NE)(\\d+)_(P1P1|LEVEL)");
    record Filter(String base, Quantity quantity, IntPredicate matches) { }

    private IntrinsicCounterPredicates() { }

    static Optional<Filter> parse(final String filter) {
        if (filter == null) { return Optional.empty(); }
        final List<String> base = new ArrayList<>();
        final List<IntPredicate> predicates = new ArrayList<>();
        Quantity quantity = null;
        for (final String part : filter.replace(".counters_", "+counters_").split("\\+", -1)) {
            final var clause = CLAUSE.matcher(part);
            if (!clause.matches()) {
                if (part.startsWith("counters_") || part.isBlank()) { return Optional.empty(); }
                base.add(part);
                continue;
            }
            final Quantity kind = "P1P1".equals(clause.group(3)) ? Quantity.P1P1_COUNTERS : Quantity.LEVEL_COUNTERS;
            if (quantity != null && quantity != kind) { return Optional.empty(); }
            quantity = kind;
            final int threshold;
            try { threshold = Integer.parseInt(clause.group(2)); }
            catch (final NumberFormatException invalid) { return Optional.empty(); }
            predicates.add(switch (clause.group(1)) {
            case "GE" -> value -> value >= threshold;
            case "GT" -> value -> value > threshold;
            case "LE" -> value -> value <= threshold;
            case "LT" -> value -> value < threshold;
            case "EQ" -> value -> value == threshold;
            default -> value -> value != threshold;
            });
        }
        // TODO: Mixed counter types and counters with no calibrated reference distribution.
        return quantity == null || base.isEmpty() ? Optional.empty()
                : Optional.of(new Filter(String.join("+", base), quantity,
                        value -> predicates.stream().allMatch(predicate -> predicate.test(value))));
    }
}
