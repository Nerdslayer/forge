package forge.ai.effect;

import forge.game.spellability.SpellAbility;

/** Compatibility boundary: existing relationship scores are threat values, not AI utility. */
final class PlannedOutcomeEvaluator implements OutcomeEvaluator {
    static final PlannedOutcomeEvaluator INSTANCE = new PlannedOutcomeEvaluator();

    private PlannedOutcomeEvaluator() { }

    @Override
    public boolean supports(final SpellAbility outcome) {
        return SpellAbilityOutcomePlanner.supports(outcome);
    }

    @Override
    public int evaluateOutcome(final SpellAbility outcome, final OutcomeEvaluationContext context) {
        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(
                outcome, context.evaluatingAi(), context.event());
        return score(plan);
    }

    static int score(final OutcomePlan<OutcomeState> plan) {
        return plan.supported() ? (int) Math.max(Integer.MIN_VALUE,
                Math.min(Integer.MAX_VALUE, Math.round(plan.value()))) : 0;
    }
}
