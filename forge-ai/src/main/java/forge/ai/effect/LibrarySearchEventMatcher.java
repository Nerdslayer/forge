package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;

/** Matches predicted library-search events against SearchedLibrary triggers. */
final class LibrarySearchEventMatcher implements EffectEventMatcher {
    static final LibrarySearchEventMatcher INSTANCE = new LibrarySearchEventMatcher();

    private LibrarySearchEventMatcher() {
    }

    @Override
    public List<EffectMatch> match(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.type() != EffectType.CARD_SEARCHED_OR_SELECTED
                || consequence.observedType() != EffectType.CARD_SEARCHED_OR_SELECTED) {
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
