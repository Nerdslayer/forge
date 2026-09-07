package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.player.Player;
import forge.game.trigger.TriggerType;

/** Matches direct life-loss events against individual and resolution-batch triggers. */
final class LifeLostEventMatcher implements EffectEventMatcher {
    static final LifeLostEventMatcher INSTANCE = new LifeLostEventMatcher();

    // TODO(effect analysis): Support first-time/turn and activation limits, player-turn and cause
    // restrictions, optional trigger costs, replacement-modified amounts, and batches spanning
    // unsupported life-loss mechanisms.
    private LifeLostEventMatcher() {
    }

    @Override
    public List<EffectMatch> match(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.type() != EffectType.LIFE_LOST
                || consequence.observedType() != EffectType.LIFE_LOST) {
            return List.of();
        }
        return consequence.trigger().getMode() == TriggerType.LifeLostAll
                ? matchBatch(production, consequence) : matchIndividuals(production, consequence);
    }

    private static List<EffectMatch> matchIndividuals(final EffectProduction production,
            final EffectConsequence consequence) {
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

    private static List<EffectMatch> matchBatch(final EffectProduction production,
            final EffectConsequence consequence) {
        final Map<Player, Integer> lossMap = new LinkedHashMap<>();
        final List<EffectEvent.Subject> subjects = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            final Object amount = event.triggerParameters().get(AbilityKey.LifeAmount);
            if (!(amount instanceof Integer value) || value <= 0) {
                return List.of();
            }
            lossMap.merge(event.player(), value, EffectMath::add);
            subjects.addAll(event.subjects());
        }
        if (lossMap.isEmpty()) {
            return List.of();
        }

        final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
        runParams.put(AbilityKey.Map, lossMap);
        if (!EffectEventMatchUtils.passes(consequence, runParams)) {
            return List.of();
        }
        final EffectEvent event = new EffectEvent(EffectType.LIFE_LOST,
                production.events().get(0).player(), subjects, runParams);
        return List.of(new EffectMatch(event, 1));
    }

    private static Map<AbilityKey, Object> copiedParameters(final EffectEvent event) {
        final Map<AbilityKey, Object> result = new EnumMap<>(AbilityKey.class);
        result.putAll(event.triggerParameters());
        return result;
    }
}
