package forge.ai.effect;

import java.util.List;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardStateName;
import forge.item.PaperCard;
import forge.model.FModel;

public class IntrinsicStartingCounterTest extends AITest {
    private static PaperCard definition(final String keyword, final String outcome) {
        return new PaperCard(CardRules.fromScript(List.of("Name:Starting Counter Probe", "ManaCost:2 G", "Types:Creature Hydra",
                "PT:0/0", "K:" + keyword, "T:Mode$ Phase | Phase$ Upkeep | ValidPlayer$ You | Execute$ Benefit",
                "SVar:Benefit:" + outcome, "SVar:X:Count$CardCounters.P1P1", "Oracle:Starting counters and recurring benefit.")),
                CardEdition.UNKNOWN_CODE, CardRarity.Special);
    }

    @Test
    public void knownStartingCountersAreIncludedInSizeAndInventoryExactlyOnce() {
        final var card = definition("etbCounter:P1P1:4", "DB$ MultiplyCounter | Defined$ Self | CounterType$ P1P1");
        final var state = CardAbilityTraversal.definitionState(card, CardStateName.Original);
        final var base = new PermanentProfile(true, PermanentKind.CREATURE, true, 0, 0, Set.of());
        final var source = IntrinsicSourceProfileResolver.resolveCases(state, base, IntrinsicReferenceModel.defaults()).orElseThrow()
                .get(0).value();
        Assert.assertEquals(source.profile().power(), 4);
        Assert.assertEquals(source.profile().toughness(), 4);
        Assert.assertEquals(source.quantities().get(IntrinsicDrawOutcomeBackend.SOURCE_P1P1).intValue(), 4);
        final var projected = IntrinsicDrawOutcomeBackend.initializeP1p1(new IntrinsicDrawOutcomeBackend.State(3, 3)
                .withSourcePermanent(source.profile()), IntrinsicDrawOutcomeBackend.TargetRef.SOURCE, 4, 4);
        Assert.assertEquals(projected.sourcePermanent().power(), 4);
        Assert.assertEquals(projected.p1p1(IntrinsicDrawOutcomeBackend.TargetRef.SOURCE), 4);
        final var value = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(card, CardStateName.Original).stream().filter(entry -> entry.path().endsWith("trigger:0"))
                .findFirst().orElseThrow();
        Assert.assertTrue(value.contribution().complete());
        Assert.assertTrue(value.contribution().value() > 0);
    }

    @Test
    public void aliasesUseKnownCountRatherThanTheGenericPriorAndConditionalCountsAreNotGuessed() {
        final var card = definition("etbCounter:P1P1:4", "DB$ Draw | Defined$ You | NumCards$ X");
        final var original = IntrinsicReferenceModel.defaults();
        final var emptyPrior = original.withQuantities(original.quantities().with(IntrinsicReferenceQuantities.Quantity.P1P1_COUNTERS,
                WeightedDistribution.of(new WeightedValue<>(0, 1))));
        final var a = new IntrinsicAbilityEvaluator(original, IntrinsicEvaluationSettings.defaults()).evaluateDefinition(card,
                CardStateName.Original).stream().filter(entry -> entry.path().endsWith("trigger:0")).findFirst().orElseThrow();
        final var b = new IntrinsicAbilityEvaluator(emptyPrior, IntrinsicEvaluationSettings.defaults()).evaluateDefinition(card,
                CardStateName.Original).stream().filter(entry -> entry.path().endsWith("trigger:0")).findFirst().orElseThrow();
        Assert.assertTrue(a.contribution().complete());
        Assert.assertTrue(a.contribution().value() > 0);
        Assert.assertEquals(a.contribution().value(), b.contribution().value(), 1e-9);
        final var conditional = definition("etbCounter:P1P1:4:Bloodthirst$ True", "DB$ Draw | NumCards$ 1");
        final var base = new PermanentProfile(true, PermanentKind.CREATURE, true, 0, 0, Set.of());
        Assert.assertTrue(IntrinsicSourceProfileResolver.resolveCases(CardAbilityTraversal.definitionState(conditional,
                CardStateName.Original), base, original).orElseThrow().get(0).value().quantities().isEmpty());
    }

