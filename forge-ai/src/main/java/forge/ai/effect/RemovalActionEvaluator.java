package forge.ai.effect;

import forge.ai.PlayerResourceValueEvaluator;
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
        if (candidate == null || permanentRemovalValue == null || actionKind == null
                || actionKind != RemovalActionKind.BOUNCE || candidate.isToken()) {
            return permanentRemovalValue;
        }

        final Player owner = candidate.getOwner() == null ? candidate.getController() : candidate.getOwner();
        if (owner == null) {
            return permanentRemovalValue;
        }

        final int handSize = owner.getCardsIn(ZoneType.Hand).size();
        final int returnedCardValue = PlayerResourceValueEvaluator.evaluateNextCard(handSize);

        // A bounced card is temporarily absent but its owner retains a generic card-sized resource.
        // TODO(effect analysis): Replace this generic hand-card approximation with the specific
        // bounced card's hand/replay value, including castability and renewed entry effects.
        return new CardValueBreakdown(
                permanentRemovalValue.currentPresenceValue(),
                permanentRemovalValue.futurePotentialValue(),
                EffectMath.negate(returnedCardValue),
                permanentRemovalValue.accessCost(),
                permanentRemovalValue.contextAdjustment(),
                permanentRemovalValue.completeness(),
                permanentRemovalValue.reasons());
    }
}
