package forge.ai.effect;

import java.util.List;

/**
 * Weighted result of evaluating one outcome across intrinsic reference cases.
 *
 * <p>{@link #value()} is the known-value subtotal. It may include a supported subtotal from a
 * partial plan, so callers must inspect the coverage fields before treating it as a complete
 * expected value.</p>
 */
public record IntrinsicReferenceAggregate(double value, double completeCaseProbability,
        double unavailableCaseProbability, double partialCaseProbability,
        double unsupportedCaseProbability, double unresolvedRandomProbability,
        List<String> unresolvedReasons) {
    public IntrinsicReferenceAggregate {
        if (!Double.isFinite(value)) {
            throw new IllegalArgumentException("Reference aggregate value must be finite");
        }
        validateProbability(completeCaseProbability, "complete case probability");
        validateProbability(unavailableCaseProbability, "unavailable case probability");
        validateProbability(partialCaseProbability, "partial case probability");
        validateProbability(unsupportedCaseProbability, "unsupported case probability");
        validateProbability(unresolvedRandomProbability, "unresolved random probability");
        unresolvedReasons = List.copyOf(unresolvedReasons);
    }

    /** Probability whose result is understood, including cases with a known unavailable action. */
    public double knownCaseProbability() {
        return completeCaseProbability + unavailableCaseProbability;
    }

    /** Probability of reference cases for which the final value is not fully evaluated. */
    public double incompleteCaseProbability() {
        return partialCaseProbability + unsupportedCaseProbability;
    }

    public boolean complete() {
        return incompleteCaseProbability() == 0;
    }

    private static void validateProbability(final double value, final String name) {
        if (!Double.isFinite(value) || value < 0 || value > 1) {
            throw new IllegalArgumentException(name + " must be between zero and one");
        }
    }
}
