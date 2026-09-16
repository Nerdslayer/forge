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
        return sumContributions(evaluateContributions(evaluatingAi, candidates, trace));
    }

    static Map<Card, List<AbilityValueContribution>> evaluateContributions(
            final Player evaluatingAi, final Iterable<Card> candidates,
            final EffectAnalysisTrace trace) {
        return evaluateContributions(evaluatingAi, candidates, null, trace);
    }

    static Map<Card, List<AbilityValueContribution>> evaluateContributions(
            final Player evaluatingAi, final Iterable<Card> candidates,
            final SpellAbility removalAbility, final EffectAnalysisTrace trace) {
        if (evaluatingAi == null || candidates == null) {
            return Collections.emptyMap();
        }

        final List<Card> candidateList = copyCandidates(candidates);
        final Set<Player> analyzedControllers = findAnalyzedControllers(evaluatingAi, candidateList);
        if (analyzedControllers.isEmpty()) {
            return Collections.emptyMap();
        }

        final List<EffectProduction> productions = new ArrayList<>();
        final Map<EffectType, List<EffectConsequence>> consequences = new EnumMap<>(EffectType.class);
        // A removal target can profit when the evaluating AI performs a known library or
        // filtering action (for example, an opposing Archivist of Oghma). Model only these
        // normalized productions from the AI's known battlefield abilities; do not broaden this
        // pass to all allied relationships.
        extractPlayerProductions(evaluatingAi, evaluatingAi,
                Set.of(EffectType.CARD_SEARCHED_OR_SELECTED, EffectType.SCRIED_OR_SURVEILLED),
                productions, trace);
        addNormalDrawStep(evaluatingAi, productions, trace);
        extractEffects(evaluatingAi, analyzedControllers, productions, consequences, trace);
        final List<EffectProduction> targetEvents = BecameTargetProductionExtractor.extract(
                evaluatingAi, removalAbility, candidateList);
        productions.addAll(targetEvents);
        targetEvents.forEach(trace::production);

        final Map<Card, List<AbilityValueContribution>> values = new HashMap<>();
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
                final String opportunityKey = production.ability().path() + "->"
                        + consequence.ability().path() + ":" + production.type();
                addContribution(values, AbilityValueContribution.counted(
                        production.source(), production.source(), production.ability(),
                        consequence.source(), consequence.ability(), AbilityValueKind.KNOWN_RELATIONSHIP,
                        relationshipValue, opportunityKey,
                        "Known " + production.type() + " relationship to "
                                + consequence.source().getName()));
                if (production.source() != consequence.source()) {
                    addContribution(values, AbilityValueContribution.counted(
                            consequence.source(), production.source(), production.ability(),
                            consequence.source(), consequence.ability(), AbilityValueKind.KNOWN_RELATIONSHIP,
                            relationshipValue, opportunityKey,
                            "Known " + production.type() + " relationship from "
                                    + production.source().getName()));
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

    private static void extractEffects(final Player evaluatingAi,
            final Iterable<Player> controllers,
            final List<EffectProduction> productions,
            final Map<EffectType, List<EffectConsequence>> consequences,
            final EffectAnalysisTrace trace) {
        for (final Player controller : controllers) {
            addNormalDrawStep(controller, productions, trace);
            // Land plays are player-wide opportunities, not one independent production per
            // battlefield land. The synthetic source is intentionally outside candidate cards;
            // matching consequences still receive their normal relationship contribution.
            final List<EffectProduction> landPlays = LandPlayedProductionExtractor.extract(controller);
            productions.addAll(landPlays);
            landPlays.forEach(trace::production);
            for (final Card permanent : controller.getCardsIn(ZoneType.Battlefield)) {
                try {
                    final List<EffectProduction> extracted =
                            EffectProductionExtractorRegistry.extract(evaluatingAi, permanent);
                    productions.addAll(extracted);
                    extracted.forEach(trace::production);
                } catch (final RuntimeException ignored) {
                    // Unknown or malformed card state must not disrupt AI decisions.
                }
                for (final SpellAbility ability : permanent.getSpellAbilities()) {
                    try {
                        final List<EffectProduction> extracted =
                                EffectProductionExtractorRegistry.extract(
                                        evaluatingAi, permanent, ability);
                        productions.addAll(extracted);
                        extracted.forEach(trace::production);
                        if (ability.isActivatedAbility() && !extracted.isEmpty()) {
                            trace.activationEstimate(permanent, ability,
                                    ActivatedAbilityUseEvaluator.estimate(permanent, ability));
                        }
                    } catch (final RuntimeException ignored) {
                        // Unknown or malformed card scripts must not disrupt AI decisions.
                    }
                }
                for (final Trigger trigger : permanent.getTriggers()) {
                    try {
                        final List<EffectProduction> extracted =
                                EffectProductionExtractorRegistry.extract(
                                        evaluatingAi, permanent, trigger);
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

    private static void addNormalDrawStep(final Player player,
            final List<EffectProduction> productions, final EffectAnalysisTrace trace) {
        final List<EffectProduction> drawSteps =
                CardDrawProductionExtractor.extractNormalDrawStep(player);
        productions.addAll(drawSteps);
        drawSteps.forEach(trace::production);
    }

    private static void extractPlayerProductions(final Player evaluatingAi,
            final Player controller, final Set<EffectType> includedTypes,
            final List<EffectProduction> productions, final EffectAnalysisTrace trace) {
        if (controller == null) {
            return;
        }
        for (final Card permanent : controller.getCardsIn(ZoneType.Battlefield)) {
            for (final SpellAbility ability : permanent.getSpellAbilities()) {
                try {
                    addPlayerProductions(evaluatingAi, permanent, ability, includedTypes,
                            productions, trace);
                } catch (final RuntimeException ignored) {
                    // Unknown or malformed card scripts must not disrupt AI decisions.
                }
            }
            for (final Trigger trigger : permanent.getTriggers()) {
                try {
                    addPlayerProductions(evaluatingAi, permanent, trigger, includedTypes,
                            productions, trace);
                } catch (final RuntimeException ignored) {
                    // Unknown or malformed card scripts must not disrupt AI decisions.
                }
            }
        }
    }

    private static void addPlayerProductions(final Player evaluatingAi, final Card source,
            final SpellAbility ability, final Set<EffectType> includedTypes,
            final List<EffectProduction> productions, final EffectAnalysisTrace trace) {
        final List<EffectProduction> extracted = EffectProductionExtractorRegistry.extract(
                evaluatingAi, source, ability);
        for (final EffectProduction production : extracted) {
            if (includedTypes.contains(production.type())) {
                productions.add(production);
                trace.production(production);
            }
        }
    }

    private static void addPlayerProductions(final Player evaluatingAi, final Card source,
            final Trigger trigger, final Set<EffectType> includedTypes,
            final List<EffectProduction> productions, final EffectAnalysisTrace trace) {
        final List<EffectProduction> extracted = EffectProductionExtractorRegistry.extract(
                evaluatingAi, source, trigger);
        for (final EffectProduction production : extracted) {
            if (includedTypes.contains(production.type())) {
                productions.add(production);
                trace.production(production);
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
                final int outcomeValue;
                if (consequence.outcomeEvaluator() == PlannedOutcomeEvaluator.INSTANCE) {
                    final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(
                            outcome, evaluatingAi, match.event());
                    trace.outcomePlan(plan);
                    outcomeValue = PlannedOutcomeEvaluator.score(plan);
                } else {
                    outcomeValue = consequence.outcomeEvaluator().evaluateOutcome(
                            outcome, new OutcomeEvaluationContext(evaluatingAi, match.event()));
                }
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

    private static void addContribution(final Map<Card, List<AbilityValueContribution>> values,
            final AbilityValueContribution contribution) {
        values.computeIfAbsent(contribution.candidate(), key -> new ArrayList<>()).add(contribution);
    }

    private static Map<Card, Integer> sumContributions(
            final Map<Card, List<AbilityValueContribution>> contributions) {
        final Map<Card, Integer> values = new HashMap<>();
        for (final Map.Entry<Card, List<AbilityValueContribution>> entry : contributions.entrySet()) {
            int value = 0;
            for (final AbilityValueContribution contribution : entry.getValue()) {
                if (contribution.counted()) {
                    value = EffectMath.add(value, contribution.value());
                }
            }
            if (value != 0) {
                values.put(entry.getKey(), value);
            }
        }
        return values;
    }

    private static List<Card> copyCandidates(final Iterable<Card> candidates) {
        final List<Card> result = new ArrayList<>();
        candidates.forEach(candidate -> {
            if (candidate != null) {
                result.add(candidate);
            }
        });
        return result;
    }
}
