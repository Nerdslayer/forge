package forge.ai.effect;

import java.util.List;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.card.CardEdition;
import forge.card.CardRarity;
import forge.card.CardRules;
import forge.card.CardStateName;
import forge.item.PaperCard;

public class IntrinsicLoyaltyAbilityTest extends AITest {
    private static List<IntrinsicAbilityEvaluator.AbilityValue> evaluate(final String... modes) {
        final var script = new java.util.ArrayList<>(List.of("Name:Loyalty Opportunity Probe", "ManaCost:2 U",
                "Types:Legendary Planeswalker Probe", "Loyalty:3", "Oracle:Shared loyalty opportunities."));
        for (final var mode : modes) { script.add("A:" + mode); }
        final var card = new PaperCard(CardRules.fromScript(script), CardEdition.UNKNOWN_CODE, CardRarity.Special);
        return new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(card, CardStateName.Original).stream().filter(value -> value.path().contains("/ability:")).toList();
    }

    @Test
    public void definitionModesShareCapacityAndChooseTheBetterSupportedOutcome() {
        final var values = evaluate("AB$ Draw | Cost$ AddCounter<1/LOYALTY> | Planeswalker$ True | Defined$ You | NumCards$ 1",
                "AB$ Draw | Cost$ AddCounter<1/LOYALTY> | Planeswalker$ True | Defined$ You | NumCards$ 2");
        Assert.assertEquals(values.size(), 2);
        Assert.assertTrue(values.stream().allMatch(value -> value.contribution().complete()));
        Assert.assertEquals(values.get(0).contribution().value(), 0.0);
        Assert.assertTrue(values.get(1).contribution().value() > 0);
        Assert.assertEquals(values.get(1).currentTurnUses(), 1.0);
    }

    @Test
    public void buildingLoyaltyFundsFutureOutcomeAndUnsupportedAlternativesStayExplicit() {
        final var values = evaluate("AB$ Draw | Cost$ AddCounter<2/LOYALTY> | Planeswalker$ True | Defined$ You | NumCards$ 0",
                "AB$ Draw | Cost$ SubCounter<5/LOYALTY> | Planeswalker$ True | Defined$ You | NumCards$ 8");
        Assert.assertTrue(values.stream().allMatch(value -> value.contribution().complete()));
        Assert.assertTrue(values.get(1).contribution().value() > 0);
        Assert.assertEquals(values.get(1).currentTurnUses(), 0.0);
        final var partial = evaluate("AB$ Draw | Cost$ 0 | Planeswalker$ True | Defined$ You | NumCards$ 1",
                "AB$ Mill | Cost$ 0 | Planeswalker$ True | Defined$ You | NumCards$ 1");
        Assert.assertFalse(partial.get(0).contribution().complete());
        Assert.assertTrue(partial.get(0).contribution().value() > 0);
        Assert.assertFalse(partial.get(0).contribution().unresolvedReasons().isEmpty());
    }

