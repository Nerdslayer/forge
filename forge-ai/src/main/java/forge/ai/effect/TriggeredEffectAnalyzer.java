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
    // TODO(effect analysis): Traverse bounded consequence chains, handle cycles, and analyze
    // relevant allied effects and non-battlefield zones. This pass is intentionally first-order
    // and starts from battlefield cards controlled by removal candidates' opponents.
    private TriggeredEffectAnalyzer() {
    }

    static Map<Card, Integer> evaluateRelationships(final Player evaluatingAi,
            final Iterable<Card> candidates, final EffectAnalysisTrace trace) {
        if (evaluatingAi == null || candidates == null) {
            return Collections.emptyMap();
        }

        final Set<Player> analyzedControllers = findAnalyzedControllers(evaluatingAi, candidates);
        if (analyzedControllers.isEmpty()) {
            return Collections.emptyMap();
        }

        final List<EffectProduction> productions = new ArrayList<>();
        final Map<EffectType, List<EffectConsequence>> consequences = new EnumMap<>(EffectType.class);
        extractEffects(analyzedControllers, productions, consequences, trace);

        final Map<Card, Integer> values = new HashMap<>();
        for (final EffectProduction production : productions) {
            final EffectEventMatcher matcher = EffectEventMatcherRegistry.find(production.type());
            if (matcher == null) {
                continue;
            }
            for (final EffectConsequence consequence : consequences.getOrDefault(
                    production.type(), List.of())) {
                final int relationshipValue = evaluateRelationship(
                        evaluatingAi, production, consequence, matcher, trace);
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
            final Map<EffectType, List<EffectConsequence>> consequences,
            final EffectAnalysisTrace trace) {
        for (final Player controller : controllers) {
            for (final Card permanent : controller.getCardsIn(ZoneType.Battlefield)) {
                try {
                    final List<EffectProduction> extracted =
                            EffectProductionExtractorRegistry.extract(permanent);
                    productions.addAll(extracted);
                    extracted.forEach(trace::production);
                } catch (final RuntimeException ignored) {
                    // Unknown or malformed card state must not disrupt AI decisions.
                }
                for (final SpellAbility ability : permanent.getSpellAbilities()) {
                    try {
                        final List<EffectProduction> extracted =
                                EffectProductionExtractorRegistry.extract(permanent, ability);
                        productions.addAll(extracted);
                        extracted.forEach(trace::production);
                    } catch (final RuntimeException ignored) {
                        // Unknown or malformed card scripts must not disrupt AI decisions.
                    }
                }
                for (final Trigger trigger : permanent.getTriggers()) {
                    try {
                        final List<EffectProduction> extracted =
                                EffectProductionExtractorRegistry.extract(permanent, trigger);
                        productions.addAll(extracted);
                        extracted.forEach(trace::production);
                        final EffectConsequence consequence =
                                EffectConsequenceExtractorRegistry.extract(permanent, trigger);
                        if (consequence != null) {
                            consequences.computeIfAbsent(consequence.observedType(), key -> new ArrayList<>())
                                    .add(consequence);
                            trace.consequence(consequence);
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
            final EffectEventMatcher matcher, final EffectAnalysisTrace trace) {
        try {
            int value = 0;
            for (final EffectMatch match : matcher.match(production, consequence)) {
                final SpellAbility outcome = consequence.outcome().copy(consequence.source(), false);
                outcome.setActivatingPlayer(consequence.source().getController());
                outcome.resetTargets();
                consequence.trigger().setTriggeringObjects(outcome, match.event().triggerParameters());
                final int outcomeValue = consequence.outcomeEvaluator().evaluateOutcome(
                        outcome, new OutcomeEvaluationContext(evaluatingAi, match.event()));
                final int contribution = EffectMath.multiply(match.resolutions(), outcomeValue);
                trace.triggeredMatch(
                        production, consequence, match, outcomeValue, contribution);
                value = EffectMath.add(value, contribution);
            }
            final int totalValue = EffectMath.multiply(production.expectedBatches(), value);
            if (totalValue != 0) {
                trace.triggeredRelationship(production, consequence, value, totalValue);
            }
            return totalValue;
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
