package forge.ai.effect;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import forge.game.ability.AbilityKey;
import forge.game.card.Card;
import forge.game.card.CardCollection;
import forge.game.trigger.TriggerType;

/** Matches sacrifice events against Sacrificed and SacrificedOnce triggers. */
final class SacrificeEventMatcher implements EffectEventMatcher {
    static final SacrificeEventMatcher INSTANCE = new SacrificeEventMatcher();

    // TODO(effect analysis): Support cross-player batches, WhileKeyword, richer cause filters,
    // replacement-modified sacrifices, and the additional trigger parameters currently rejected
    // by TriggeredConsequenceExtractor.

    private SacrificeEventMatcher() {
    }

    @Override
    public List<EffectMatch> match(final EffectProduction production,
            final EffectConsequence consequence) {
        if (production.type() != EffectType.SACRIFICED
                || consequence.observedType() != EffectType.SACRIFICED) {
            return List.of();
        }
        return consequence.trigger().getMode() == TriggerType.SacrificedOnce
                ? matchBatch(production, consequence) : matchIndividual(production, consequence);
    }

    private static List<EffectMatch> matchIndividual(final EffectProduction production,
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

    private static List<EffectMatch> matchBatch(final EffectProduction production,
            final EffectConsequence consequence) {
        final CardCollection cards = new CardCollection();
        final List<EffectEvent.Subject> subjects = new ArrayList<>();
        for (final EffectEvent event : production.events()) {
            subjects.addAll(event.subjects());
            cards.addAll(event.expandedCardSubjects());
        }
        if (cards.isEmpty()) {
            return List.of();
        }
        final Map<AbilityKey, Object> runParams = new EnumMap<>(AbilityKey.class);
        runParams.putAll(production.events().get(0).triggerParameters());
        runParams.put(AbilityKey.Cards, cards);
        if (!EffectEventMatchUtils.passes(consequence, runParams)) {
            return List.of();
        }
        return List.of(new EffectMatch(new EffectEvent(EffectType.SACRIFICED,
                production.events().get(0).player(), subjects, runParams), 1));
    }
}
