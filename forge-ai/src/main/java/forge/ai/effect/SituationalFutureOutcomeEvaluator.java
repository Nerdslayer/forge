package forge.ai.effect;

import forge.game.card.Card;
import forge.game.player.Player;
import forge.game.spellability.SpellAbility;
import forge.game.trigger.Trigger;

/**
 * Re-evaluates a directly inspectable future trigger outcome against the live game state.
 *
 * <p>This is intentionally a narrow bridge. Intrinsic analysis still supplies the occurrence
 * estimate and the conservative reference fallback; this class only replaces the per-resolution
 * outcome value when the live trigger can be copied and the shared outcome planner can evaluate
 * it without hidden information.</p>
 */
final class SituationalFutureOutcomeEvaluator {
    // TODO(effect analysis): Extend live refinement to activations and event-triggered abilities,
    // and report target/choice availability separately when a branch cannot be planned safely.
    private SituationalFutureOutcomeEvaluator() {
    }

    record Evaluation(boolean supported, int value, String reason) {
        static Evaluation supported(final int value, final String reason) {
            return new Evaluation(true, value, reason);
        }

        static Evaluation unsupported(final String reason) {
            return new Evaluation(false, 0, reason);
        }
    }

    static Evaluation evaluateScheduledTrigger(final Player evaluatingAi, final Card source,
            final String path) {
        final Trigger trigger = EffectAbilityUtils.triggerAtPath(source, path);
        if (trigger == null) {
            return Evaluation.unsupported("live trigger could not be found");
        }
        final SpellAbility outcome = EffectAbilityUtils.copyTriggerOutcome(source, trigger);
        if (outcome == null) {
            return Evaluation.unsupported("live trigger has no inspectable outcome");
        }
        if (source.getController() == null) {
            return Evaluation.unsupported("live trigger has no controller");
        }
        // Trigger execution normally supplies this field. The evaluator is only inspecting a
        // copied outcome, so bind the same controller explicitly for planner recipient resolution.
        outcome.setActivatingPlayer(source.getController());
        final OutcomePlan<OutcomeState> plan = SpellAbilityOutcomePlanner.evaluate(outcome,
                evaluatingAi);
        if (!plan.complete()) {
            return Evaluation.unsupported("live outcome is incomplete: " + plan.reason());
        }
        return Evaluation.supported(PlannedOutcomeEvaluator.score(plan),
                "Live scheduled outcome value (reference occurrence estimate retained)");
    }
}
