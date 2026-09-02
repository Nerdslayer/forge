package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.GameEntity;
import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CounterType;
import forge.game.trigger.TriggerType;

/** Matches concrete counter additions against exact-type or any-type counter triggers. */
final class CounterAddedEventMatcher implements EffectEventMatcher {
    static final CounterAddedEventMatcher INSTANCE = new CounterAddedEventMatcher();

    // TODO(effect analysis): Add CounterAddedAll and semantics for optional, limited,
    // replacement-modified, distributed, and other currently rejected counter trigger forms.

    private CounterAddedEventMatcher() {
    }

    @Override
    public List<EffectMatch> match(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.type() != EffectType.COUNTER_ADDED
                || consequence.observedType() != EffectType.COUNTER_ADDED) {
            return List.of();
        }
        return consequence.trigger().getMode() == TriggerType.CounterAddedOnce
                ? matchOnce(production, consequence) : matchIndividual(production, consequence);
    }

    private static List<EffectMatch> matchIndividual(final EffectProduction production,
            final EffectConsequence consequence) {
        final List<EffectMatch> matches = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            final EffectEvent.Subject subject = event.subjects().get(0);
            final GameEntity recipient = (GameEntity) subject.value();
            final CounterType counterType = (CounterType) event.triggerParameters().get(
                    AbilityKey.CounterType);
            final int oldAmount = recipient.getCounters(counterType);
            int resolutions = 0;
            Map<AbilityKey, Object> firstPassingParams = null;
            for (int i = 1; i <= subject.occurrences(); i++) {
                final Map<AbilityKey, Object> runParams = counterRunParams(
                        event.triggerParameters());
                runParams.put(AbilityKey.CounterAmount, EffectMath.add(oldAmount, i));
                if (EffectEventMatchUtils.passes(consequence, runParams)) {
                    resolutions++;
                    if (firstPassingParams == null) {
                        firstPassingParams = runParams;
                    }
                }
            }
            if (resolutions > 0) {
                matches.add(new EffectMatch(new EffectEvent(event.type(), event.player(),
                        List.of(new EffectEvent.Subject(subject.value(), 1)), firstPassingParams),
                        resolutions));
            }
        }
        return matches;
    }

    private static List<EffectMatch> matchOnce(final EffectProduction production,
            final EffectConsequence consequence) {
        final List<EffectMatch> matches = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            final EffectEvent.Subject subject = event.subjects().get(0);
            final CounterType counterType = (CounterType) event.triggerParameters().get(
                    AbilityKey.CounterType);
            final Map<AbilityKey, Object> runParams = counterRunParams(
                    event.triggerParameters());
            runParams.put(AbilityKey.CounterAmount, subject.occurrences());
            if (subject.value() instanceof Card card) {
                runParams.put(AbilityKey.FirstTime,
                        card.getGame().getCounterAddedThisTurn(counterType, card) == 0);
            }
            if (EffectEventMatchUtils.passes(consequence, runParams)) {
                matches.add(new EffectMatch(new EffectEvent(event.type(), event.player(),
                        List.of(subject), runParams), 1));
            }
        }
        return matches;
    }

    private static Map<AbilityKey, Object> counterRunParams(
            final Map<AbilityKey, Object> parameters) {
        final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
        runParams.putAll(parameters);
        return runParams;
    }
}
