package forge.ai.effect;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.function.Function;

/** Owns probability weighting and coverage reporting for independent intrinsic reference cases. */
public final class IntrinsicReferenceAggregator {
    private static final double PROBABILITY_EPSILON = 0.000000001;

    private IntrinsicReferenceAggregator() {
    }

    /**
     * Evaluates each case once and keeps its probability attached to the result. Case weights are
     * never renormalized after an unsupported or partial result.
     */
    public static <S> IntrinsicReferenceAggregate aggregate(final Collection<ReferenceCase> cases,
            final Function<ReferenceCase, OutcomePlan<S>> evaluator) {
        Objects.requireNonNull(cases, "cases");
        Objects.requireNonNull(evaluator, "evaluator");
        if (cases.isEmpty()) {
            throw new IllegalArgumentException("At least one reference case is required");
        }

        double totalProbability = 0;
        double value = 0;
        double complete = 0;
        double unavailable = 0;
        double partial = 0;
        double unsupported = 0;
        double unresolvedRandom = 0;
        final List<String> unresolvedReasons = new ArrayList<>();
        for (final ReferenceCase referenceCase : cases) {
            final OutcomePlan<S> plan = Objects.requireNonNull(evaluator.apply(referenceCase),
                    "reference outcome plan");
            final double probability = referenceCase.probability();
            totalProbability += probability;
            value += probability * plan.value();
            switch (plan.completeness()) {
            case COMPLETE -> complete += probability;
            case UNAVAILABLE -> unavailable += probability;
            case PARTIAL -> {
                partial += probability;
                unresolvedRandom += probability * plan.unresolvedProbability();
                unresolvedReasons.addAll(plan.unresolvedAlternatives());
                if (plan.unresolvedAlternatives().isEmpty()) {
                    unresolvedReasons.add(plan.reason());
                }
            }
            case UNSUPPORTED -> {
                unsupported += probability;
                unresolvedReasons.add(plan.reason());
            }
            default -> throw new IllegalStateException("Unhandled plan completeness");
            }
        }
        if (Math.abs(totalProbability - 1) > PROBABILITY_EPSILON) {
            throw new IllegalArgumentException("Reference case probabilities must sum to one");
        }
        // A normalized Cartesian distribution can sum to 1 + a few ulps. The total has been
        // validated above; clamp that rounding noise without renormalizing unsupported cases.
        return new IntrinsicReferenceAggregate(value, Math.min(1, complete), Math.min(1, unavailable),
                Math.min(1, partial), Math.min(1, unsupported), Math.min(1, unresolvedRandom), unresolvedReasons);
    }
}
