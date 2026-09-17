package forge.ai.effect;

import forge.ai.CardResourceValueEvaluator;
import forge.ai.PlayerResourceValueEvaluator;
import forge.game.player.Player;

/** Conservative value estimates for legal actions whose detailed outcome is not modeled yet. */
final class ActionValueFallbackEvaluator {
    private ActionValueFallbackEvaluator() {
    }

    /**
     * Estimates an unsupported cast as an average card played at its mana value.
     *
     * <p>The mana-combination selector compares the benefits of legal actions while using mana
     * cost as its knapsack constraint. It therefore needs the gross value of the assumed card,
     * not the value after subtracting the card and mana used to cast it.</p>
     */
    static Estimate cast(final int manaCost) {
        final int grossValue = CardResourceValueEvaluator.evaluateAverageCardPlay(manaCost);
        return new Estimate(grossValue,
                "Unsupported cast: assuming an average card worth one card plus its mana cost "
                        + "(gross=" + grossValue + "; mana is constrained separately)");
    }

    /**
     * Estimates an unsupported activation as a fair-rate mana sink. An activation does not spend
     * a card, so it receives no card value. Mana is constrained separately by the combination
     * selector, while known life payments remain a cost.
     */
    static Estimate activation(final Player ai, final int manaCost, final int lifeCost) {
        final int grossValue = CardResourceValueEvaluator.evaluateMana(manaCost);
        final int lifeCostValue = lifeCost <= 0 || ai == null ? 0
                : EffectMath.negate(PlayerResourceValueEvaluator.evaluateLifeChange(ai.getLife(),
                        ai.getLife() - lifeCost));
        return new Estimate(EffectMath.subtract(grossValue, lifeCostValue),
                "Unsupported activation: assuming a fair-rate mana sink "
                        + "(gross=" + grossValue + ", lifeCost=" + lifeCostValue
                        + "; mana is constrained separately)");
    }

    record Estimate(int value, String reason) {
    }
}
