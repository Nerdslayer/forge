package forge.ai.effect;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** Compatibility facade for the situational activation occurrence estimate. */
final class ActivatedAbilityUseEvaluator {
    private ActivatedAbilityUseEvaluator() {
    }

    static ActivationUseEstimate estimate(final Card source, final SpellAbility ability) {
        final SituationalAbilityOccurrenceContext context =
                new SituationalAbilityOccurrenceContext(source == null ? null : source.getController());
        final ActivationOccurrenceRequest request = context.activationRequest(source, ability);
        final AbilityOccurrenceEstimate estimate = AbilityOccurrenceEstimator.estimateActivated(request);
        if (!estimate.supported()) {
            return ActivationUseEstimate.unsupported(estimate.reason());
        }
        if (estimate.expectedOccurrences() <= 0) {
            return ActivationUseEstimate.unsupported("No expected activation in the analysis horizon");
        }
        return new ActivationUseEstimate(estimate.expectedOccurrences(),
                estimate.opportunityOccurrences(), estimate.currentUses(), estimate.noLandUses(),
                estimate.withLandUses(), estimate.nextTurnUses(), estimate.nextLandProbability(),
                estimate.willingness(), request.outcomeValue(), request.averageCardPlayValue(),
                request.outcomeSupported(), true, estimate.reason());
    }

    static double estimateAdditionalLandProbability(final Player player) {
        return SituationalAbilityOccurrenceContext.estimateAdditionalLandProbability(player);
    }
}
