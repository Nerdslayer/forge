package forge.ai;

import org.testng.Assert;
import org.testng.annotations.Test;

public class PlayerResourceValueEvaluatorTest {
    @Test
    public void testMarginalCardValueDeclinesWithHandSize() {
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateNextCard(0), 140);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateNextCard(3), 104);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateNextCard(7), 60);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateNextCard(20), 60);
    }

    @Test
    public void testMultipleDrawsUseSuccessiveMarginalValues() {
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateCardDraw(0, 3),
                140 + 128 + 116);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateCardDraw(3, 2), 104 + 92);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateCardDraw(3, 0), 0);
    }

    @Test
    public void testManaUsesSharedPermanentEvaluationScale() {
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateMana(1), 35);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateMana(3), 105);
        Assert.assertEquals(PlayerResourceValueEvaluator.evaluateMana(0), 0);
    }
}
