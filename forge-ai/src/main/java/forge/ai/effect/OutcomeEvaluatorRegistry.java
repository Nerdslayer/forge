package forge.ai.effect;

import java.util.List;

import forge.game.spellability.SpellAbility;

/** Shared routing for consequence outcomes supported by effect relationship analysis. */
final class OutcomeEvaluatorRegistry {
    private static final List<OutcomeEvaluator> EVALUATORS = List.of(
            CardDrawOutcomeEvaluator.INSTANCE,
            DiscardOutcomeEvaluator.INSTANCE,
            ManaOutcomeEvaluator.INSTANCE,
            LifeOutcomeEvaluator.INSTANCE,
            PlayerDamageOutcomeEvaluator.INSTANCE,
            TargetedDamageOutcomeEvaluator.INSTANCE,
            CounterOutcomeEvaluator.INSTANCE,
            CopiedPermanentOutcomeEvaluator.INSTANCE,
            CreatureTokenOutcomeEvaluator.INSTANCE,
            AnimationOutcomeEvaluator.INSTANCE,
            KeywordOutcomeEvaluator.INSTANCE,
            PermanentPtOutcomeEvaluator.INSTANCE,
            PermanentRemovalOutcomeEvaluator.INSTANCE,
            SacrificeOutcomeEvaluator.INSTANCE,
            ControlChangeOutcomeEvaluator.INSTANCE,
            AttachmentOutcomeEvaluator.INSTANCE,
            StateChangeOutcomeEvaluator.INSTANCE);

    private OutcomeEvaluatorRegistry() {
    }

    static OutcomeEvaluator find(final SpellAbility outcome) {
        if (SpellAbilityOutcomePlanner.supports(outcome)) {
            return PlannedOutcomeEvaluator.INSTANCE;
        }
        // Never fall back to mandatory atomic value after failing optional semantics validation.
        if (outcome.hasParam("Optional") || outcome.hasParam("OptionalDecider")
                || outcome.getTrigger() != null && outcome.getTrigger().hasParam("OptionalDecider")) {
            return null;
        }
        return findAtomic(outcome);
    }

    static OutcomeEvaluator findAtomic(final SpellAbility outcome) {
        for (final OutcomeEvaluator evaluator : EVALUATORS) {
            if (evaluator.supports(outcome)) {
                return evaluator;
            }
        }
        return null;
    }
}