    @Test
    public void loyaltySizedTokensReadTheCostPaidSourceAndKeepUnknownUltimateExplicit() {
        final var card = forge.model.FModel.getMagicDb().getCommonCards().getCard("Nissa, Ascended Animist");
        final var values = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(card, CardStateName.Original);
        final var plus = values.stream().filter(value -> value.path().endsWith("ability:0")).findFirst().orElseThrow();
        Assert.assertTrue(plus.contribution().value() > 0);
        Assert.assertNotEquals(plus.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.UNSUPPORTED);
        Assert.assertFalse(plus.contribution().complete(), "Unsupported ultimate remains an unresolved legal alternative");
        final var source = new IntrinsicReferenceModel.PermanentProfile(true,
                IntrinsicReferenceModel.PermanentKind.PLANESWALKER, true, 0, 0, java.util.Set.of(), false, 4);
        Assert.assertEquals(IntrinsicQuantityResolver.resolve("L", java.util.Map.of("L", "Count$CardCounters.LOYALTY"),
                IntrinsicReferenceModel.defaults(), source).orElseThrow().values().entries().get(0).value(), Integer.valueOf(4));
        final var token = new AbilityOutcomeDescription("token", "Token", java.util.Map.of("TokenScript", "g_x_x_phyrexian_horror",
                "TokenPower", "L", "TokenToughness", "L"), List.of(), null, "");
        final var variables = java.util.Map.of("L", "Count$CardCounters.LOYALTY");
        final var bound = IntrinsicOutcomeQuantityBinder.bind(token, variables, IntrinsicReferenceModel.defaults(), source).get(0).value();
        Assert.assertEquals(bound.parameters().get("TokenPower"), "4");
        Assert.assertEquals(bound.parameters().get("TokenToughness"), "4");
        final var modify = new AbilityOutcomeDescription("modify", "PutCounter",
                java.util.Map.of("Defined", "Self", "CounterType", "LOYALTY", "CounterNum", "1"), List.of(), token, "");
        final var unresolved = IntrinsicOutcomeQuantityBinder.bind(modify, variables,
                IntrinsicReferenceModel.defaults(), source).get(0).value();
        Assert.assertEquals(unresolved.next().parameters().get("TokenPower"), "L", "Do not freeze a mutable loyalty read");
        final var resolver = IntrinsicTokenProfileResolver.forSource(card, IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults(), source, resolver);
        final var plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(new OutcomeDescriptionCompiler<>(backend).compile(bound),
                new IntrinsicDrawOutcomeBackend.State(3, 3).withSourcePermanent(source));
        Assert.assertTrue(plan.complete());
        final var profile = new IntrinsicReferenceModel.PermanentProfile(true,
                IntrinsicReferenceModel.PermanentKind.TOKEN, true, 4, 4, java.util.Set.of());
        Assert.assertEquals(plan.value(), (double) new IntrinsicOutcomeEvaluator().evaluatePermanent(profile), 1e-9);
    }

    @Test
    public void fixedAllPlayerDiscardIsAvailableForSharedLoyaltyBuilding() {
        final var values = evaluate("AB$ Discard | Cost$ AddCounter<2/LOYALTY> | Planeswalker$ True | Defined$ Player | Mode$ TgtChoose | NumCards$ 0",
                "AB$ Draw | Cost$ SubCounter<5/LOYALTY> | Planeswalker$ True | NumCards$ 2");
        Assert.assertTrue(values.stream().allMatch(value -> value.contribution().complete()));
        Assert.assertTrue(values.get(1).contribution().value() > 0);
        Assert.assertEquals(values.get(1).currentTurnUses(), 0.0);
        final var liliana = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(forge.model.FModel.getMagicDb().getCommonCards().getCard("Liliana of the Veil"), CardStateName.Original);
        final var discard = liliana.stream().filter(value -> value.path().endsWith("ability:0")).findFirst().orElseThrow();
        Assert.assertNotEquals(discard.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.UNSUPPORTED);
        Assert.assertFalse(discard.contribution().complete(), "Unknown ultimate stays explicit");
    }

    @Test
    public void costsRequireLiteralSelfLoyaltyWithoutAdditionalResources() {
        Assert.assertEquals(IntrinsicLoyaltyAbilityEvaluator.loyaltyChange("AddCounter<2/LOYALTY>").orElseThrow(), 2);
        Assert.assertEquals(IntrinsicLoyaltyAbilityEvaluator.loyaltyChange("SubCounter<3/LOYALTY>").orElseThrow(), -3);
        Assert.assertEquals(IntrinsicLoyaltyAbilityEvaluator.loyaltyChange("0").orElseThrow(), 0);
        for (final var invalid : List.of("1 AddCounter<1/LOYALTY>", "SubCounter<X/LOYALTY>", "AddCounter<1/P1P1>",
                "AddCounter<1/LOYALTY/Creature.YouCtrl>", "T AddCounter<1/LOYALTY>")) {
            Assert.assertTrue(IntrinsicLoyaltyAbilityEvaluator.loyaltyChange(invalid).isEmpty(), invalid);
        }
    }
}
