package forge.ai.effect;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/** A safe copied ability root together with its estimated number of near-term resolutions. */
record ProductionOpportunity(SpellAbility root, double expectedBatches) {
    static ProductionOpportunity fromTrigger(final Player evaluatingAi, final Card source,
            final Trigger trigger) {
        if (!EffectAbilityUtils.isActiveBattlefieldTrigger(source, trigger)) {
            return null;
        }
        final AbilityOccurrenceEstimate estimate = AbilityOccurrenceEstimator.estimateTriggered(
                source, trigger, new SituationalAbilityOccurrenceContext(evaluatingAi));
        if (!estimate.supported() || estimate.expectedOccurrences() <= 0) {
            return null;
        }
        final SpellAbility root = EffectAbilityUtils.copyTriggerOutcome(source, trigger);
        return root == null ? null : new ProductionOpportunity(root, estimate.expectedOccurrences());
    }

    static ProductionOpportunity fromActivatedAbility(final Card source,
            final SpellAbility ability) {
        // Keep fractional expected uses so a future activation can contribute without being
        // rounded to zero. The estimator also discounts activations the controller is unlikely
        // to choose when the shared outcome planner can value them.
        final SpellAbility root = EffectAbilityUtils.copyActivatedAbility(source, ability);
        if (root == null) {
            return null;
        }
        final ActivationUseEstimate estimate = ActivatedAbilityUseEvaluator.estimate(source, root);
        if (estimate.supported()) {
            return estimate.expectedUses() > 0
                    ? new ProductionOpportunity(root, estimate.expectedUses()) : null;
        }
        // Preserve existing one-use behavior for unsupported cost forms until their resource
        // models are implemented. Do not silently discard already-supported sacrifice/life/etc.
        // productions while the bounded estimator grows.
        final SpellAbility currentlyPayable = EffectAbilityUtils.copyPayableActivatedAbility(
                source, ability);
        return currentlyPayable == null ? null : new ProductionOpportunity(currentlyPayable, 1);
    }
}
