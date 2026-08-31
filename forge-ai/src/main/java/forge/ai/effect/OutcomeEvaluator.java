package forge.ai.effect;

import forge.game.spellability.SpellAbility;

/**
 * Evaluates the marginal value of one supported consequence outcome independently of the event
 * that caused it.
 */
interface OutcomeEvaluator {
    /** Returns whether this evaluator can safely value the supplied outcome form. */
    boolean supports(SpellAbility outcome);

    /**
     * Returns the signed value of one resolution from the evaluating AI's perspective. Positive
     * values make an opposing source more threatening; negative values make it beneficial.
     */
    int evaluateOutcome(SpellAbility outcome, OutcomeEvaluationContext context);
}
