package forge.ai.effect;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.Predicate;

/** Selects among concrete actions without replacing a caller's safe fallback. */
public final class ActionValueSelector {
    private ActionValueSelector() {
    }

    /** Result of attempting to value and select a list of concrete candidates. */
    public record Selection<T>(T selected, List<T> orderedCandidates, boolean valuationUsed,
            String reason) {
        public Selection {
            orderedCandidates = orderedCandidates == null ? List.of() : List.copyOf(orderedCandidates);
            reason = reason == null ? "" : reason;
        }
    }

    /**
     * Values every candidate and selects the highest net value when all candidates are complete.
     * A candidate that is unavailable, unsupported, incomplete, or not admitted by the caller
     * causes the original ordering to be returned. Stable sorting preserves the caller's order
     * for equal values.
     */
    public static <T> Selection<T> selectBest(final List<T> candidates,
            final ValuationContext context, final Predicate<T> supportedCandidate,
            final Function<T, ValuationAction> actionFactory) {
        if (candidates == null || candidates.isEmpty()) {
            return fallback(candidates, "No action candidates were supplied.");
        }
        if (context == null || supportedCandidate == null || actionFactory == null) {
            return fallback(candidates, "Action valuation is not configured.");
        }

        final Map<T, CardValueBreakdown> values = new IdentityHashMap<>();
        for (final T candidate : candidates) {
            try {
                if (!supportedCandidate.test(candidate)) {
                    return fallback(candidates, "At least one action is outside the supported slice.");
                }
                final CardValueBreakdown value = UnifiedActionValueEvaluator.evaluate(
                        actionFactory.apply(candidate), context);
                if (!value.isComplete()) {
                    return fallback(candidates, "At least one action is not completely understood.");
                }
                values.put(candidate, value);
            } catch (final RuntimeException unsupported) {
                return fallback(candidates, "At least one action could not be evaluated safely.");
            }
        }

        final List<T> ordered = new ArrayList<>(candidates);
        ordered.sort(Comparator.comparingInt(
                (T candidate) -> values.get(candidate).netValue()).reversed());
        return new Selection<>(ordered.get(0), ordered, true, "All action candidates were complete.");
    }

    private static <T> Selection<T> fallback(final List<T> candidates, final String reason) {
        final List<T> original = candidates == null ? List.of() : new ArrayList<>(candidates);
        return new Selection<>(original.isEmpty() ? null : original.get(0), original, false, reason);
    }
}
