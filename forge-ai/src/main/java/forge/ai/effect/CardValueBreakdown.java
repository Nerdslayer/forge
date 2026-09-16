package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared value components for a card in one explicitly described context.
 *
 * <p>{@code contextAdjustment} is decision-specific value such as the removal priority adjustment
 * for tokens and board position. It is kept separate from intrinsic presence and future potential
 * so the same card value can be reused by another decision without copying that adjustment.</p>
 */
public record CardValueBreakdown(int currentPresenceValue, int futurePotentialValue,
        int transitionValue, int accessCost, int contextAdjustment,
        ValuationCompleteness completeness, List<String> reasons) {
    public CardValueBreakdown {
        if (completeness == null) {
            throw new IllegalArgumentException("Valuation completeness is required");
        }
        reasons = reasons == null ? List.of() : List.copyOf(reasons);
    }

    /** Value before access costs are paid. */
    public int grossValue() {
        return add(add(add(currentPresenceValue, futurePotentialValue), transitionValue),
                contextAdjustment);
    }

    /** Net value after the cost of accessing or retaining the card in this context. */
    public int netValue() {
        return subtract(grossValue(), accessCost);
    }

    public boolean isComplete() {
        return completeness == ValuationCompleteness.COMPLETE;
    }

    /**
     * Adds independent valuation components together. Callers are responsible for ensuring the
     * components represent distinct benefits or costs; this method only performs saturated
     * arithmetic, combines completeness, and preserves attribution.
     */
    public CardValueBreakdown plus(final CardValueBreakdown other) {
        if (other == null) {
            throw new IllegalArgumentException("A breakdown is required for composition");
        }
        return new CardValueBreakdown(
                add(currentPresenceValue, other.currentPresenceValue),
                add(futurePotentialValue, other.futurePotentialValue),
                add(transitionValue, other.transitionValue),
                add(accessCost, other.accessCost),
                add(contextAdjustment, other.contextAdjustment),
                ValuationCompleteness.combine(completeness, other.completeness),
                combineReasons(other.reasons));
    }

    public CardValueBreakdown withFuturePotential(final int value,
            final ValuationCompleteness valueCompleteness, final List<String> valueReasons) {
        return new CardValueBreakdown(currentPresenceValue, value, transitionValue, accessCost,
                contextAdjustment, ValuationCompleteness.combine(completeness, valueCompleteness),
                combineReasons(valueReasons));
    }

    /** Adds an action-specific transition while preserving the existing attribution. */
    public CardValueBreakdown withTransitionValue(final int value,
            final ValuationCompleteness valueCompleteness, final List<String> valueReasons) {
        final List<String> combinedReasons = combineReasons(valueReasons);
        return new CardValueBreakdown(currentPresenceValue, futurePotentialValue, value,
                accessCost, contextAdjustment,
                ValuationCompleteness.combine(completeness, valueCompleteness), combinedReasons);
    }

    private List<String> combineReasons(final List<String> additionalReasons) {
        final List<String> combinedReasons = new ArrayList<>(reasons);
        if (additionalReasons != null) {
            combinedReasons.addAll(additionalReasons);
        }
        return combinedReasons;
    }

    public static CardValueBreakdown unavailable(final String reason) {
        return new CardValueBreakdown(0, 0, 0, 0, 0, ValuationCompleteness.UNAVAILABLE,
                List.of(reason));
    }

    public static CardValueBreakdown unsupported(final String reason) {
        return new CardValueBreakdown(0, 0, 0, 0, 0, ValuationCompleteness.UNSUPPORTED,
                List.of(reason));
    }

    private static int add(final int left, final int right) {
        final long result = (long) left + right;
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE
                : result < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) result;
    }

    private static int subtract(final int left, final int right) {
        final long result = (long) left - right;
        return result > Integer.MAX_VALUE ? Integer.MAX_VALUE
                : result < Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) result;
    }
}
