package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.trigger.TriggerType;

/** Matches concrete counter removals against individual and once-per-resolution triggers. */
final class CounterRemovedEventMatcher implements EffectEventMatcher {
    static final CounterRemovedEventMatcher INSTANCE = new CounterRemovedEventMatcher();

    // TODO(effect analysis): Add semantics for counter movement, group/threshold triggers,
    // player counters, replacement-modified removals, and counter choices.

    private CounterRemovedEventMatcher() {
    }

    @Override
    public List<EffectMatch> match(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.type() != EffectType.COUNTER_REMOVED
                || consequence.observedType() != EffectType.COUNTER_REMOVED) {
            return List.of();
        }
        return consequence.trigger().getMode() == TriggerType.CounterRemovedOnce
                ? matchOnce(production, consequence) : matchIndividual(production, consequence);
    }

    private static List<EffectMatch> matchIndividual(final EffectProduction production,
            final EffectConsequence consequence) {
        final List<EffectMatch> matches = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            final EffectEvent.Subject subject = event.subjects().get(0);
            if (!(subject.value() instanceof Card card)) {
                continue;
            }
            final CounterType counterType = (CounterType) event.triggerParameters().get(
                    AbilityKey.CounterType);
            final int before = card.getCounters(counterType);
            int resolutions = 0;
            Map<AbilityKey, Object> firstPassingParams = null;
            for (int i = 1; i <= subject.occurrences(); i++) {
                final Map<AbilityKey, Object> runParams = removalRunParams(
                        event.triggerParameters());
                runParams.put(AbilityKey.CounterAmount, 1);
                runParams.put(AbilityKey.NewCounterAmount, Math.max(0, before - i));
                if (EffectEventMatchUtils.passes(consequence, runParams)) {
                    resolutions++;
                    if (firstPassingParams == null) {
                        firstPassingParams = runParams;
                    }
                }
            }
            if (resolutions > 0) {
                matches.add(new EffectMatch(new EffectEvent(event.type(), event.player(),
                        List.of(new EffectEvent.Subject(card, 1)), firstPassingParams), resolutions));
            }
        }
        return matches;
    }

    private static List<EffectMatch> matchOnce(final EffectProduction production,
            final EffectConsequence consequence) {
        final List<EffectMatch> matches = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            final EffectEvent.Subject subject = event.subjects().get(0);
            if (!(subject.value() instanceof Card card)) {
                continue;
            }
            final Map<AbilityKey, Object> runParams = removalRunParams(
                    event.triggerParameters());
            final CounterType counterType = (CounterType) runParams.get(AbilityKey.CounterType);
            final int before = card.getCounters(counterType);
            runParams.put(AbilityKey.CounterAmount, subject.occurrences());
            runParams.put(AbilityKey.NewCounterAmount,
                    Math.max(0, before - subject.occurrences()));
            if (EffectEventMatchUtils.passes(consequence, runParams)) {
                matches.add(new EffectMatch(new EffectEvent(event.type(), event.player(),
                        List.of(subject), runParams), 1));
            }
        }
        return matches;
    }

    private static Map<AbilityKey, Object> removalRunParams(
            final Map<AbilityKey, Object> parameters) {
        final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
        runParams.putAll(parameters);
        return runParams;
    }
}
