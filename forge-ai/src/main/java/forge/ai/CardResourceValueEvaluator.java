package forge.ai;

/**
 * Shared, game-independent estimates for card and mana resources on the permanent-evaluation
 * point scale.
 *
 * <p>The immediate value of available mana is intentionally linear. The additional premium for
 * expensive cards belongs only to the opportunity cost of casting a card from hand: a six-drop
 * is less reliable to realize than a four-drop because the game may end before six mana is
 * available. Callers comparing an activated ability with spending the same currently available
 * mana should use {@link #evaluateMana(int)} or {@link #evaluateAverageCardPlay(int)} instead.</p>
 */
public final class CardResourceValueEvaluator {
    /** Approximate value of one unrestricted mana in permanent-evaluation points. */
    public static final int MANA_VALUE = 25;

    private static final int EMPTY_HAND_CARD_VALUE = 120;
    private static final int CARD_VALUE_LOSS_PER_EXISTING_CARD = 10;
    private static final int MINIMUM_CARD_VALUE = 50;
    private static final int REFERENCE_HAND_SIZE = 3;
    private static final int EXPENSIVE_CARD_MANA_THRESHOLD = 3;
    private static final int EXPENSIVE_CARD_MANA_PREMIUM = 5;
    private CardResourceValueEvaluator() {
    }

    /** Returns the value of one additional unknown, mid-power card in a hand of the given size. */
    public static int evaluateNextCard(final int currentHandSize) {
        final long value = (long) EMPTY_HAND_CARD_VALUE
                - (long) CARD_VALUE_LOSS_PER_EXISTING_CARD * Math.max(0, currentHandSize);
        return (int) Math.max(MINIMUM_CARD_VALUE, value);
    }

    /** Returns the vacuum opportunity cost of spending one card. */
    public static int evaluateCardOpportunityCost() {
        return evaluateNextCard(REFERENCE_HAND_SIZE);
    }

    /** Returns the value of gaining or spending immediately usable unrestricted mana. */
    public static int evaluateMana(final int amount) {
        if (amount <= 0) {
            return 0;
        }
        return saturate((long) MANA_VALUE * amount);
    }

    /**
     * Returns the opportunity cost of casting a card with the given printed mana value.
     *
     * <p>The first three mana are valued linearly. Above that point, the quadratic premium models
     * the increasing chance that a game ends, or the card remains stranded, before the card's
     * cost can be paid. This is a calibration heuristic, not a claim that all formats share one
     * exact mana curve.</p>
     */
    public static int evaluateManaInvestment(final int manaValue) {
        final int safeManaValue = Math.max(0, manaValue);
        final long expensiveMana = Math.max(0, safeManaValue - EXPENSIVE_CARD_MANA_THRESHOLD);
        final long linearValue = (long) MANA_VALUE * safeManaValue;
        final long premium = EXPENSIVE_CARD_MANA_PREMIUM * expensiveMana * expensiveMana;
        return saturate(linearValue + premium);
    }

    /** Returns the expected resource investment for an average card playable at this cost. */
    public static int evaluateAverageCardPlay(final int manaCost) {
        return saturate((long) evaluateMana(manaCost) + evaluateCardOpportunityCost());
    }

    private static int saturate(final long value) {
        return value >= Integer.MAX_VALUE ? Integer.MAX_VALUE
                : value <= Integer.MIN_VALUE ? Integer.MIN_VALUE : (int) value;
    }
}
