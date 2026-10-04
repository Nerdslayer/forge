package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

/** Recipient eligibility and outcome deltas use the same sampled pre-change characteristics. */
public class IntrinsicStaticEligibilityTest {
    private static final IntrinsicReferenceModel.PermanentProfile SOURCE = new IntrinsicReferenceModel.PermanentProfile(true,
            IntrinsicReferenceModel.PermanentKind.CREATURE, true, 2, 3, Set.of());

    private static IntrinsicStaticAbilityEvaluator.Evaluation evaluate(final String affected, final Map<String, String> changes) {
        final var parameters = new java.util.LinkedHashMap<>(changes);
        parameters.put("Mode", "Continuous");
        parameters.put("Affected", affected);
        return IntrinsicStaticAbilityEvaluator.evaluate(new CardAbilityTraversal.AbilityDescription("static",
                CardAbilityTraversal.Origin.STATIC, CardAbilityTraversal.Provenance.PRINTED, parameters, null),
                SOURCE, IntrinsicReferenceModel.defaults());
    }

    @Test
    public void profileFiltersPreserveScopeAndApplyEveryThresholdToOneProfile() {
        final var filter = IntrinsicStaticRecipientFilter.describe("Creature.powerGE4+YouCtrl+toughnessLE5",
                IntrinsicReferenceModel.defaults());
        Assert.assertEquals(filter.affected(), "Creature.YouCtrl");
        Assert.assertTrue(filter.characteristics().test(4, 5));
        Assert.assertFalse(filter.characteristics().test(3, 5));
        Assert.assertFalse(filter.characteristics().test(4, 6));
        final var impossible = IntrinsicStaticRecipientFilter.describe("Creature.YouCtrl+powerGE4+powerLT4",
                IntrinsicReferenceModel.defaults());
        Assert.assertFalse(impossible.characteristics().test(4, 4));
        Assert.assertEquals(evaluate("Creature.YouCtrl+powerGE4+powerLT4", Map.of("AddKeyword", "Flying")).value(), 0.0);
        Assert.assertFalse(evaluate("Creature.YouCtrl+powerGEX", Map.of("AddKeyword", "Flying")).supported());
    }

    @Test
    public void eligibleRecipientDeltaIsNotAnUnconditionalDeltaTimesAnIndependentProbability() {
        final var model = IntrinsicReferenceModel.defaults();
        final var evaluator = new IntrinsicOutcomeEvaluator();
        final double present = model.creatureProfiles().entries().stream().filter(entry -> entry.value().present())
                .mapToDouble(WeightedValue::weight).sum();
        double weighted = 0;
        for (final var entry : model.creatureProfiles().entries()) {
            final var before = entry.value();
            if (!before.present() || before.power() < 4) { continue; }
            final var keywords = new java.util.HashSet<>(before.keywords());
            keywords.add("trample");
            final var after = new IntrinsicReferenceModel.CreatureProfile(true, before.power(), before.toughness(),
                    keywords, before.hexproof(), before.indestructible());
            weighted += entry.weight() * evaluator.evaluateCreatureDelta(before, after, true);
        }
        final var own = evaluate("Creature.YouCtrl+powerGE4", Map.of("AddKeyword", "Trample"));
        Assert.assertTrue(own.supported());
        Assert.assertEquals(own.value(), Math.round(weighted / present) * 2.0);
        Assert.assertEquals(evaluate("Creature.OppCtrl+powerGE4", Map.of("AddKeyword", "Trample")).value(), -own.value());
        Assert.assertEquals(evaluate("Creature.powerGE4", Map.of("AddKeyword", "Trample")).value(), 0.0);
    }

