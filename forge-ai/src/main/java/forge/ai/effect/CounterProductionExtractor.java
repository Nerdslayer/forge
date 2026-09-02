package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.GameEntity;
import forge.game.ability.AbilityKey;
import forge.game.ability.AbilityUtils;
import forge.game.ability.ApiType;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/** Extracts direct, fixed-recipient counter-added productions. */
final class CounterProductionExtractor implements EffectProductionExtractor {
    static final CounterProductionExtractor INSTANCE = new CounterProductionExtractor();

    // TODO(effect analysis): Support targeted, distributed, multi-type, optional, ETB,
    // replacement-modified, and additional trigger-origin counter productions.

    private CounterProductionExtractor() {
    }

    @Override
    public List<EffectProduction> extract(final Card source, final Trigger trigger) {
        if (!EffectAbilityUtils.isActiveBattlefieldTrigger(source, trigger)) {
            return List.of();
        }
        final int expectedBatches = EffectOccurrenceEstimator.estimateTriggerBatches(source, trigger);
        if (expectedBatches <= 0) {
            return List.of();
        }
        final SpellAbility outcome = findSupportedCounterOutcome(
                EffectAbilityUtils.copyTriggerOutcome(source, trigger));
        final EffectProduction production = outcome == null
                ? null : createProduction(source, outcome, expectedBatches);
        return production == null ? List.of() : List.of(production);
    }

    @Override
    public List<EffectProduction> extract(final Card source, final SpellAbility ability) {
        final SpellAbility copied = EffectAbilityUtils.copyPayableActivatedAbility(source, ability);
        if (copied == null) {
            return List.of();
        }
        final SpellAbility outcome = findSupportedCounterOutcome(copied);
        final EffectProduction production = outcome == null
                ? null : createProduction(source, outcome, 1);
        return production == null ? List.of() : List.of(production);
    }

    private static SpellAbility findSupportedCounterOutcome(final SpellAbility root) {
        final SpellAbility outcome = EffectAbilityUtils.findOutcome(root, ApiType.PutCounter);
        if (outcome == null || outcome.usesTargeting() || !outcome.hasParam("CounterType")
                || outcome.hasParam("CounterTypes") || outcome.hasParam("Choices")
                || outcome.hasParam("ChooseDifferent") || outcome.hasParam("DividedAsYouChoose")
                || outcome.hasParam("DividedRandomly") || outcome.hasParam("EachExistingCounter")
                || outcome.hasParam("ExistingCounter") || outcome.hasParam("PutOnEachOther")
                || outcome.hasParam("PutOnDefined") || outcome.hasParam("ETB")
                || outcome.hasParam("UpTo") || outcome.hasParam("Optional")
                || (outcome.hasParam("Placer") && !"You".equals(outcome.getParam("Placer")))) {
            return null;
        }
        for (final String param : outcome.getMapParams().keySet()) {
            if (param.startsWith("Condition") || param.startsWith("Unless")) {
                return null;
            }
        }
        return outcome;
    }

    private static EffectProduction createProduction(final Card source,
            final SpellAbility outcome, final int expectedBatches) {
        outcome.setActivatingPlayer(source.getController());
        final CounterType counterType = CounterType.getType(outcome.getParam("CounterType"));
        if (counterType == null) {
            return null;
        }
        final int amount = AbilityUtils.calculateAmount(source,
                outcome.getParamOrDefault("CounterNum", "1"), outcome);
        if (amount <= 0) {
            return null;
        }

        final List<GameEntity> recipients = AbilityUtils.getDefinedEntities(source,
                outcome.getParamOrDefault("Defined", "Self").split(" & "), outcome);
        final List<EffectEvent> events = new ArrayList<>();
        for (final GameEntity recipient : recipients) {
            if (!(recipient instanceof Card) && !(recipient instanceof Player)
                    || !recipient.canReceiveCounters(counterType)) {
                continue;
            }
            final Map<AbilityKey, Object> triggerParameters = new EnumMap<>(AbilityKey.class);
            if (recipient instanceof Card card) {
                triggerParameters.put(AbilityKey.Card, card);
            } else {
                triggerParameters.put(AbilityKey.Player, recipient);
            }
            triggerParameters.put(AbilityKey.Source, source.getController());
            triggerParameters.put(AbilityKey.CounterType, counterType);
            events.add(new EffectEvent(EffectType.COUNTER_ADDED, source.getController(),
                    List.of(new EffectEvent.Subject(recipient, amount)), triggerParameters));
        }
        return events.isEmpty() ? null : new EffectProduction(
                source, EffectType.COUNTER_ADDED, events, expectedBatches);
    }
}
