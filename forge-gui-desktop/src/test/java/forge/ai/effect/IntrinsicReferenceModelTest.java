package forge.ai.effect;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

/** Regression checks for deterministic intrinsic reference inputs. */
public class IntrinsicReferenceModelTest {
    @Test
    public void defaultLifeDistributionUsesAgreedWeights() {
        final List<WeightedValue<Integer>> entries = IntrinsicReferenceModel.defaults()
                .lifeTotals().entries();

        Assert.assertEquals(entries.size(), 8);
        Assert.assertEquals(entries.get(0).value().intValue(), 1);
        Assert.assertEquals(entries.get(0).weight(), .005, 0.000000001);
        Assert.assertEquals(entries.get(4).value().intValue(), 5);
        Assert.assertEquals(entries.get(4).weight(), .05, 0.000000001);
        Assert.assertEquals(entries.get(7).value().intValue(), 20);
        Assert.assertEquals(entries.get(7).weight(), .40, 0.000000001);
        Assert.assertEquals(entries.stream().mapToDouble(WeightedValue::weight).sum(), 1.0, 0.000000001);
    }

    @Test
    public void distributionsNormalizePositiveWeights() {
        final WeightedDistribution<String> distribution = WeightedDistribution.of(
                new WeightedValue<>("first", 2), new WeightedValue<>("second", 1));

        Assert.assertEquals(distribution.entries().get(0).weight(), 2.0 / 3, 0.000000001);
        Assert.assertEquals(distribution.entries().get(1).weight(), 1.0 / 3, 0.000000001);
    }

    @Test(expectedExceptions = IllegalArgumentException.class)
    public void distributionsRejectNegativeWeights() {
        new WeightedDistribution<>(List.of(new WeightedValue<>("invalid", -1)));
    }

    @Test
    public void referenceCasesCombineInStableDimensionOrder() {
        final WeightedDistribution<Integer> binary = WeightedDistribution.of(
                new WeightedValue<>(0, .5), new WeightedValue<>(1, .5));
        final List<ReferenceCase> cases = ReferenceCaseCombiner.combine(List.of(
                new ReferenceDimension("second", binary),
                new ReferenceDimension("first", binary)));

        Assert.assertEquals(cases.size(), 4);
        Assert.assertEquals(cases.get(0).value("first", Integer.class).intValue(), 0);
        Assert.assertEquals(cases.get(0).value("second", Integer.class).intValue(), 0);
        Assert.assertEquals(cases.get(0).probability(), .25, 0.000000001);
        Assert.assertEquals(cases.get(3).value("first", Integer.class).intValue(), 1);
        Assert.assertEquals(cases.get(3).value("second", Integer.class).intValue(), 1);
        Assert.assertEquals(cases.stream().mapToDouble(ReferenceCase::probability).sum(), 1.0,
                0.000000001);
    }

    @Test
    public void lethalValueUsesIntrinsicCurveWithoutChangingLiveConstant() {
        final IntrinsicEvaluationSettings settings = IntrinsicEvaluationSettings.defaults();

        Assert.assertEquals(settings.intrinsicLethalValue(1), 260);
        Assert.assertEquals(settings.intrinsicLethalValue(5), 500);
        Assert.assertEquals(settings.intrinsicLethalValue(20), 500);
    }

    @Test
    public void intrinsicResourceValueUsesControllerPolarityAndReferenceInputs() {
        final IntrinsicOutcomeEvaluator evaluator = new IntrinsicOutcomeEvaluator();
        final ReferenceCase reference = new ReferenceCase(java.util.Map.of(
                IntrinsicReferenceModel.HAND_SIZE, 0,
                IntrinsicReferenceModel.LIFE_TOTAL, 5), 1);

        Assert.assertEquals(evaluator.evaluateCardDraw(reference, 1, true), 140);
        Assert.assertEquals(evaluator.evaluateCardDraw(reference, 1, false), -140);
        Assert.assertTrue(evaluator.evaluateLifeLoss(reference, 1, true) < 0);
        Assert.assertEquals(evaluator.evaluateLifeLoss(reference, 1, false),
                -evaluator.evaluateLifeLoss(reference, 1, true));
        Assert.assertEquals(evaluator.evaluatePlayerDamage(reference, 5, false), 500);
    }

    @Test
    public void intrinsicCreatureDeltaUsesCharacteristicValueWithoutAbilityRecursion() {
        final IntrinsicOutcomeEvaluator evaluator = new IntrinsicOutcomeEvaluator();
        final IntrinsicReferenceModel.CreatureProfile before =
                new IntrinsicReferenceModel.CreatureProfile(true, 3, 3, java.util.Set.of(), false, false);
        final IntrinsicReferenceModel.CreatureProfile after =
                new IntrinsicReferenceModel.CreatureProfile(true, 4, 4, java.util.Set.of(), false, false);

        Assert.assertEquals(evaluator.evaluateCreatureDelta(before, after, true), 25);
        Assert.assertEquals(evaluator.evaluateCreatureDelta(before, after, false), -25);
    }

    @Test
    public void targetDistributionRetainsAbsentAndUnsuitableCases() {
        final IntrinsicTargetDistribution targets = new IntrinsicTargetDistribution(
                IntrinsicReferenceModel.defaults());
        final WeightedDistribution<IntrinsicTargetDistribution.TargetCase<
                IntrinsicReferenceModel.CreatureProfile>> distribution = targets.creatureTargets(
                        profile -> profile.toughness() >= 3);

        Assert.assertEquals(distribution.entries().size(), 6);
        Assert.assertEquals(distribution.entries().stream()
                .filter(entry -> entry.value().legal())
                .mapToDouble(WeightedValue::weight).sum(), .35, 0.000000001);
        Assert.assertEquals(distribution.entries().stream()
                .filter(entry -> !entry.value().present())
                .mapToDouble(WeightedValue::weight).sum(), .20, 0.000000001);
    }

    @Test
    public void referenceAggregatorKeepsCaseWeightsWhenOneCaseIsUnsupported() {
        final List<ReferenceCase> cases = List.of(
                new ReferenceCase(java.util.Map.of("case", "supported"), .25),
                new ReferenceCase(java.util.Map.of("case", "unsupported"), .75));
        final IntrinsicReferenceAggregate aggregate = IntrinsicReferenceAggregator.aggregate(cases,
                reference -> "supported".equals(reference.value("case", String.class))
                        ? OutcomePlan.complete(100, reference)
                        : OutcomePlan.unsupported(reference, "Reference outcome unsupported"));

        Assert.assertEquals(aggregate.value(), 25.0);
        Assert.assertEquals(aggregate.completeCaseProbability(), .25);
        Assert.assertEquals(aggregate.unsupportedCaseProbability(), .75);
        Assert.assertEquals(aggregate.incompleteCaseProbability(), .75);
        Assert.assertFalse(aggregate.complete());
    }
}
