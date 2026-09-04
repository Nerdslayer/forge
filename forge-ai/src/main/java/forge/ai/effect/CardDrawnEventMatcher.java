package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.trigger.Trigger;

/** Matches individual predicted draws against supported Drawn triggers. */
final class CardDrawnEventMatcher implements EffectEventMatcher {
    static final CardDrawnEventMatcher INSTANCE = new CardDrawnEventMatcher();

    // TODO(effect analysis): Support revealed-card and characteristic-sensitive triggers once
    // their information is public or can be estimated without inspecting hidden cards, plus
    // first/limited/optional trigger semantics and replacement-modified draws.

    private CardDrawnEventMatcher() {
    }

    @Override
    public List<EffectMatch> match(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.type() != EffectType.CARD_DRAWN
                || consequence.observedType() != EffectType.CARD_DRAWN) {
            return List.of();
        }

        final Trigger trigger = consequence.trigger();
        final Trigger normalized = trigger.hasParam("FirstCardInDrawStep")
                ? withoutDrawStepFilter(consequence, trigger) : trigger;
        final List<EffectMatch> matches = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
            runParams.putAll(event.triggerParameters());
            if (matchesDrawStepFilter(trigger, runParams)
                    && EffectEventMatchUtils.passes(consequence, normalized, runParams)) {
                matches.add(new EffectMatch(new EffectEvent(event.type(), event.player(),
                        event.subjects(), runParams), 1));
            }
        }
        return matches;
    }

    private static Trigger withoutDrawStepFilter(final EffectConsequence consequence,
            final Trigger trigger) {
        final Trigger normalized = trigger.copy(consequence.source(), true);
        normalized.removeParam("FirstCardInDrawStep");
        return normalized;
    }

    private static boolean matchesDrawStepFilter(final Trigger trigger,
            final Map<AbilityKey, Object> runParams) {
        if (!trigger.hasParam("FirstCardInDrawStep")) {
            return true;
        }
        final boolean requiresFirst = Boolean.parseBoolean(
                trigger.getParam("FirstCardInDrawStep"));
        return requiresFirst == Boolean.TRUE.equals(runParams.get(AbilityKey.FirstTime));
    }
}
