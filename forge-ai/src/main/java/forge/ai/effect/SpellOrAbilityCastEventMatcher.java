package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;

/** Matches known spell and activated-ability cast opportunities against cast triggers. */
final class SpellOrAbilityCastEventMatcher implements EffectEventMatcher {
    static final SpellOrAbilityCastEventMatcher INSTANCE = new SpellOrAbilityCastEventMatcher();

    private SpellOrAbilityCastEventMatcher() {
    }

    @Override
    public List<EffectMatch> match(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.type() != EffectType.SPELL_OR_ABILITY_CAST
                || consequence.observedType() != EffectType.SPELL_OR_ABILITY_CAST) {
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
