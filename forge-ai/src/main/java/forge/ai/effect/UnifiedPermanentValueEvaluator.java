package forge.ai.effect;

import forge.ai.ComputerUtilCard;
import forge.ai.CreatureEvaluator;
import forge.game.card.Card;
import forge.game.player.Player;

/** Live permanent presence score used only by unified and effect-analysis decisions. */
final class UnifiedPermanentValueEvaluator {
    private static final CreatureEvaluator CREATURE_EVALUATOR =
            new CreatureEvaluator(CreatureEvaluator.ValuationScale.UNIFIED);

    private UnifiedPermanentValueEvaluator() {
    }

    static int evaluate(final Player evaluatingAi, final Card card) {
        return card.isCreature() ? CREATURE_EVALUATOR.evaluateCreature(card)
                : ComputerUtilCard.evaluatePermanent(evaluatingAi, card);
    }
}
