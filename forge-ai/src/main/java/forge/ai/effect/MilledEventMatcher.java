package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.trigger.TriggerType;

/** Matches individual and batch mill events against Forge's mill triggers. */
final class MilledEventMatcher implements EffectEventMatcher {
    static final MilledEventMatcher INSTANCE = new MilledEventMatcher();

    // TODO(effect analysis): Add characteristic-aware matching once hidden library cards can be
    // estimated safely, and support cause, replacement, limited, and aggregate mill conditions.

    private MilledEventMatcher() {
    }

    @Override
    public List<EffectMatch> match(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.type() != EffectType.CARD_MILLED
                || consequence.observedType() != EffectType.CARD_MILLED) {
            return List.of();
        }
        final TriggerType mode = consequence.trigger().getMode();
        return mode == TriggerType.MilledOnce || mode == TriggerType.MilledAll
                ? matchBatches(production, consequence) : matchIndividuals(production, consequence);
    }

    private static List<EffectMatch> matchIndividuals(final EffectProduction production,
            final EffectConsequence consequence) {
        final List<EffectMatch> matches = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            for (final EffectEvent.Subject subject : event.subjects()) {
                if (!(subject.value() instanceof Card card)) {
                    continue;
                }
                final Map<AbilityKey, Object> parameters = new EnumMap<>(AbilityKey.class);
                parameters.putAll(event.triggerParameters());
                parameters.put(AbilityKey.Card, card);
                if (EffectEventMatchUtils.passes(consequence, parameters)) {
                    matches.add(new EffectMatch(new EffectEvent(event.type(), event.player(),
                            List.of(new EffectEvent.Subject(card, 1)), parameters),
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
            final Map<AbilityKey, Object> parameters = new EnumMap<>(AbilityKey.class);
            parameters.putAll(event.triggerParameters());
            parameters.put(AbilityKey.Cards, cards);
            if (EffectEventMatchUtils.passes(consequence, parameters)) {
                matches.add(new EffectMatch(new EffectEvent(event.type(), event.player(),
                        event.subjects(), parameters), 1));
            }
        }
        return matches;
    }
}
