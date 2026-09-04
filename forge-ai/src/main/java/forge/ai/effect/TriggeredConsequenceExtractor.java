package forge.ai.effect;

import java.util.Set;

import forge.game.card.Card;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.trigger.TriggerType;

/** Extracts supported event-triggered consequences independently of production origins. */
final class TriggeredConsequenceExtractor implements EffectConsequenceExtractor {
    static final TriggeredConsequenceExtractor INSTANCE = new TriggeredConsequenceExtractor();

    // TODO(effect analysis): Support the remaining trigger families and richer token/counter
    // forms, including optional/limited triggers, CounterAddedAll, broader player constraints,
    // conditions, intervening-if clauses, and triggers active outside the battlefield.

    private static final Set<String> TOKEN_CREATED_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidPlayer", "ValidToken", "OnlyFirst", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> TOKEN_CREATED_ONCE_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidToken", "OnlyFirst", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> COUNTER_ADDED_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidCard", "ValidPlayer", "ValidSource", "CounterType",
            "CounterAmount", "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> COUNTER_ADDED_ONCE_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidEntity", "ValidCard", "ValidPlayer", "ValidSource", "CounterType",
            "FirstTime", "Execute", "TriggerZones", "TriggerDescription", "Secondary");
    private static final Set<String> LIFE_GAINED_TRIGGER_PARAMS = Set.of(
            "Mode", "ValidPlayer", "ValidSource", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> CHANGES_ZONE_TRIGGER_PARAMS = Set.of(
            "Mode", "Origin", "Destination", "ValidCard", "Execute", "TriggerZones",
            "TriggerDescription", "Secondary");
    private static final Set<String> CHANGES_ZONE_ALL_TRIGGER_PARAMS = Set.of(
            "Mode", "Origin", "Destination", "ValidCards", "Execute", "TriggerZones",
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
        if (outcomeEvaluator == null) {
            return null;
        }
        final Trigger normalizedTrigger = normalizedTrigger(source, trigger);
        return new EffectConsequence(source, observedType, normalizedTrigger, outcome, outcomeEvaluator);
    }

    private static EffectType observedType(final Trigger trigger) {
        if (trigger.getMode() == TriggerType.TokenCreated
                || trigger.getMode() == TriggerType.TokenCreatedOnce) {
            return EffectType.TOKEN_CREATED;
        }
        if (trigger.getMode() == TriggerType.LifeGained) {
            return EffectType.LIFE_GAINED;
        }
        if (trigger.getMode() == TriggerType.ChangesZone
                || trigger.getMode() == TriggerType.ChangesZoneAll) {
            return EffectType.ZONE_CHANGED;
        }
        return trigger.getMode() == TriggerType.CounterAdded
                || trigger.getMode() == TriggerType.CounterAddedOnce
                ? EffectType.COUNTER_ADDED : null;
    }

    private static boolean hasSupportedParameters(final Trigger trigger) {
        if (trigger.getMode() == TriggerType.TokenCreated) {
            return EffectAbilityUtils.hasOnlyParams(trigger, TOKEN_CREATED_TRIGGER_PARAMS)
                    && "You".equals(trigger.getParam("ValidPlayer"));
        }
        if (trigger.getMode() == TriggerType.TokenCreatedOnce) {
            return EffectAbilityUtils.hasOnlyParams(trigger, TOKEN_CREATED_ONCE_TRIGGER_PARAMS);
        }
        if (trigger.getMode() == TriggerType.CounterAdded) {
            return EffectAbilityUtils.hasOnlyParams(trigger, COUNTER_ADDED_TRIGGER_PARAMS);
        }
        if (trigger.getMode() == TriggerType.LifeGained) {
            return EffectAbilityUtils.hasOnlyParams(trigger, LIFE_GAINED_TRIGGER_PARAMS);
        }
        if (trigger.getMode() == TriggerType.ChangesZone) {
            return EffectAbilityUtils.hasOnlyParams(trigger, CHANGES_ZONE_TRIGGER_PARAMS);
        }
        if (trigger.getMode() == TriggerType.ChangesZoneAll) {
            return EffectAbilityUtils.hasOnlyParams(trigger, CHANGES_ZONE_ALL_TRIGGER_PARAMS);
        }
        return trigger.getMode() == TriggerType.CounterAddedOnce
                && EffectAbilityUtils.hasOnlyParams(trigger, COUNTER_ADDED_ONCE_TRIGGER_PARAMS);
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
