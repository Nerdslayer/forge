package forge.ai.effect;

import java.util.List;

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
        final CardValueBreakdown knownCardValue = UnifiedCardValueEvaluator.evaluateCard(candidate,
                handContext);
        final int returnedCardValue = knownCardValue.netValue();

        // A bounced card is temporarily absent but its owner retains a resource in hand. The
        // known-card evaluator uses the printed definition when supported and deliberately falls
        // back to a generic value when it is not. TODO(effect analysis): Account for the owner's
        // hidden alternatives, cast timing, replay likelihood, and renewed entry effects.
        return permanentRemovalValue.withTransitionValue(EffectMath.negate(returnedCardValue),
                combineCompleteness(permanentRemovalValue, knownCardValue),
                List.of("Bounce retains hand value " + returnedCardValue
                        + " for the returned card."));
    }

    private static ValuationCompleteness combineCompleteness(final CardValueBreakdown permanent,
            final CardValueBreakdown handValue) {
        return ValuationCompleteness.combine(permanent.completeness(), handValue.completeness());
    }

}
