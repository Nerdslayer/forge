package forge.ai.effect;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.CardDefinitionValueEvaluator;

/** Regression checks for the shared valuation context and component accounting. */
public class UnifiedValuationFoundationTest {
    @Test
    public void breakdownSeparatesGrossValueFromAccessCost() {
        final CardValueBreakdown breakdown = new CardValueBreakdown(100, 40, 10, 30, 5,
                ValuationCompleteness.COMPLETE, List.of());

        Assert.assertEquals(breakdown.grossValue(), 155);
        Assert.assertEquals(breakdown.netValue(), 125);
        Assert.assertTrue(breakdown.isComplete());
    }

    @Test
    public void cardDefinitionEvaluationUsesSharedComponents() {
        final CardDefinitionValueEvaluator.Evaluation evaluation =
                new CardDefinitionValueEvaluator.Evaluation(130, 100, 20, 10, 100,
                        List.of(
                                new CardDefinitionValueEvaluator.Contribution(
                                        "Battlefield", "body", 100),
                                new CardDefinitionValueEvaluator.Contribution(
                                        "Intrinsic ability", "draw", 30)),
                        List.of());

        final CardValueBreakdown breakdown = evaluation.toCardValueBreakdown();

        Assert.assertEquals(breakdown.currentPresenceValue(), 100);
        Assert.assertEquals(breakdown.futurePotentialValue(), 30);
        Assert.assertEquals(breakdown.accessCost(), 30);
        Assert.assertEquals(breakdown.netValue(), 100);
        Assert.assertTrue(breakdown.isComplete());
    }

    @Test
    public void intrinsicContextUsesDefinitionDecisionWithoutLiveRelationships() {
        final ValuationContext context = ValuationContext.intrinsicCard();

        Assert.assertEquals(context.mode(), ValuationMode.INTRINSIC_REFERENCE);
        Assert.assertEquals(context.decision(), ValuationDecision.GENERAL_CARD);
        Assert.assertEquals(context.relationshipWeightPercent(), 0);
        Assert.assertEquals(context.intrinsicWeightPercent(), 0);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void situationalContextRequiresAPlayer() {
        ValuationContext.forRemoval(null, 75, 25);
    }
}
