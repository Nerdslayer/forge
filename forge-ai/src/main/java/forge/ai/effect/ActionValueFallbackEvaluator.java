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
     * <p>The gross estimate is intentionally based on the linear value of the resources spent by
     * an average card. The normal cast access cost is still applied, including the existing
     * expensive-card premium, so an unknown card does not become an automatic best play.</p>
     */
    static Estimate cast(final int manaCost) {
        final int grossValue = CardResourceValueEvaluator.evaluateAverageCardPlay(manaCost);
        final int accessCost = EffectMath.add(
                CardResourceValueEvaluator.evaluateCardOpportunityCost(),
                CardResourceValueEvaluator.evaluateManaInvestment(manaCost));
        return new Estimate(EffectMath.subtract(grossValue, accessCost),
                "Unsupported cast: assuming an average card worth one card plus its mana cost "
                        + "(gross=" + grossValue + ", access=" + accessCost + ")");
    }

    /**
     * Estimates an unsupported activation as a fair-rate mana sink. An activation does not spend
     * a card, so it receives no card value. Known life payments remain a cost.
     */
    static Estimate activation(final Player ai, final int manaCost, final int lifeCost) {
        final int grossValue = CardResourceValueEvaluator.evaluateMana(manaCost);
        final int manaCostValue = CardResourceValueEvaluator.evaluateMana(manaCost);
        final int lifeCostValue = lifeCost <= 0 || ai == null ? 0
                : EffectMath.negate(PlayerResourceValueEvaluator.evaluateLifeChange(ai.getLife(),
                        ai.getLife() - lifeCost));
        final int accessCost = EffectMath.add(manaCostValue, lifeCostValue);
        return new Estimate(EffectMath.subtract(grossValue, accessCost),
                "Unsupported activation: assuming a fair-rate mana sink "
                        + "(gross=" + grossValue + ", access=" + accessCost + ")");
    }

    record Estimate(int value, String reason) {
    }
}
