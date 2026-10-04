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
