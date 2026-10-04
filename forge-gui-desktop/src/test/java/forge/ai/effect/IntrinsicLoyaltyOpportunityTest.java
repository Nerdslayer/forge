package forge.ai.effect;

import java.util.List;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

public class IntrinsicLoyaltyOpportunityTest {
    private static IntrinsicReferenceModel.PermanentProfile source(final int loyalty) {
        return new IntrinsicReferenceModel.PermanentProfile(true, IntrinsicReferenceModel.PermanentKind.PLANESWALKER,
                true, 0, 0, Set.of(), false, loyalty);
    }

    private static IntrinsicEvaluationSettings settings() {
        final var defaults = IntrinsicEvaluationSettings.defaults();
        return new IntrinsicEvaluationSettings(defaults.maximumExpectedOccurrencesPerAbility(), defaults.recurringTriggerResolutions(),
                1, defaults.maximumActivationUsesPerTurn(), defaults.maximumTraversalDepth(), defaults.maximumInteractionDepth(),
                defaults.maximumInteractionPaths(), defaults.maximumOutcomeSearchBudget(), defaults.minimumSurvivalMultiplier(),
                defaults.maximumSurvivalMultiplier(), defaults.lethalBaseValue(), defaults.lethalValuePerLife(), defaults.lethalValueCap());
    }

    private static IntrinsicLoyaltyOpportunityPlanner.Payoff payoff(final double value) {
        return new IntrinsicLoyaltyOpportunityPlanner.Payoff(value, true, true, List.of());
    }

    @Test
    public void alternativesShareOneActivationPerTurnAndDoNotSumIndependentHorizons() {
        final var options = List.of(new IntrinsicLoyaltyOpportunityPlanner.Option("small", 1),
                new IntrinsicLoyaltyOpportunityPlanner.Option("large", 1));
        final var result = IntrinsicLoyaltyOpportunityPlanner.estimate(options, source(3), IntrinsicReferenceModel.defaults(),
                settings(), EntryTiming.NORMAL_SPEED, (option, loyalty, turn) -> payoff("large".equals(option.path()) ? 100 : 50));
        Assert.assertTrue(result.supported());
        Assert.assertTrue(result.complete());
        Assert.assertFalse(result.contributions().containsKey("small"));
        Assert.assertEquals(result.contributions().get("large").currentTurnUses(), 1.0);
        Assert.assertTrue(result.contributions().get("large").expectedUses() > 1);
        Assert.assertTrue(result.contributions().get("large").expectedUses() < 2);
        Assert.assertEquals(result.value(), result.contributions().get("large").value());
    }

    @Test
    public void buildingLoyaltyCanFundLaterSpendAndZeroLoyaltyEndsFutureUses() {
        final var options = List.of(new IntrinsicLoyaltyOpportunityPlanner.Option("build", 2),
                new IntrinsicLoyaltyOpportunityPlanner.Option("spend", -5));
        final var result = IntrinsicLoyaltyOpportunityPlanner.estimate(options, source(3), IntrinsicReferenceModel.defaults(),
                settings(), EntryTiming.NORMAL_SPEED, (option, loyalty, turn) -> payoff("spend".equals(option.path()) ? 500 : 0));
        Assert.assertTrue(result.complete());
        Assert.assertEquals(result.contributions().get("build").currentTurnUses(), 1.0);
        Assert.assertEquals(result.contributions().get("spend").currentTurnUses(), 0.0);
        Assert.assertTrue(result.value() > 0 && result.value() < 500);
        final var immediate = IntrinsicLoyaltyOpportunityPlanner.estimate(List.of(options.get(1)), source(5),
                IntrinsicReferenceModel.defaults(), settings(), EntryTiming.NORMAL_SPEED, (option, loyalty, turn) -> {
                    Assert.assertEquals(loyalty, 0);
                    return payoff(500);
                });
        Assert.assertEquals(immediate.value(), 500.0);
        Assert.assertEquals(immediate.contributions().get("spend").expectedUses(), 1.0);
    }

