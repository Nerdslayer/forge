package forge.ai.effect;

import forge.game.player.Player;

/**
 * Explicit assumptions used when valuing a card or action.
 *
 * <p>The context keeps the decision being made separate from the card's intrinsic value. This
 * lets later consumers reuse the same card-value components for casting, combat, discard, and
 * removal without silently applying assumptions from another decision.</p>
 */
public record ValuationContext(Player evaluatingAi, ValuationMode mode,
        ValuationDecision decision, int horizonTurns, boolean completeInformation,
        int relationshipWeightPercent, int intrinsicWeightPercent) {
    public ValuationContext {
        if (mode == null || decision == null) {
            throw new IllegalArgumentException("Valuation mode and decision are required");
        }
        if (horizonTurns < 0) {
            throw new IllegalArgumentException("Valuation horizon cannot be negative");
        }
        validateWeight(relationshipWeightPercent, "relationship");
        validateWeight(intrinsicWeightPercent, "intrinsic");
        if (mode == ValuationMode.SITUATIONAL && evaluatingAi == null) {
            throw new IllegalArgumentException("Situational valuation requires an AI player");
        }
        if (mode == ValuationMode.INTRINSIC_REFERENCE
                && (relationshipWeightPercent != 0 || intrinsicWeightPercent != 0)) {
            throw new IllegalArgumentException("Intrinsic valuation cannot use live relationship weights");
        }
    }

    /** Creates the live context used by removal-target selection. */
    public static ValuationContext forRemoval(final Player evaluatingAi,
            final int relationshipWeightPercent, final int intrinsicWeightPercent) {
        return new ValuationContext(evaluatingAi, ValuationMode.SITUATIONAL,
                ValuationDecision.REMOVAL_TARGET, 1, true,
                relationshipWeightPercent, intrinsicWeightPercent);
    }

    /** Creates the live context used when selecting a card from a hand. */
    public static ValuationContext forHandSelection(final Player evaluatingAi,
            final boolean completeInformation) {
        return new ValuationContext(evaluatingAi, ValuationMode.SITUATIONAL,
                ValuationDecision.HAND_SELECTION, 3, completeInformation, 0, 0);
    }

    /** Creates a live context for evaluating one known card as a discard action. */
    public static ValuationContext forDiscard(final Player evaluatingAi,
            final boolean completeInformation) {
        return new ValuationContext(evaluatingAi, ValuationMode.SITUATIONAL,
                ValuationDecision.DISCARD, 3, completeInformation, 0, 0);
    }

    /** Creates a live context for evaluating a card as a candidate to cast from hand. */
    public static ValuationContext forCast(final Player evaluatingAi,
            final boolean completeInformation) {
        return new ValuationContext(evaluatingAi, ValuationMode.SITUATIONAL,
                ValuationDecision.CAST, 3, completeInformation, 0, 0);
    }

    /** Creates a live context for evaluating one activated ability use. */
    public static ValuationContext forActivation(final Player evaluatingAi,
            final boolean completeInformation) {
        return new ValuationContext(evaluatingAi, ValuationMode.SITUATIONAL,
                ValuationDecision.ACTIVATE, 3, completeInformation, 0, 0);
    }

    /** Creates a context for definition-only card evaluation. */
    public static ValuationContext intrinsicCard() {
        return new ValuationContext(null, ValuationMode.INTRINSIC_REFERENCE,
                ValuationDecision.GENERAL_CARD, 3, false, 0, 0);
    }

    private static void validateWeight(final int weight, final String name) {
        if (weight < 0 || weight > 100) {
            throw new IllegalArgumentException(name + " weight must be between 0 and 100");
        }
    }
}
