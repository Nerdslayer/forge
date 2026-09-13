package forge.ai;

/**
 * Shared estimates for card and mana resources on the permanent-evaluation scale.
 *
 * <p>This class deliberately contains only game-free resource values. Live hand-size and game
 * state adjustments belong in the callers that have that information.</p>
 */
public final class CardResourceValueEvaluator {
    /** Approximate value of one unrestricted mana. */
    public static final int MANA_VALUE = 35;
    private static final int EMPTY_HAND_CARD_VALUE = 140;
    private static final int CARD_VALUE_LOSS_PER_EXISTING_CARD = 12;
    private static final int MINIMUM_CARD_VALUE = 60;
    private static final int REFERENCE_HAND_SIZE = 3;

    private CardResourceValueEvaluator() {
    }

    /** Returns the value of one additional unknown card in a hand of the given size. */
    public static int evaluateNextCard(final int currentHandSize) {
        final long value = (long) EMPTY_HAND_CARD_VALUE
                - (long) CARD_VALUE_LOSS_PER_EXISTING_CARD * Math.max(0, currentHandSize);
        return (int) Math.max(MINIMUM_CARD_VALUE, value);
    }

    /** Returns the vacuum opportunity cost of spending one card. */
    public static int evaluateCardOpportunityCost() {
        return evaluateNextCard(REFERENCE_HAND_SIZE);
    }

    /** Returns the unrestricted-mana cost for a printed mana value. */
    public static int evaluateManaInvestment(final int manaValue) {
        if (manaValue <= 0) {
            return 0;
        }
        final long value = (long) MANA_VALUE * manaValue;
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
