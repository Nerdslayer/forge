package forge.ai.effect;

import java.util.Set;

import forge.game.ability.ApiType;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;

/** Shared event binding and scoring, with a bounded public-only combat preparation policy. */
final class TriggeredRelationshipEvaluator {
    private TriggeredRelationshipEvaluator() { }

    record Evaluation(int value, boolean supported) { }

    static Evaluation evaluate(final Player evaluatingAi, final EffectProduction production,
            final EffectConsequence consequence, final EffectEventMatcher matcher,
            final EffectAnalysisTrace trace, final boolean fixedPublicOnly) {
        final OutcomeEvaluator evaluator = fixedPublicOnly
                ? OutcomeEvaluatorRegistry.findAtomic(consequence.outcome()) : consequence.outcomeEvaluator();
        if (fixedPublicOnly && (!isFixedPublicOutcome(consequence.outcome())
                || consequence.trigger().hasParam("OptionalDecider") || evaluator == null)) {
            return new Evaluation(0, false);
        }
        try {
            int value = 0;
            for (final EffectMatch match : matcher.match(production, consequence)) {
                final SpellAbility outcome = consequence.outcome().copy(consequence.source(), false);
                outcome.setActivatingPlayer(consequence.source().getController());
                outcome.resetTargets();
                consequence.trigger().setTriggeringObjects(outcome, match.event().triggerParameters());
                final int outcomeValue;
                if (evaluator == PlannedOutcomeEvaluator.INSTANCE) {
                    final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(
                            outcome, evaluatingAi, match.event());
                    trace.outcomePlan(plan);
                    outcomeValue = PlannedOutcomeEvaluator.score(plan);
                } else {
                    final OutcomeState state = fixedPublicOnly ? new OutcomeState() : null;
                    outcomeValue = evaluator.evaluateOutcome(outcome,
                            new OutcomeEvaluationContext(evaluatingAi, match.event(), state));
                    if (state != null && state.unsupported) { return new Evaluation(0, false); }
                }
                final int contribution = EffectMath.multiply(match.resolutions(), outcomeValue);
                trace.triggeredMatch(production, consequence, match, outcomeValue, contribution);
                value = EffectMath.add(value, contribution);
            }
            final int totalValue = EffectMath.multiply(production.expectedBatches(), value);
            if (totalValue != 0) { trace.triggeredRelationship(production, consequence, value, totalValue); }
            return new Evaluation(totalValue, true);
        } catch (final RuntimeException ignored) {
            return new Evaluation(0, false);
        }
    }

    /** Reject dynamic quantities/recipients before any live public preparation evaluation. */
    static boolean isFixedPublicOutcome(final SpellAbility outcome) {
        if (outcome == null || outcome.getSubAbility() != null || outcome.usesTargeting()
                || outcome.hasParam("OptionalDecider") || EffectAbilityUtils.hasUnsupportedControlFlow(outcome)) {
            return false;
        }
        final ApiType api = outcome.getApi();
        final String amount;
        if (api == ApiType.Draw) {
            amount = outcome.getParamOrDefault("NumCards", "1");
        } else if (api == ApiType.GainLife || api == ApiType.LoseLife) {
            amount = outcome.getParamOrDefault("LifeAmount", "1");
        } else if (api == ApiType.PutCounter) {
            if (!outcome.hasParam("CounterType") || outcome.hasParam("CounterTypes")
                    || outcome.hasParam("EachFromSource")) { return false; }
            amount = outcome.getParamOrDefault("CounterNum", "1");
        } else { return false; }
        // TODO: Admit projected targets, choices, sequences, optional/dynamic amounts, and
        // additional atomic outcomes after their preparation cannot recurse into combat AI.
        return amount.matches("\\d+") && Set.of("Self", "You", "Opponent").contains(
                outcome.getParamOrDefault("Defined", api == ApiType.PutCounter ? "Self" : "You"));
    }
}
