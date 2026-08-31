package forge.ai.effect;

import java.util.function.ToIntFunction;

import forge.ai.ComputerUtilCard;
import forge.game.card.Card;
import forge.game.spellability.SpellAbility;

/** Shared aggregation and perspective handling for analysis-only card-state changes. */
final class CardStateDeltaEvaluator {
    private CardStateDeltaEvaluator() {
    }

    static int evaluate(final SpellAbility outcome, final OutcomeEvaluationContext context,
            final AffectedCardResolver.Resolution resolution,
            final ToIntFunction<Card> cardDeltaEvaluator) {
        if (resolution.cards().isEmpty()) {
            return 0;
        }
        if (resolution.chooseOne()) {
            return chooseBest(outcome, context, resolution, cardDeltaEvaluator);
        }

        int value = 0;
        for (final AffectedCardResolver.WeightedCard affected : resolution.cards()) {
            value = EffectMath.add(value, EffectMath.multiply(
                    affected.occurrences(), cardDeltaEvaluator.applyAsInt(affected.card())));
        }
        return value;
    }

    static int evaluateChange(final OutcomeEvaluationContext context,
            final Card original, final Card changed) {
        final int before = ComputerUtilCard.evaluatePermanent(context.evaluatingAi(), original);
        final int after = ComputerUtilCard.evaluatePermanent(context.evaluatingAi(), changed);
        final int delta = EffectMath.subtract(after, before);
        return original.getController().isOpponentOf(context.evaluatingAi())
                ? delta : EffectMath.negate(delta);
    }

    private static int chooseBest(final SpellAbility outcome,
            final OutcomeEvaluationContext context,
            final AffectedCardResolver.Resolution resolution,
            final ToIntFunction<Card> cardDeltaEvaluator) {
        final boolean chooserIsOpponent = outcome.getActivatingPlayer()
                .isOpponentOf(context.evaluatingAi());
        int best = chooserIsOpponent ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        for (final AffectedCardResolver.WeightedCard affected : resolution.cards()) {
            final int value = cardDeltaEvaluator.applyAsInt(affected.card());
            best = chooserIsOpponent ? Math.max(best, value) : Math.min(best, value);
        }
        if (best == Integer.MIN_VALUE || best == Integer.MAX_VALUE) {
            return 0;
        }
        return best;
    }
}