    @Test
    public void keywordEligibilitySharesTheProfileAndCombinesWithSizeBounds() {
        final var model = IntrinsicReferenceModel.defaults();
        final var filter = IntrinsicStaticRecipientFilter.describe("Creature.withFlying+Other+YouCtrl+powerGE2", model);
        Assert.assertEquals(filter.affected(), "Creature.Other+YouCtrl");
        Assert.assertTrue(filter.matches(new IntrinsicReferenceModel.CreatureProfile(true, 2, 2, Set.of("FLYING"), false, false)));
        Assert.assertFalse(filter.matches(new IntrinsicReferenceModel.CreatureProfile(true, 1, 2, Set.of("Flying"), false, false)));
        Assert.assertFalse(filter.matches(new IntrinsicReferenceModel.CreatureProfile(true, 3, 3, Set.of(), false, false)));
        final var protectedFilter = IntrinsicStaticRecipientFilter.describe("Creature.withHexproof+withoutIndestructible", model);
        Assert.assertTrue(protectedFilter.matches(new IntrinsicReferenceModel.CreatureProfile(true, 2, 2, Set.of(), true, false)));
        Assert.assertFalse(protectedFilter.matches(new IntrinsicReferenceModel.CreatureProfile(true, 2, 2, Set.of(), true, true)));
        Assert.assertEquals(evaluate("Creature.YouCtrl+withFlying+withoutFlying", Map.of("AddPower", "1")).value(), 0.0);
        Assert.assertFalse(evaluate("Creature.YouCtrl+withUnmodeledKeyword", Map.of("AddPower", "1")).supported());

        final var scorer = new IntrinsicOutcomeEvaluator();
        double total = 0;
        double weighted = 0;
        for (final var entry : model.creatureProfiles().entries()) {
            final var before = entry.value();
            if (!before.present()) { continue; }
            total += entry.weight();
            if (!filter.matches(before)) { continue; }
            final var after = new IntrinsicReferenceModel.CreatureProfile(true, before.power() + 1, before.toughness() + 1,
                    before.keywords(), before.hexproof(), before.indestructible());
            weighted += entry.weight() * scorer.evaluateCreatureDelta(before, after, true);
        }
        final var actual = evaluate("Creature.withFlying+Other+YouCtrl+powerGE2", Map.of("AddPower", "1", "AddToughness", "1"));
        Assert.assertTrue(actual.supported());
        Assert.assertEquals(actual.value(), Math.round(weighted / total) * 2.0);
    }

    @Test
    public void keywordLossUsesTheExistingDeltaAndRecipientPerspective() {
        final var own = evaluate("Creature.YouCtrl", Map.of("RemoveKeyword", "Flying"));
        Assert.assertTrue(own.supported());
        Assert.assertTrue(own.value() < 0);
        Assert.assertEquals(evaluate("Creature.OppCtrl", Map.of("RemoveKeyword", "Flying")).value(), -own.value());
        Assert.assertEquals(evaluate("Creature", Map.of("RemoveKeyword", "Flying")).value(), 0.0);
        final var hammer = evaluate("Creature.EquippedBy",
                Map.of("AddPower", "10", "AddToughness", "10", "RemoveKeyword", "Flying"));
        Assert.assertTrue(hammer.supported());
        Assert.assertTrue(hammer.value() > 0);
        Assert.assertTrue(hammer.value() < evaluate("Creature.EquippedBy",
                Map.of("AddPower", "10", "AddToughness", "10")).value());
        Assert.assertFalse(evaluate("Creature.YouCtrl", Map.of("RemoveKeyword", "Unsupported keyword")).supported());
        Assert.assertFalse(evaluate("Creature.YouCtrl", Map.of("RemoveKeyword", "Flying", "AddKeyword", "Flying")).supported());
        Assert.assertFalse(evaluate("Creature.OppCtrl", Map.of("RemoveKeyword", "Flying", "CantHaveKeyword", "Flying")).supported());
    }

    @Test
    public void sourceEligibilityUsesBeforeCharacteristicsAndHintsOnlyCountEligibleRecipients() {
        Assert.assertEquals(evaluate("Card.Self+powerGE4", Map.of("AddPower", "3")).value(), 0.0);
        Assert.assertTrue(evaluate("Card.Self+powerLE2", Map.of("AddPower", "3")).value() > 0);
        final var model = IntrinsicReferenceModel.defaults();
        final double present = model.creatureProfiles().entries().stream().filter(entry -> entry.value().present())
                .mapToDouble(WeightedValue::weight).sum();
        final double eligible = model.creatureProfiles().entries().stream()
                .filter(entry -> entry.value().present() && entry.value().power() >= 4).mapToDouble(WeightedValue::weight).sum();
        Assert.assertEquals(evaluate("Creature.YouCtrl+powerGE4", Map.of("AIEffectValue", "20")).value(), 40 * eligible / present, 1e-9);
        for (final String affected : List.of("Creature.YouCtrl+powerGE999999999999", "Artifact.YouCtrl+powerGE1")) {
            Assert.assertFalse(evaluate(affected, Map.of("AIEffectValue", "20")).supported());
        }
    }
}
