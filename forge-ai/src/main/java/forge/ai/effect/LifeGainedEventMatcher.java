package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;

/** Matches known life-gain events against supported LifeGained triggers. */
final class LifeGainedEventMatcher implements EffectEventMatcher {
    static final LifeGainedEventMatcher INSTANCE = new LifeGainedEventMatcher();

    // TODO(effect analysis): Add first-time/turn limits, spell restrictions,
    // replacement-modified gains, optional trigger costs, and batch/combined gain semantics.

    private LifeGainedEventMatcher() {
    }

    @Override
    public List<EffectMatch> match(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.type() != EffectType.LIFE_GAINED
                || consequence.observedType() != EffectType.LIFE_GAINED) {
            return List.of();
        }

        final List<EffectMatch> matches = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
            runParams.putAll(event.triggerParameters());
            if (EffectEventMatchUtils.passes(consequence, runParams)) {
                matches.add(new EffectMatch(new EffectEvent(event.type(), event.player(),
                        event.subjects(), runParams), 1));
            }
        }
        return matches;
    }
}
