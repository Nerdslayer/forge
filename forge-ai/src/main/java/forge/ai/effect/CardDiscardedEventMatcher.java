package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.trigger.TriggerType;

/** Matches predicted discards against individual and batch discard triggers. */
final class CardDiscardedEventMatcher implements EffectEventMatcher {
    static final CardDiscardedEventMatcher INSTANCE = new CardDiscardedEventMatcher();

    // TODO(effect analysis): Support characteristic-sensitive matches when identities are public
    // or can be estimated, first/limited/optional triggers, cause filters, cross-player aggregate
    // batches, and replacement-modified discards.
    private CardDiscardedEventMatcher() {
    }

    @Override
    public List<EffectMatch> match(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.type() != EffectType.CARD_DISCARDED
                || consequence.observedType() != EffectType.CARD_DISCARDED) {
            return List.of();
        }
        return consequence.trigger().getMode() == TriggerType.DiscardedAll
                ? matchBatches(production, consequence)
                : matchIndividuals(production, consequence);
    }

    private static List<EffectMatch> matchIndividuals(final EffectProduction production,
            final EffectConsequence consequence) {
        final List<EffectMatch> matches = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            for (final EffectEvent.Subject subject : event.subjects()) {
                if (!(subject.value() instanceof Card card)) {
                    continue;
                }
                final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
                runParams.putAll(event.triggerParameters());
                runParams.put(AbilityKey.Card, card);
                if (EffectEventMatchUtils.passes(consequence, runParams)) {
                    matches.add(new EffectMatch(new EffectEvent(event.type(), event.player(),
                            List.of(new EffectEvent.Subject(card, 1)), runParams),
                            subject.occurrences()));
                }
            }
        }
        return matches;
    }

    private static List<EffectMatch> matchBatches(final EffectProduction production,
            final EffectConsequence consequence) {
        final List<EffectMatch> matches = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            final CardCollection cards = new CardCollection(event.expandedCardSubjects());
            if (cards.isEmpty()) {
                continue;
            }
            final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
            runParams.putAll(event.triggerParameters());
            runParams.put(AbilityKey.Cards, cards);
            if (EffectEventMatchUtils.passes(consequence, runParams)) {
                matches.add(new EffectMatch(new EffectEvent(event.type(), event.player(),
                        event.subjects(), runParams), 1));
            }
        }
        return matches;
    }
}
