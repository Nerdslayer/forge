package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.game.GameEntity;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.trigger.TriggerType;

/** Matches damage instances using Forge's individual and simultaneous-batch semantics. */
final class DamageDealtEventMatcher implements EffectEventMatcher {
    static final DamageDealtEventMatcher INSTANCE = new DamageDealtEventMatcher();

    // TODO(effect analysis): Support DamageAll/ExcessDamage trigger families, FirstTime,
    // cause-relative and source-relative restrictions, optional/limited triggers, combat damage,
    // prevention and replacement-modified batches, and mixed combat/noncombat damage tables.

    private DamageDealtEventMatcher() {
    }

    @Override
    public List<EffectMatch> match(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.type() != EffectType.DAMAGE_DEALT
                || consequence.observedType() != EffectType.DAMAGE_DEALT) {
            return List.of();
        }
        if (consequence.trigger().getMode() == TriggerType.DamageDoneOnce) {
            return matchOncePerTarget(production, consequence);
        }
        if (consequence.trigger().getMode() == TriggerType.DamageDealtOnce) {
            return matchOncePerSource(production, consequence);
        }

        final List<EffectMatch> matches = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            final Map<AbilityKey, Object> runParams = copiedParameters(event);
            if (EffectEventMatchUtils.passes(consequence, runParams)) {
                matches.add(new EffectMatch(new EffectEvent(event.type(), event.player(),
                        event.subjects(), runParams), 1));
            }
        }
        return matches;
    }

    private static List<EffectMatch> matchOncePerTarget(final EffectProduction production,
            final EffectConsequence consequence) {
        final Map<GameEntity, Map<Card, Integer>> grouped = new LinkedHashMap<>();
        for (final EffectEvent event : production.events()) {
            final Object source = event.triggerParameters().get(AbilityKey.DamageSource);
            final Object target = event.triggerParameters().get(AbilityKey.DamageTarget);
            final Object amount = event.triggerParameters().get(AbilityKey.DamageAmount);
            if (!(source instanceof Card card) || !(target instanceof GameEntity entity)
                    || !(amount instanceof Integer value)) {
                return List.of();
            }
            grouped.computeIfAbsent(entity, key -> new LinkedHashMap<>())
                    .merge(card, value, EffectMath::add);
        }

        final List<EffectMatch> matches = new ArrayList<>();
        for (final Map.Entry<GameEntity, Map<Card, Integer>> entry : grouped.entrySet()) {
            final Map<AbilityKey, Object> runParams = commonBatchParameters(production);
            runParams.put(AbilityKey.DamageTarget, entry.getKey());
            runParams.put(AbilityKey.DamageMap, entry.getValue());
            if (EffectEventMatchUtils.passes(consequence, runParams)) {
                matches.add(batchMatch(production, entry.getKey(), runParams));
            }
        }
        return matches;
    }

    private static List<EffectMatch> matchOncePerSource(final EffectProduction production,
            final EffectConsequence consequence) {
        final Map<Card, Map<GameEntity, Integer>> grouped = new LinkedHashMap<>();
        for (final EffectEvent event : production.events()) {
            final Object source = event.triggerParameters().get(AbilityKey.DamageSource);
            final Object target = event.triggerParameters().get(AbilityKey.DamageTarget);
            final Object amount = event.triggerParameters().get(AbilityKey.DamageAmount);
            if (!(source instanceof Card card) || !(target instanceof GameEntity entity)
                    || !(amount instanceof Integer value)) {
                return List.of();
            }
            grouped.computeIfAbsent(card, key -> new LinkedHashMap<>())
                    .merge(entity, value, EffectMath::add);
        }

        final List<EffectMatch> matches = new ArrayList<>();
        for (final Map.Entry<Card, Map<GameEntity, Integer>> entry : grouped.entrySet()) {
            final Map<AbilityKey, Object> runParams = commonBatchParameters(production);
            runParams.put(AbilityKey.DamageSource, entry.getKey());
            runParams.put(AbilityKey.DamageMap, entry.getValue());
            if (EffectEventMatchUtils.passes(consequence, runParams)) {
                matches.add(batchMatch(production, entry.getKey(), runParams));
            }
        }
        return matches;
    }

    private static EffectMatch batchMatch(final EffectProduction production,
            final Object subject, final Map<AbilityKey, Object> runParams) {
        final EffectEvent event = new EffectEvent(EffectType.DAMAGE_DEALT,
                production.events().get(0).player(),
                List.of(new EffectEvent.Subject(subject, 1)), runParams);
        return new EffectMatch(event, 1);
    }

    private static Map<AbilityKey, Object> copiedParameters(final EffectEvent event) {
        final Map<AbilityKey, Object> result = new EnumMap<>(AbilityKey.class);
        result.putAll(event.triggerParameters());
        return result;
    }

    private static Map<AbilityKey, Object> commonBatchParameters(
            final EffectProduction production) {
        final Map<AbilityKey, Object> result = new EnumMap<>(AbilityKey.class);
        result.put(AbilityKey.IsCombatDamage, false);
        result.put(AbilityKey.Cause,
                production.events().get(0).triggerParameters().get(AbilityKey.Cause));
        return result;
    }
}
