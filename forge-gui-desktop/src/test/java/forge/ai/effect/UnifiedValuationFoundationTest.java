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
    public void transitionCompositionPreservesExistingReasons() {
        final CardValueBreakdown breakdown = new CardValueBreakdown(100, 40, 0, 30, 5,
                ValuationCompleteness.COMPLETE, List.of("current value"));

        final CardValueBreakdown changed = breakdown.withTransitionValue(-25,
                ValuationCompleteness.PARTIAL, List.of("returned card"));

        Assert.assertEquals(changed.transitionValue(), -25);
        Assert.assertEquals(changed.grossValue(), 120);
        Assert.assertEquals(changed.netValue(), 90);
        Assert.assertEquals(changed.completeness(), ValuationCompleteness.PARTIAL);
        Assert.assertEquals(changed.reasons(), List.of("current value", "returned card"));
    }

    @Test
    public void componentCompositionPreservesMostRestrictiveCompleteness() {
        final CardValueBreakdown partial = new CardValueBreakdown(100, 40, 0, 30, 5,
                ValuationCompleteness.PARTIAL, List.of());

        Assert.assertEquals(partial.withFuturePotential(20, ValuationCompleteness.COMPLETE,
                List.of()).completeness(), ValuationCompleteness.PARTIAL);
        Assert.assertEquals(partial.withTransitionValue(20, ValuationCompleteness.UNAVAILABLE,
                List.of()).completeness(), ValuationCompleteness.UNAVAILABLE);
        Assert.assertEquals(ValuationCompleteness.combine(ValuationCompleteness.UNSUPPORTED,
                ValuationCompleteness.PARTIAL), ValuationCompleteness.UNSUPPORTED);
    }

    @Test
    public void breakdownPlusCombinesDistinctComponentsAndReasons() {
        final CardValueBreakdown first = new CardValueBreakdown(100, 20, -5, 30, 2,
                ValuationCompleteness.COMPLETE, List.of("first"));
        final CardValueBreakdown second = new CardValueBreakdown(40, 10, 5, 15, -2,
                ValuationCompleteness.PARTIAL, List.of("second"));

        final CardValueBreakdown combined = first.plus(second);

        Assert.assertEquals(combined.currentPresenceValue(), 140);
        Assert.assertEquals(combined.futurePotentialValue(), 30);
        Assert.assertEquals(combined.transitionValue(), 0);
        Assert.assertEquals(combined.accessCost(), 45);
        Assert.assertEquals(combined.contextAdjustment(), 0);
        Assert.assertEquals(combined.netValue(), 125);
        Assert.assertEquals(combined.completeness(), ValuationCompleteness.PARTIAL);
        Assert.assertEquals(combined.reasons(), List.of("first", "second"));
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
