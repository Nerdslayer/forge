package forge.ai.effect;

import java.util.Set;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;

/** Extracts supported event-triggered consequences independently of production origins. */
final class TriggeredConsequenceExtractor implements EffectConsequenceExtractor {
    static final TriggeredConsequenceExtractor INSTANCE = new TriggeredConsequenceExtractor();

    private static final Set<String> TOKEN_CREATED_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidPlayer", "ValidToken", "OnlyFirst", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> TOKEN_CREATED_ONCE_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidToken", "OnlyFirst", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");

    private TriggeredConsequenceExtractor() {
    }

    @Override
    public EffectConsequence extract(final Card source, final Trigger trigger) {
        final EffectType observedType = observedType(trigger);
        if (observedType == null || !EffectAbilityUtils.isActiveBattlefieldTrigger(source, trigger)
                || !hasSupportedParameters(trigger)) {
            return null;
        }

        final SpellAbility outcome = EffectAbilityUtils.copyTriggerOutcome(source, trigger);
        if (outcome == null) {
            return null;
        }
        outcome.setActivatingPlayer(source.getController());
        outcome.resetTargets();
        final OutcomeEvaluator outcomeEvaluator = OutcomeEvaluatorRegistry.find(outcome);
        return outcomeEvaluator == null ? null
                : new EffectConsequence(source, observedType, trigger, outcome, outcomeEvaluator);
    }

    private static EffectType observedType(final Trigger trigger) {
        return trigger.getMode() == TriggerType.TokenCreated
                || trigger.getMode() == TriggerType.TokenCreatedOnce
                ? EffectType.TOKEN_CREATED : null;
    }

    private static boolean hasSupportedParameters(final Trigger trigger) {
        if (trigger.getMode() == TriggerType.TokenCreated) {
            return EffectAbilityUtils.hasOnlyParams(trigger, TOKEN_CREATED_TRIGGER_PARAMS)
                    && "You".equals(trigger.getParam("ValidPlayer"));
        }
        return trigger.getMode() == TriggerType.TokenCreatedOnce
                && EffectAbilityUtils.hasOnlyParams(trigger, TOKEN_CREATED_ONCE_TRIGGER_PARAMS);
    }
}
