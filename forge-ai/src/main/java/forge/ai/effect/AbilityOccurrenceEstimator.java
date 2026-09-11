package forge.ai.effect;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/** Shared occurrence arithmetic for situational and future intrinsic ability analysis. */
final class AbilityOccurrenceEstimator {
    static final int MAX_USES_PER_TURN = 4;
    static final double NEXT_TURN_DISCOUNT = 0.75;
    private static final double LAND_PER_HAND_CARD = 0.40;

    private AbilityOccurrenceEstimator() {
    }

    static AbilityOccurrenceEstimate estimateTriggered(final Card source, final Trigger trigger,
            final AbilityOccurrenceContext context) {
        if (context == null) {
            return AbilityOccurrenceEstimate.unsupported("Missing occurrence context");
        }
        return estimate(context.triggerRequest(source, trigger));
    }

    static AbilityOccurrenceEstimate estimate(final AbilityOccurrenceRequest request) {
        if (request == null || !request.supported()) {
            return AbilityOccurrenceEstimate.unsupported(request == null
                    ? "Missing occurrence request" : request.reason());
        }
        final double opportunity = nonNegative(request.opportunityOccurrences());
        final double condition = probability(request.conditionLikelihood());
        final double feasibility = probability(request.feasibilityLikelihood());
        final double willingness = probability(request.willingness());
        final double survival = nonNegative(request.survivalMultiplier());
        final double horizon = probability(request.horizonDiscount());
        final double expected = opportunity * condition * feasibility * willingness * survival * horizon;
        return new AbilityOccurrenceEstimate(expected, opportunity, condition, feasibility, willingness,
                survival, horizon, 0, 0, 0, 0, 0, true, request.reason());
    }

    static AbilityOccurrenceEstimate estimateActivated(final ActivationOccurrenceRequest request) {
        if (request == null || !request.supported()) {
            return AbilityOccurrenceEstimate.unsupported(request == null
                    ? "Missing activation occurrence request" : request.reason());
        }
        final int currentUses = request.canPayNow()
                ? usesForTurn(request.currentMana(), request.manaCost(), request.hasTapCost(),
                        request.sourceTapped(), false) : 0;
        final int noLandUses = usesForTurn(request.nextTurnMana(), request.manaCost(),
                request.hasTapCost(), false, true);
        final int withLandUses = usesForTurn(EffectMath.add(request.nextTurnMana(), 1),
                request.manaCost(), request.hasTapCost(), false, true);
        final double landProbability = probability(request.nextLandProbability());
        final double expectedNextTurnUses = (1 - landProbability) * noLandUses
                + landProbability * withLandUses;
        final double opportunityUses = request.manaCost() <= 0 && !request.hasTapCost()
                ? currentUses : currentUses + NEXT_TURN_DISCOUNT * expectedNextTurnUses;
        final double expectedUses = opportunityUses * probability(request.willingness());
        return new AbilityOccurrenceEstimate(expectedUses, opportunityUses, 1, 1,
                probability(request.willingness()), 1, NEXT_TURN_DISCOUNT, currentUses,
                noLandUses, withLandUses, Math.max(noLandUses, withLandUses), landProbability,
                true, request.reason());
    }

    static double estimateAdditionalLandProbability(final Player player) {
        if (player == null) {
            return 0;
        }
        final int handSize = player.getCardsIn(ZoneType.Hand).size();
        return 1 - Math.pow(1 - LAND_PER_HAND_CARD, handSize);
    }

    /** Returns the bounded discount for a relative turn boundary. */
    static double turnDiscount(final int turnNumber) {
        return Math.pow(NEXT_TURN_DISCOUNT, Math.max(0, turnNumber - 1));
    }

    private static int usesForTurn(final int availableMana, final int manaCost,
            final boolean hasTapCost, final boolean sourceTapped, final boolean assumeUntapped) {
        if (hasTapCost) {
            // A tap ability can be used once between untaps. A future opportunity assumes the
            // source untaps; current availability is checked separately by canPayNow.
            return Math.min(MAX_USES_PER_TURN,
                    availableMana >= manaCost && (assumeUntapped || !sourceTapped) ? 1 : 0);
        }
        if (manaCost <= 0) {
            // Without a mana or tap resource, the first pass cannot infer an activation limit.
            // Keep one expected use until the outcome model can establish safe repeatability.
            return 1;
        }
        return Math.min(MAX_USES_PER_TURN, availableMana / manaCost);
    }

    private static double probability(final double value) {
        return Math.max(0, Math.min(1, value));
    }

    private static double nonNegative(final double value) {
        return Math.max(0, value);
    }
}
