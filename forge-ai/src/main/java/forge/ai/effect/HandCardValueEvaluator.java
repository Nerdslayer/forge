package forge.ai.effect;

import java.util.ArrayList;
import java.util.List;

import forge.ai.CardDefinitionValueEvaluator;
import forge.ai.CardResourceValueEvaluator;
import forge.game.card.Card;
import forge.item.IPaperCard;

/**
 * Values a known or unknown card as a resource in hand.
 *
 * <p>This is intentionally different from evaluating a live permanent. A known card has no
 * current battlefield presence; its value is the board value it could produce after paying its
 * printed mana cost. The card opportunity cost is not charged again because the card is already
 * in the hand. For hidden cards, the shared marginal-card estimate remains the only honest value
 * available.</p>
 */
public final class HandCardValueEvaluator {
    private static final CardDefinitionValueEvaluator DEFINITION_EVALUATOR =
            new CardDefinitionValueEvaluator();

    private HandCardValueEvaluator() {
    }

    /** Returns the value of one additional unknown card in the supplied hand context. */
    public static int evaluateUnknownCard(final HandValuationContext context) {
        if (context == null) {
            return 0;
        }
        return CardResourceValueEvaluator.evaluateNextCard(context.totalHandSize() - 1);
    }

    /**
     * Evaluates a known card using the same definition evaluator used by the card creator.
     * Unsupported or incomplete definitions fall back to the unknown-card estimate rather than
     * leaking an artificially low value into a decision.
     */
    public static CardValueBreakdown evaluateKnownCard(final Card card,
            final HandValuationContext context) {
        if (card == null || context == null || !context.knows(card)) {
            return CardValueBreakdown.unavailable("The card is not known in this hand context.");
        }

        final IPaperCard paperCard = card.getPaperCard();
        if (paperCard == null || paperCard.getRules() == null) {
            return unknownFallback(context, "The known card has no evaluable definition.");
        }

        final CardDefinitionValueEvaluator.Evaluation evaluation;
        try {
            evaluation = DEFINITION_EVALUATOR.evaluate(paperCard.getRules(), paperCard.getEdition());
        } catch (final RuntimeException ex) {
            return unknownFallback(context, "The known card definition could not be evaluated.");
        }
        if (!evaluation.isComplete()) {
            return unknownFallback(context, "The known card definition is only partially supported.");
        }

        final int manaInvestment = evaluation.manaInvestment();
        final int netValue = evaluation.grossPointValue() - manaInvestment;
        final HandCardAccessEvaluator.Estimate access = HandCardAccessEvaluator.evaluate(card,
                context);
        final List<String> reasons = new ArrayList<>();
        reasons.add("Known card definition value " + evaluation.grossPointValue());
        reasons.add("Printed mana investment " + manaInvestment);
        reasons.add("Net hand value " + netValue);
        reasons.add("Hand has " + context.totalHandSize() + " cards, including "
                + context.knownCards().size() + " known");
        reasons.addAll(access.reasons());
        return new CardValueBreakdown(0, evaluation.grossPointValue(), 0, manaInvestment, 0,
                ValuationCompleteness.COMPLETE, reasons);
    }

    private static CardValueBreakdown unknownFallback(final HandValuationContext context,
            final String reason) {
        final int value = evaluateUnknownCard(context);
        return new CardValueBreakdown(0, value, 0, 0, 0, ValuationCompleteness.PARTIAL,
                List.of(reason, "Using unknown-card value " + value));
    }
}
