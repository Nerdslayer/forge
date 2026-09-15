package forge.ai.effect;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.zone.ZoneType;

/** Applies action-specific state-transition value to a live permanent valuation. */
public final class RemovalActionEvaluator {
    private RemovalActionEvaluator() {
    }

    /**
     * Applies the bounded distinction between permanent removal and returning a permanent to hand.
     * Destroy and exile currently retain the full permanent-removal value.
     */
    public static CardValueBreakdown evaluate(final Card candidate,
            final CardValueBreakdown permanentRemovalValue, final RemovalActionKind actionKind) {
        return evaluate(null, candidate, permanentRemovalValue, actionKind);
    }

    /** Applies action-specific value with the deciding AI available for hand-knowledge context. */
    public static CardValueBreakdown evaluate(final Player evaluatingAi, final Card candidate,
            final CardValueBreakdown permanentRemovalValue, final RemovalActionKind actionKind) {
        if (candidate == null || permanentRemovalValue == null || actionKind == null
                || actionKind != RemovalActionKind.BOUNCE || candidate.isToken()) {
            return permanentRemovalValue;
        }

        final Player owner = candidate.getOwner() == null ? candidate.getController() : candidate.getOwner();
        if (owner == null) {
            return permanentRemovalValue;
        }

        final int handSize = owner.getCardsIn(ZoneType.Hand).size();
        final HandValuationContext handContext = HandValuationContext.knownCardOnly(evaluatingAi,
                owner, candidate, handSize + 1);
        final CardValueBreakdown knownCardValue = HandCardValueEvaluator.evaluateKnownCard(candidate,
                handContext);
        final int returnedCardValue = knownCardValue.netValue();

        // A bounced card is temporarily absent but its owner retains a resource in hand. The
        // known-card evaluator uses the printed definition when supported and deliberately falls
        // back to a generic value when it is not. TODO(effect analysis): Account for the owner's
        // hidden alternatives, cast timing, replay likelihood, and renewed entry effects.
        return new CardValueBreakdown(
                permanentRemovalValue.currentPresenceValue(),
                permanentRemovalValue.futurePotentialValue(),
                EffectMath.negate(returnedCardValue),
                permanentRemovalValue.accessCost(),
                permanentRemovalValue.contextAdjustment(),
                combineCompleteness(permanentRemovalValue, knownCardValue),
                permanentRemovalValue.reasons());
    }

    private static ValuationCompleteness combineCompleteness(final CardValueBreakdown permanent,
            final CardValueBreakdown handValue) {
        if (permanent.completeness() == ValuationCompleteness.UNAVAILABLE
                || handValue.completeness() == ValuationCompleteness.UNAVAILABLE) {
            return ValuationCompleteness.UNAVAILABLE;
        }
        if (permanent.completeness() == ValuationCompleteness.UNSUPPORTED
                || handValue.completeness() == ValuationCompleteness.UNSUPPORTED) {
            return ValuationCompleteness.UNSUPPORTED;
        }
        return permanent.completeness() == ValuationCompleteness.PARTIAL
                || handValue.completeness() == ValuationCompleteness.PARTIAL
                ? ValuationCompleteness.PARTIAL : ValuationCompleteness.COMPLETE;
    }

}