    @Test
    public void unknownLegalModesRemainIncompleteAndIllegalModesDoNotContribute() {
        final var options = List.of(new IntrinsicLoyaltyOpportunityPlanner.Option("draw", 0),
                new IntrinsicLoyaltyOpportunityPlanner.Option("unknown", -8));
        final var result = IntrinsicLoyaltyOpportunityPlanner.estimate(options, source(3), IntrinsicReferenceModel.defaults(),
                settings(), EntryTiming.NORMAL_SPEED, (option, loyalty, turn) -> payoff(100));
        Assert.assertTrue(result.complete());
        Assert.assertFalse(result.contributions().containsKey("unknown"));
        final var legal = IntrinsicLoyaltyOpportunityPlanner.estimate(options, source(8), IntrinsicReferenceModel.defaults(),
                settings(), EntryTiming.NORMAL_SPEED, (option, loyalty, turn) -> "unknown".equals(option.path())
                        ? new IntrinsicLoyaltyOpportunityPlanner.Payoff(0, true, false, List.of("Unsupported outcome")) : payoff(100));
        Assert.assertTrue(legal.supported());
        Assert.assertFalse(legal.complete());
        Assert.assertTrue(legal.value() > 0);
        Assert.assertTrue(legal.unresolvedReasons().contains("Unsupported outcome"));
    }

    @Test
    public void flashWaitsForControllerTurnAndUnavailableOrAbsentSourcesDoNotActivate() {
        final var options = List.of(new IntrinsicLoyaltyOpportunityPlanner.Option("benefit", 1));
        final var flash = IntrinsicLoyaltyOpportunityPlanner.estimate(options, source(3), IntrinsicReferenceModel.defaults(),
                settings(), EntryTiming.FLASH_LATE_TURN, (option, loyalty, turn) -> payoff(100));
        Assert.assertEquals(flash.contributions().get("benefit").currentTurnUses(), 0.0);
        Assert.assertTrue(flash.value() > 0 && flash.value() < 100);
        final var unavailable = IntrinsicLoyaltyOpportunityPlanner.estimate(options, source(3), IntrinsicReferenceModel.defaults(),
                settings(), EntryTiming.NORMAL_SPEED, (option, loyalty, turn) -> new IntrinsicLoyaltyOpportunityPlanner.Payoff(0, false, true, List.of()));
        Assert.assertEquals(unavailable.value(), 0.0);
        Assert.assertTrue(unavailable.complete());
        Assert.assertTrue(unavailable.contributions().isEmpty());
        final var absent = IntrinsicLoyaltyOpportunityPlanner.estimate(options, source(0), IntrinsicReferenceModel.defaults(),
                settings(), EntryTiming.NORMAL_SPEED, (option, loyalty, turn) -> { Assert.fail("Dead source cannot activate"); return payoff(100); });
        Assert.assertEquals(absent.value(), 0.0);
    }

    @Test
    public void oversizedSearchRetainsUnsupportedCoverageRatherThanAnArbitraryPolicy() {
        final var defaults = settings();
        final var longHorizon = new IntrinsicEvaluationSettings(defaults.maximumExpectedOccurrencesPerAbility(), defaults.recurringTriggerResolutions(),
                24, defaults.maximumActivationUsesPerTurn(), defaults.maximumTraversalDepth(), defaults.maximumInteractionDepth(),
                defaults.maximumInteractionPaths(), defaults.maximumOutcomeSearchBudget(), defaults.minimumSurvivalMultiplier(),
                defaults.maximumSurvivalMultiplier(), defaults.lethalBaseValue(), defaults.lethalValuePerLife(), defaults.lethalValueCap());
        final var many = java.util.stream.IntStream.rangeClosed(1, 32)
                .mapToObj(index -> new IntrinsicLoyaltyOpportunityPlanner.Option("mode" + index, index)).toList();
        final var result = IntrinsicLoyaltyOpportunityPlanner.estimate(many, source(3), IntrinsicReferenceModel.defaults(),
                longHorizon, EntryTiming.NORMAL_SPEED, (option, loyalty, turn) -> payoff(1));
        Assert.assertFalse(result.supported());
        Assert.assertFalse(result.complete());
        Assert.assertEquals(result.value(), 0.0);
        Assert.assertTrue(result.contributions().isEmpty());
        Assert.assertTrue(result.statesEvaluated() <= 4097);
    }
}
