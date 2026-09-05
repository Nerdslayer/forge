package forge.ai;

/**
 * Converts cards in hand and immediately available mana into the permanent-evaluation point scale.
 */
public final class PlayerResourceValueEvaluator {
    /** Approximate value of one unrestricted mana in permanent-evaluation points. */
    public static final int MANA_VALUE = 35;

    private static final int EMPTY_HAND_CARD_VALUE = 140;
    private static final int CARD_VALUE_LOSS_PER_EXISTING_CARD = 12;
    private static final int MINIMUM_CARD_VALUE = 60;

    private PlayerResourceValueEvaluator() {
    }

    /**
     * Returns the marginal value of adding one unknown, mid-power card to a hand of the given size.
     * The declining value reflects that the first playable option matters more than another option in
     * an already-full hand. At three cards the value is 104, approximately three mana at 35 each.
     */
    public static int evaluateNextCard(final int currentHandSize) {
        final long decliningValue = (long) EMPTY_HAND_CARD_VALUE
                - (long) CARD_VALUE_LOSS_PER_EXISTING_CARD * Math.max(0, currentHandSize);
        return (int) Math.max(MINIMUM_CARD_VALUE, decliningValue);
    }

    /** Returns the value of drawing {@code amount} unknown cards into the current hand. */
    public static int evaluateCardDraw(final int currentHandSize, final int amount) {
        if (amount <= 0) {
            return 0;
        }
        long value = 0;
        for (int i = 0; i < amount; i++) {
            value += evaluateNextCard(saturatedAdd(currentHandSize, i));
            if (value >= Integer.MAX_VALUE) {
                return Integer.MAX_VALUE;
            }
        }
        return (int) value;
    }

    /** Returns the value of gaining the given amount of unrestricted, immediately usable mana. */
    public static int evaluateMana(final int amount) {
        if (amount <= 0) {
            return 0;
        }
        final long value = (long) MANA_VALUE * amount;
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }

    private static int saturatedAdd(final int left, final int right) {
        final long result = (long) left + right;
        return result >= Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) result;
    }
}
