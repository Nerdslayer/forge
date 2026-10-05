package forge.ai.effect;

import java.util.Comparator;
import java.util.function.Predicate;

/** Conservative instruction multiplicity across sequences and mutually exclusive modes. */
final class OutcomeDescriptionMultiplicity {
    private OutcomeDescriptionMultiplicity() { }

    /** Returns a saturated upper bound; malformed/oversized trees reach the ceiling. */
    static int maximumOccurrences(final AbilityOutcomeDescription node,
            final Predicate<AbilityOutcomeDescription> matches, final int ceiling) {
        if (ceiling < 1) { throw new IllegalArgumentException("Positive multiplicity ceiling required"); }
        return maximum(node, matches, ceiling, 0, new int[] {1024});
    }

    private static int maximum(final AbilityOutcomeDescription node, final Predicate<AbilityOutcomeDescription> matches,
            final int ceiling, final int depth, final int[] remaining) {
        if (node == null) { return 0; }
        if (depth > 24 || --remaining[0] < 0) { return ceiling; }
        // Unknown leaf semantics remain the compiler's unresolved alternative. They do not
        // manufacture a known repeated instruction and erase supported partial-choice value.
        long count = matches.test(node) ? 1 : 0;
        if (!node.choices().isEmpty()) {
            if (!java.util.Set.of("Charm", "GenericChoice").contains(node.api())) { return ceiling; }
            try {
                final int selected = Integer.parseInt(node.parameters().getOrDefault(
                        "Charm".equals(node.api()) ? "CharmNum" : "ChoiceAmount", "1"));
                final int minimum = Integer.parseInt(node.parameters().getOrDefault("MinCharmNum", Integer.toString(selected)));
                if (minimum < 0 || selected < minimum || selected > 8) { return ceiling; }
                // The compiler decides legality/optionality/random semantics. This only bounds
                // what could execute; unresolved counts must never certify a safe single use.
                final var alternatives = node.choices().stream()
                        .map(choice -> maximum(choice, matches, ceiling, depth + 1, remaining))
                        .sorted(Comparator.reverseOrder()).toList();
                if (node.parameters().containsKey("CanRepeatModes")) {
                    if (!"True".equalsIgnoreCase(node.parameters().get("CanRepeatModes"))) { return ceiling; }
                    count += (long) selected * alternatives.get(0);
                } else {
                    count += alternatives.stream().limit(selected).mapToLong(Integer::longValue).sum();
                }
            } catch (final NumberFormatException unknownCount) { return ceiling; }
        }
        count += maximum(node.next(), matches, ceiling, depth + 1, remaining);
        return (int) Math.min(count, ceiling);
    }
}
