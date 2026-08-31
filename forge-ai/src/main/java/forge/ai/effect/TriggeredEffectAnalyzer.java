package forge.ai.effect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;
import forge.game.zone.ZoneType;

/** Coordinates first-order relationships between normalized events and triggered consequences. */
final class TriggeredEffectAnalyzer {
    private TriggeredEffectAnalyzer() {
    }

    static Map<Card, Integer> evaluateRelationships(final Player evaluatingAi,
            final Iterable<Card> candidates) {
        if (evaluatingAi == null || candidates == null) {
            return Collections.emptyMap();
        }

        final Set<Player> analyzedControllers = findAnalyzedControllers(evaluatingAi, candidates);
        if (analyzedControllers.isEmpty()) {
            return Collections.emptyMap();
        }

        final List<EffectProduction> productions = new ArrayList<>();
        final Map<EffectType, List<EffectConsequence>> consequences = new EnumMap<>(EffectType.class);
        extractEffects(analyzedControllers, productions, consequences);

        final Map<Card, Integer> values = new HashMap<>();
        for (final EffectProduction production : productions) {
            final EffectEventMatcher matcher = EffectEventMatcherRegistry.find(production.type());
            if (matcher == null) {
                continue;
            }
            for (final EffectConsequence consequence : consequences.getOrDefault(
                    production.type(), List.of())) {
                final int relationshipValue = evaluateRelationship(
                        evaluatingAi, production, consequence, matcher);
                if (relationshipValue == 0) {
                    continue;
                }
                addSaturated(values, production.source(), relationshipValue);
                if (production.source() != consequence.source()) {
                    addSaturated(values, consequence.source(), relationshipValue);
                }
            }
        }
        return values;
    }

    private static Set<Player> findAnalyzedControllers(final Player evaluatingAi,
            final Iterable<Card> candidates) {
        final Set<Player> controllers = new LinkedHashSet<>();
        for (final Card candidate : candidates) {
            if (candidate != null && candidate.getController().isOpponentOf(evaluatingAi)) {
                controllers.add(candidate.getController());
            }
        }
        return controllers;
    }

    private static void extractEffects(final Iterable<Player> controllers,
            final List<EffectProduction> productions,
            final Map<EffectType, List<EffectConsequence>> consequences) {
        for (final Player controller : controllers) {
            for (final Card permanent : controller.getCardsIn(ZoneType.Battlefield)) {
                for (final SpellAbility ability : permanent.getSpellAbilities()) {
                    try {
                        productions.addAll(
                                EffectProductionExtractorRegistry.extract(permanent, ability));
                    } catch (final RuntimeException ignored) {
                        // Unknown or malformed card scripts must not disrupt AI decisions.
                    }
                }
                for (final Trigger trigger : permanent.getTriggers()) {
                    try {
                        productions.addAll(
                                EffectProductionExtractorRegistry.extract(permanent, trigger));
                        final EffectConsequence consequence =
                                EffectConsequenceExtractorRegistry.extract(permanent, trigger);
                        if (consequence != null) {
                            consequences.computeIfAbsent(consequence.observedType(), key -> new ArrayList<>())
                                    .add(consequence);
                        }
                    } catch (final RuntimeException ignored) {
                        // Unknown or malformed card scripts must not disrupt AI decisions.
                    }
                }
            }
        }
    }

    private static int evaluateRelationship(final Player evaluatingAi,
            final EffectProduction production, final EffectConsequence consequence,
            final EffectEventMatcher matcher) {
        try {
            int value = 0;
            for (final EffectMatch match : matcher.match(production, consequence)) {
                final SpellAbility outcome = consequence.outcome().copy(consequence.source(), false);
                outcome.setActivatingPlayer(consequence.source().getController());
                outcome.resetTargets();
                consequence.trigger().setTriggeringObjects(outcome, match.event().triggerParameters());
                final int outcomeValue = consequence.outcomeEvaluator().evaluateOutcome(
                        outcome, new OutcomeEvaluationContext(evaluatingAi, match.event()));
                value = EffectMath.add(value, EffectMath.multiply(match.resolutions(), outcomeValue));
            }
            return EffectMath.multiply(production.expectedBatches(), value);
        } catch (final RuntimeException ignored) {
            return 0;
        }
    }

    private static void addSaturated(final Map<Card, Integer> values, final Card card,
            final int amount) {
        final int result = EffectMath.add(values.getOrDefault(card, 0), amount);
        if (result == 0) {
            values.remove(card);
        } else {
            values.put(card, result);
        }
    }
}
