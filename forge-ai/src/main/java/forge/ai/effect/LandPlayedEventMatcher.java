package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;

/** Matches a predicted land play against a LandPlayed trigger. */
final class LandPlayedEventMatcher implements EffectEventMatcher {
    static final LandPlayedEventMatcher INSTANCE = new LandPlayedEventMatcher();

    // TODO(effect analysis): Add exact land-play counts, extra-land effects, cause/ability filters,
    // replacement effects, and richer land characteristics once those are represented in the
    // player-wide production model.
    private LandPlayedEventMatcher() {
    }

    @Override
    public List<EffectMatch> match(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.type() != EffectType.LAND_PLAYED
                || consequence.observedType() != EffectType.LAND_PLAYED) {
            return List.of();
        }
        final List<EffectMatch> matches = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            final Card land = event.subjects().stream()
                    .map(EffectEvent.Subject::value)
                    .filter(Card.class::isInstance)
                    .map(Card.class::cast)
                    .findFirst().orElse(null);
            if (land == null) {
                continue;
            }
            final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
            runParams.putAll(event.triggerParameters());
            runParams.put(AbilityKey.Card, land);
            if (EffectEventMatchUtils.passes(consequence, runParams)) {
                matches.add(new EffectMatch(new EffectEvent(event.type(), event.player(),
                        List.of(new EffectEvent.Subject(land, 1)), runParams), 1));
            }
        }
        return matches;
    }
}
