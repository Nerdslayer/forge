package forge.ai.effect;

import java.util.Map;
import java.util.function.ToIntFunction;

import forge.ai.ComputerUtilCard;
import forge.game.card.Card;
import forge.game.card.CardCopyService;
import forge.game.player.Player;
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
        return applyPerspective(context, original, delta);
    }

    static int evaluateDeparture(final OutcomeEvaluationContext context, final Card original) {
        final int value = ComputerUtilCard.evaluatePermanent(context.evaluatingAi(), original);
        return applyPerspective(context, original, EffectMath.negate(value));
    }

    static int evaluateControlChange(final OutcomeEvaluationContext context,
            final Card original, final Player newController) {
        if (original.getController() == newController) {
            return 0;
        }

        final Card changed = CardCopyService.getLKICopy(original);
        changed.setController(newController, 0);
        return evaluateBoardChanges(context, Map.of(original, changed));
    }

    static int evaluateBoardChanges(final OutcomeEvaluationContext context,
            final Map<Card, Card> changes) {
        int before = 0;
        int after = 0;
        for (final Map.Entry<Card, Card> change : changes.entrySet()) {
            final Card original = change.getKey();
            before = EffectMath.add(before,
                    signedBoardValue(context, original, original.getController()));
            final Card changed = change.getValue();
            if (changed != null) {
                after = EffectMath.add(after,
                        signedBoardValue(context, changed, changed.getController()));
            }
        }
        return EffectMath.subtract(before, after);
    }

    private static int applyPerspective(final OutcomeEvaluationContext context,
            final Card original, final int delta) {
        return original.getController().isOpponentOf(context.evaluatingAi())
                ? delta : EffectMath.negate(delta);
    }

    private static int signedBoardValue(final OutcomeEvaluationContext context,
            final Card card, final Player controller) {
        final int value = ComputerUtilCard.evaluatePermanent(context.evaluatingAi(), card);
        return controller.isOpponentOf(context.evaluatingAi())
                ? EffectMath.negate(value) : value;
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
