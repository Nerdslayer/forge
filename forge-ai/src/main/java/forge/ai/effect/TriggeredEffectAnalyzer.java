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
        final Map<Card, List<AbilityValueContribution>> values = new HashMap<>();
        merge(values, evaluateBaseline(evaluatingAi, candidates, trace));
        merge(values, evaluateTargetOverlay(evaluatingAi, candidates, removalAbility, trace));
        return values;
    }

    static Map<Card, List<AbilityValueContribution>> evaluateBaseline(
            final Player evaluatingAi, final Iterable<Card> candidates, final EffectAnalysisTrace trace) {
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
        // A removal target can profit when the evaluating AI performs a known action (for
        // example, an opposing Archivist of Oghma or a cast-matters permanent). Model only
        // normalized productions from the evaluating AI's known cards/abilities; do not broaden
        // this pass to hidden opponent-hand actions.
        addKnownCastProductions(evaluatingAi, evaluatingAi, true, productions, trace);
        extractPlayerProductions(evaluatingAi, evaluatingAi,
                Set.of(EffectType.CARD_SEARCHED_OR_SELECTED, EffectType.SCRIED_OR_SURVEILLED),
                productions, trace);
        addNormalDrawStep(evaluatingAi, productions, trace);
        extractEffects(evaluatingAi, analyzedControllers, productions, consequences, trace);
        return evaluateProductions(evaluatingAi, productions, consequences, trace);
    }

    /** Target-caused opportunities depend on the current action and are never cached as baseline. */
    static Map<Card, List<AbilityValueContribution>> evaluateTargetOverlay(
            final Player evaluatingAi, final Iterable<Card> candidates,
            final SpellAbility removalAbility, final EffectAnalysisTrace trace) {
        if (evaluatingAi == null || candidates == null || removalAbility == null) {
            return Map.of();
        }
        final List<Card> candidateList = copyCandidates(candidates);
        final List<EffectProduction> productions = BecameTargetProductionExtractor.extract(
                evaluatingAi, removalAbility, candidateList);
        if (productions.isEmpty()) {
            return Map.of();
        }
        productions.forEach(trace::production);
        final Map<EffectType, List<EffectConsequence>> consequences = new EnumMap<>(EffectType.class);
        final List<Player> controllers = List.copyOf(findAnalyzedControllers(evaluatingAi, candidateList));
        final SituationalAnalysisSession session = SituationalAnalysisSession.current(evaluatingAi);
        if (session == null) {
            // Preserve the original standalone/Default extraction and its fallback behavior.
            for (final Player controller : controllers) {
                for (final Card source : controller.getCardsIn(ZoneType.Battlefield)) {
                    for (final Trigger trigger : source.getTriggers()) {
                        try {
                            addConsequence(consequences, EffectConsequenceExtractorRegistry.extract(source, trigger));
                        } catch (final RuntimeException ignored) {
                            SituationalAnalysisSession.noteFailure(evaluatingAi);
                        }
                    }
                }
            }
        } else {
            final PreparedConsequenceIndex index = session.consequenceIndex(candidateList, controllers, trace);
            final Set<EffectType> types = new LinkedHashSet<>();
            productions.forEach(production -> types.add(production.type()));
            for (final EffectType type : types) {
                for (final PreparedConsequenceIndex.Reference reference : index.forType(type)) {
                    try {
                        addConsequence(consequences, reference.bind());
                    } catch (final RuntimeException ignored) {
                        SituationalAnalysisSession.noteFailure(evaluatingAi);
                    }
                }
            }
        }
        return evaluateProductions(evaluatingAi, productions, consequences, trace);
    }

    private static void addConsequence(final Map<EffectType, List<EffectConsequence>> consequences,
            final EffectConsequence consequence) {
        if (consequence != null) {
            consequences.computeIfAbsent(consequence.observedType(), key -> new ArrayList<>()).add(consequence);
        }
    }

    private static Map<Card, List<AbilityValueContribution>> evaluateProductions(final Player evaluatingAi,
            final List<EffectProduction> productions,
            final Map<EffectType, List<EffectConsequence>> consequences, final EffectAnalysisTrace trace) {

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

    private static void merge(final Map<Card, List<AbilityValueContribution>> values,
            final Map<Card, List<AbilityValueContribution>> additions) {
        additions.forEach((card, entries) -> values.computeIfAbsent(card, key -> new ArrayList<>()).addAll(entries));
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
            addKnownCastProductions(evaluatingAi, controller, false, productions, trace);
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
                    SituationalAnalysisSession.noteFailure(evaluatingAi);
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
                        SituationalAnalysisSession.noteFailure(evaluatingAi);
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
                        SituationalAnalysisSession.noteFailure(evaluatingAi);
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

    private static void addKnownCastProductions(final Player evaluatingAi,
            final Player controller, final boolean includeHand,
            final List<EffectProduction> productions, final EffectAnalysisTrace trace) {
        final List<EffectProduction> casts = KnownCastProductionExtractor.extract(
                evaluatingAi, controller, includeHand);
        productions.addAll(casts);
        casts.forEach(trace::production);
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
                    SituationalAnalysisSession.noteFailure(evaluatingAi);
                    // Unknown or malformed card scripts must not disrupt AI decisions.
                }
            }
            for (final Trigger trigger : permanent.getTriggers()) {
                try {
                    addPlayerProductions(evaluatingAi, permanent, trigger, includedTypes,
                            productions, trace);
                } catch (final RuntimeException ignored) {
                    SituationalAnalysisSession.noteFailure(evaluatingAi);
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
        final TriggeredRelationshipEvaluator.Evaluation result = TriggeredRelationshipEvaluator.evaluate(
                evaluatingAi, production, consequence, matcher, trace, false);
        if (!result.supported()) {
            SituationalAnalysisSession.noteFailure(evaluatingAi);
        }
        return result.value();
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
