package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import forge.ai.PlayerResourceValueEvaluator;
import forge.game.player.Player;

/** Converts projected player life totals into consequence value. */
final class PlayerLifeOutcomeValue {
    private PlayerLifeOutcomeValue() {
    }

    static int evaluate(final OutcomeEvaluationContext context, final Map<Player, Integer> projectedLife) {
        if (context.state() == null) { return evaluate(context.evaluatingAi(), projectedLife); }
        final int before = evaluate(context.evaluatingAi(), context.state().life);
        context.state().life.putAll(projectedLife);
        return EffectMath.subtract(evaluate(context.evaluatingAi(), context.state().life), before);
    }

    static int evaluate(final Player evaluatingAi,
            final Map<Player, Integer> projectedLife) {
        int evaluatingAiUtility = 0;
        final List<Player> eliminated = new ArrayList<>();
        for (final Map.Entry<Player, Integer> projection : projectedLife.entrySet()) {
            final Player recipient = projection.getKey();
            final int playerUtility = PlayerResourceValueEvaluator.evaluateLifeChange(
                    recipient.getLife(), projection.getValue());
            evaluatingAiUtility = EffectMath.add(evaluatingAiUtility,
                    recipient.isOpponentOf(evaluatingAi)
                            ? EffectMath.negate(playerUtility) : playerUtility);
            if (projection.getValue() <= 0
                    && !recipient.cantLoseForZeroOrLessLife()) {
                eliminated.add(recipient);
            }
        }
        evaluatingAiUtility = EffectMath.add(evaluatingAiUtility,
                PlayerResourceValueEvaluator.evaluateEliminations(
                        evaluatingAi, eliminated));
        return EffectMath.negate(evaluatingAiUtility);
    }

    static int saturatedSubtract(final int left, final int right) {
        final long result = (long) left - right;
        return result <= Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) result;
    }
}
