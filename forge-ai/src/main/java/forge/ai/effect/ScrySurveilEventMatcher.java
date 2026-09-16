package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;

/** Matches predicted scry/surveil events against their corresponding triggers. */
final class ScrySurveilEventMatcher implements EffectEventMatcher {
    static final ScrySurveilEventMatcher INSTANCE = new ScrySurveilEventMatcher();

    private ScrySurveilEventMatcher() {
    }

    @Override
    public List<EffectMatch> match(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.type() != EffectType.SCRIED_OR_SURVEILLED
                || consequence.observedType() != EffectType.SCRIED_OR_SURVEILLED) {
            return List.of();
        }
        final List<EffectMatch> matches = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            final Map<AbilityKey, Object> parameters = new EnumMap<>(AbilityKey.class);
            parameters.putAll(event.triggerParameters());
            if (EffectEventMatchUtils.passes(consequence, parameters)) {
                matches.add(new EffectMatch(new EffectEvent(event.type(), event.player(),
                        event.subjects(), parameters), 1));
            }
        }
        return matches;
    }
}
