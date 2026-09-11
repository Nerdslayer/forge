package forge.ai.effect;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;

/** Extracts supported event-triggered consequences independently of production origins. */
final class TriggeredConsequenceExtractor implements EffectConsequenceExtractor {
    static final TriggeredConsequenceExtractor INSTANCE = new TriggeredConsequenceExtractor();

    // TODO(effect analysis): Support the remaining trigger families and richer token/counter
    // forms, including optional/limited triggers, CounterAddedAll, broader player constraints,
    // conditions, intervening-if clauses, and triggers active outside the battlefield. Combat
    // support excludes group declaration/once triggers, attacked-alone, first-attack,
    // poisoned-player, complex blocker-count, and multi-defender conditions.
    // Tap support currently covers straightforward became-tapped triggers and the specific
    // second-main self-tapped checkpoint used by survival abilities; other phase/state conditions,
    // untap triggers, TapAll, TapsForMana, and timing windows remain unsupported.

    private TriggeredConsequenceExtractor() {
    }

    @Override
    public EffectConsequence extract(final Card source, final Trigger trigger) {
        final EffectType observedType = EventTriggerParser.observedType(trigger);
        final boolean active = EventTriggerParser.isSecondMainTappedCheckpoint(trigger)
                ? EffectAbilityUtils.isActiveBattlefieldTriggerIgnoringRequirements(source, trigger)
                : EffectAbilityUtils.isActiveBattlefieldTrigger(source, trigger);
        if (observedType == null || !active
                || !EventTriggerParser.hasSupportedParameters(trigger)) {
            return null;
        }

        final SpellAbility outcome = EffectAbilityUtils.copyTriggerOutcome(source, trigger);
        if (outcome == null) {
            return null;
        }
        outcome.setActivatingPlayer(source.getController());
        outcome.resetTargets();
        final OutcomeEvaluator outcomeEvaluator = OutcomeEvaluatorRegistry.find(outcome);
        if (outcomeEvaluator == null) {
            return null;
        }
        final Trigger normalizedTrigger = normalizedTrigger(source, trigger);
        return new EffectConsequence(source, observedType, normalizedTrigger, outcome, outcomeEvaluator);
    }

    private static Trigger normalizedTrigger(final Card source, final Trigger trigger) {
        if ((trigger.getMode() == TriggerType.CounterAdded
                || trigger.getMode() == TriggerType.CounterAddedOnce)
                && "Any".equalsIgnoreCase(trigger.getParam("CounterType"))) {
            final Trigger normalized = trigger.copy(source, true);
            normalized.removeParam("CounterType");
            return normalized;
        }
        return trigger;
    }
}
