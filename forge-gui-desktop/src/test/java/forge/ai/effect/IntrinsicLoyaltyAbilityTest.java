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
