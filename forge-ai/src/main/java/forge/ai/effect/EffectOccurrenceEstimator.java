package forge.ai.effect;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.trigger.Trigger;

/** Compatibility facade for the former trigger-only occurrence estimator. */
final class EffectOccurrenceEstimator {
    private EffectOccurrenceEstimator() {
    }

    static int estimateTriggerBatches(final Player evaluatingAi, final Card source,
            final Trigger trigger) {
        final AbilityOccurrenceEstimate estimate = AbilityOccurrenceEstimator.estimateTriggered(
                source, trigger, new SituationalAbilityOccurrenceContext(evaluatingAi));
        return estimate.supported() && estimate.expectedOccurrences() > 0 ? 1 : 0;
    }
}
