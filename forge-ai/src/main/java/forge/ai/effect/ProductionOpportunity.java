package forge.ai.effect;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/** A safe copied ability root together with its estimated number of near-term resolutions. */
record ProductionOpportunity(SpellAbility root, int expectedBatches) {
    static ProductionOpportunity fromTrigger(final Card source, final Trigger trigger) {
        if (!EffectAbilityUtils.isActiveBattlefieldTrigger(source, trigger)) {
            return null;
        }
        final int expectedBatches = EffectOccurrenceEstimator.estimateTriggerBatches(source, trigger);
        if (expectedBatches <= 0) {
            return null;
        }
        final SpellAbility root = EffectAbilityUtils.copyTriggerOutcome(source, trigger);
        return root == null ? null : new ProductionOpportunity(root, expectedBatches);
    }

    static ProductionOpportunity fromActivatedAbility(final Card source,
            final SpellAbility ability) {
        final SpellAbility root = EffectAbilityUtils.copyPayableActivatedAbility(source, ability);
        return root == null ? null : new ProductionOpportunity(root, 1);
    }
}
