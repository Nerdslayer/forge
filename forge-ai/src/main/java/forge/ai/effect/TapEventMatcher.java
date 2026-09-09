package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.phase.PhaseType;
import forge.game.trigger.TriggerType;

/** Matches predicted tap events and second-main tapped-state checkpoints. */
final class TapEventMatcher implements EffectEventMatcher {
    static final TapEventMatcher INSTANCE = new TapEventMatcher();

    private TapEventMatcher() {
    }

    @Override
    public List<EffectMatch> match(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.type() != EffectType.TAPPED_OR_UNTAPPED
                || consequence.observedType() != EffectType.TAPPED_OR_UNTAPPED) {
            return List.of();
        }

        final List<EffectMatch> matches = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
            runParams.putAll(event.triggerParameters());
            if (matchesImmediateTap(consequence, runParams)
                    || matchesSecondMainCheckpoint(consequence, runParams)) {
                matches.add(new EffectMatch(event, 1));
            }
        }
        return matches;
    }

    private static boolean matchesImmediateTap(final EffectConsequence consequence,
            final Map<AbilityKey, Object> runParams) {
        return consequence.trigger().getMode() == TriggerType.Taps
                && !runParams.containsKey(AbilityKey.Phase)
                && EffectEventMatchUtils.passes(consequence, runParams);
    }

    private static boolean matchesSecondMainCheckpoint(final EffectConsequence consequence,
            final Map<AbilityKey, Object> runParams) {
        return consequence.trigger().getMode() == TriggerType.Phase
                && runParams.get(AbilityKey.Phase) == PhaseType.MAIN2
                && runParams.get(AbilityKey.Card) == consequence.source()
                && EffectEventMatchUtils.passesWithoutTriggeredObjectRequirements(
                        consequence, runParams);
    }
}