    @Test
    public void xPaidStartingCountersShareOneSampleWithBodyAndAbilityQuantities() {
        final var card = new PaperCard(CardRules.fromScript(List.of("Name:Shared Entry Counter Probe", "ManaCost:X G",
                "Types:Creature Hydra", "PT:1/2", "K:etbCounter:P1P1:X", "K:etbCounter:P1P1:TwiceX",
                "SVar:X:Count$xPaid", "SVar:TwiceX:SVar$X/Twice", "Oracle:Correlated starting counters.")),
                CardEdition.UNKNOWN_CODE, CardRarity.Special);
        final var original = IntrinsicReferenceModel.defaults();
        final var model = original.withQuantities(original.quantities().with(IntrinsicReferenceQuantities.Quantity.X_PAID,
                WeightedDistribution.of(new WeightedValue<>(0, .25), new WeightedValue<>(2, .75))));
        final var base = new PermanentProfile(true, PermanentKind.CREATURE, true, 1, 2, Set.of());
        final var cases = IntrinsicSourceProfileResolver.resolveCases(CardAbilityTraversal.definitionState(card, CardStateName.Original),
                base, model).orElseThrow();
        Assert.assertEquals(cases.size(), 2, "Two counter keywords must not independently sample X");
        Assert.assertEquals(cases.stream().mapToDouble(WeightedValue::weight).sum(), 1.0, 1e-9);
        for (final var reference : cases) {
            final var source = reference.value();
            final int x = source.quantities().get("X_PAID");
            Assert.assertEquals(source.quantities().get(IntrinsicDrawOutcomeBackend.SOURCE_P1P1), Integer.valueOf(3 * x));
            Assert.assertEquals(source.profile().power(), 1 + 3 * x);
            Assert.assertEquals(source.profile().toughness(), 2 + 3 * x);
            final var draw = new AbilityOutcomeDescription("draw", "Draw", java.util.Map.of("NumCards", "X"), List.of(), null, "");
            final var outcomes = IntrinsicOutcomeQuantityBinder.bind(draw, java.util.Map.of("X", "Count$xPaid"),
                    model, source.profile(), source.quantities());
            Assert.assertEquals(outcomes.size(), 1);
            Assert.assertEquals(outcomes.get(0).value().parameters().get("NumCards"), Integer.toString(x));
            final var projected = IntrinsicDrawOutcomeBackend.initializeP1p1(new IntrinsicDrawOutcomeBackend.State(3, 3)
                    .withSourcePermanent(source.profile()), IntrinsicDrawOutcomeBackend.TargetRef.SOURCE, 3 * x, 3 * x);
            Assert.assertEquals(projected.sourcePermanent(), source.profile());
        }
        final var unknown = definition("etbCounter:P1P1:Missing", "DB$ Draw | NumCards$ 1");
        Assert.assertTrue(IntrinsicSourceProfileResolver.resolveCases(CardAbilityTraversal.definitionState(unknown,
                CardStateName.Original), base, model).isEmpty());
    }

    @Test
    public void xSizedDeathTriggerUsesTheInitializedBodyRatherThanPrintedZeroPower() {
        final var original = IntrinsicReferenceModel.defaults();
        final var emptyX = original.withQuantities(original.quantities().with(IntrinsicReferenceQuantities.Quantity.X_PAID,
                WeightedDistribution.of(new WeightedValue<>(0, 1))));
        final var largeX = original.withQuantities(original.quantities().with(IntrinsicReferenceQuantities.Quantity.X_PAID,
                WeightedDistribution.of(new WeightedValue<>(4, 1))));
        final var card = FModel.getMagicDb().getCommonCards().getCard("Goldvein Hydra");
        final var zero = new IntrinsicAbilityEvaluator(emptyX, IntrinsicEvaluationSettings.defaults()).evaluateDefinition(card,
                CardStateName.Original).stream().filter(value -> value.path().endsWith("trigger:0")).findFirst().orElseThrow();
        final var four = new IntrinsicAbilityEvaluator(largeX, IntrinsicEvaluationSettings.defaults()).evaluateDefinition(card,
                CardStateName.Original).stream().filter(value -> value.path().endsWith("trigger:0")).findFirst().orElseThrow();
        Assert.assertTrue(zero.contribution().complete());
        Assert.assertEquals(zero.contribution().value(), 0.0);
        Assert.assertTrue(four.contribution().complete());
        Assert.assertTrue(four.contribution().value() > 0);
    }

    @Test
    public void startingCounterAttackerSupportsItsOwnCounterMultiplication() {
        final var value = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Kalonian Hydra"), CardStateName.Original)
                .stream().filter(entry -> entry.path().endsWith("trigger:0")).findFirst().orElseThrow();
        Assert.assertEquals(value.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(value.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(value.contribution().complete());
        Assert.assertTrue(value.contribution().value() > 0);
    }
}
