package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.trigger.TriggerType;

/** Matches normalized fight events against Forge's individual and batch fight triggers. */
final class FoughtEventMatcher implements EffectEventMatcher {
    static final FoughtEventMatcher INSTANCE = new FoughtEventMatcher();

    private FoughtEventMatcher() {
    }

    @Override
    public List<EffectMatch> match(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.type() != EffectType.FOUGHT
                || consequence.observedType() != EffectType.FOUGHT) {
            return List.of();
        }
        if (consequence.trigger().getMode() == TriggerType.FightOnce) {
            return matchOnce(production, consequence);
        }
        final List<EffectMatch> matches = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            final Map<AbilityKey, Object> parameters = copiedParameters(event);
            if (EffectEventMatchUtils.passes(consequence, parameters)) {
                matches.add(new EffectMatch(new EffectEvent(event.type(), event.player(),
                        event.subjects(), parameters), 1));
            }
        }
        return matches;
    }

    private static List<EffectMatch> matchOnce(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.events().isEmpty()) {
            return List.of();
        }
        final EffectEvent event = production.events().get(0);
        final Map<AbilityKey, Object> parameters = copiedParameters(event);
        if (!EffectEventMatchUtils.passes(consequence, parameters)) {
            return List.of();
        }
        return List.of(new EffectMatch(new EffectEvent(event.type(), event.player(),
                event.subjects(), parameters), 1));
    }

    private static Map<AbilityKey, Object> copiedParameters(final EffectEvent event) {
        final Map<AbilityKey, Object> result = new EnumMap<>(AbilityKey.class);
        result.putAll(event.triggerParameters());
        return result;
    }
}
