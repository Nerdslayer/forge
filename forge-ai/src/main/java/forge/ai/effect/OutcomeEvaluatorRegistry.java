package forge.ai.effect;

import java.util.List;

import forge.game.spellability.SpellAbility;

/** Shared routing for consequence outcomes supported by effect relationship analysis. */
final class OutcomeEvaluatorRegistry {
    private static final List<OutcomeEvaluator> EVALUATORS = List.of(
            CardDrawOutcomeEvaluator.INSTANCE,
            ManaOutcomeEvaluator.INSTANCE,
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
        for (final OutcomeEvaluator evaluator : EVALUATORS) {
            if (evaluator.supports(outcome)) {
                return evaluator;
            }
        }
        return null;
    }
}
