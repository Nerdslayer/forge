package forge.ai.effect;

import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.card.CardStateName;
import forge.model.FModel;

public class IntrinsicAttackCountBindingTest extends AITest {
    private static final String OWN = "Count$Valid Creature.attacking+YouCtrl";
    private static final String OPPONENT = "Count$Valid Creature.attacking+OppCtrl";

    private static CardAbilityTraversal.AbilityDescription trigger(final String player) {
        return new CardAbilityTraversal.AbilityDescription("attack", CardAbilityTraversal.Origin.TRIGGER,
                CardAbilityTraversal.Provenance.PRINTED, Map.of("Mode", "AttackersDeclared", "AttackingPlayer", player),
                new AbilityOutcomeDescription("gain", "GainLife", Map.of("LifeAmount", "X"), List.of(), null, ""));
    }

    private static IntrinsicReferenceModel model(final WeightedDistribution<Integer> counts) {
        final var model = IntrinsicReferenceModel.defaults();
        return model.withQuantities(model.quantities().with(IntrinsicReferenceQuantities.Quantity.ATTACKING_CREATURES, counts));
    }

    @Test
    public void countIsConditionedOnDeclarationAndRetainsOneSampleThroughAliases() {
        final var model = model(WeightedDistribution.of(new WeightedValue<>(0, .5),
                new WeightedValue<>(1, .25), new WeightedValue<>(3, .25)));
        final var variables = Map.of("X", OWN, "Y", "SVar$X/Twice");
        final var cases = IntrinsicAttackCountBinding.cases(trigger("You"), variables, model).orElseThrow();
        Assert.assertEquals(cases.size(), 2);
        Assert.assertEquals(cases.stream().mapToDouble(WeightedValue::weight).sum(), 1.0);
        Assert.assertEquals(cases.stream().mapToDouble(entry -> entry.value().amount() * entry.weight()).sum(), 2.0);
        for (final var entry : cases) {
            final var bound = entry.value().bindVariables(variables);
            Assert.assertEquals(bound.get("X"), "Number$" + entry.value().amount());
            Assert.assertEquals(IntrinsicQuantityResolver.resolve("Y", bound, model,
                    IntrinsicReferenceModel.PermanentProfile.absent()).orElseThrow().values().entries().get(0).value().intValue(),
                    entry.value().amount() * 2);
        }
        Assert.assertTrue(IntrinsicAttackCountBinding.cases(trigger("Opponent"), Map.of("X", OWN), model).isEmpty());
        Assert.assertTrue(IntrinsicAttackCountBinding.cases(trigger("Player"), Map.of("X", OWN), model).isEmpty());
        Assert.assertTrue(IntrinsicAttackCountBinding.cases(trigger("Opponent"), Map.of("X", OPPONENT), model).isPresent());
        Assert.assertTrue(IntrinsicQuantityResolver.resolve(OWN, Map.of(), model,
                IntrinsicReferenceModel.PermanentProfile.absent()).isEmpty());
        Assert.assertTrue(IntrinsicAttackCountBinding.cases(trigger("You"), Map.of("X", OWN),
                model(WeightedDistribution.of(new WeightedValue<>(0, 1)))).orElseThrow().isEmpty());
    }

    @Test
    public void scalarBindingPreservesArithmeticAndStopsAtNewEvents() {
        final var binding = new IntrinsicEventScalarBinding(OWN, 3);
        final var nested = new AbilityOutcomeDescription("delayed", "DelayedTrigger", Map.of(), List.of(),
                new AbilityOutcomeDescription("later", "GainLife", Map.of("LifeAmount", OWN), List.of(), null, ""), "");
        final var node = new AbilityOutcomeDescription("gain", "GainLife", Map.of("LifeAmount", OWN + "/Twice"),
                List.of(), nested, "");
        final var bound = binding.bindOutcome(node);
        Assert.assertEquals(bound.parameters().get("LifeAmount"), "Number$3/Twice");
        Assert.assertEquals(bound.next().next().parameters().get("LifeAmount"), OWN);
        final var parameters = new java.util.LinkedHashMap<>(trigger("You").parameters());
        parameters.put("AttackedTarget", "Player");
        final var restricted = new CardAbilityTraversal.AbilityDescription("attack", CardAbilityTraversal.Origin.TRIGGER,
                CardAbilityTraversal.Provenance.PRINTED, parameters, node);
        Assert.assertTrue(IntrinsicAttackCountBinding.cases(restricted, Map.of(), IntrinsicReferenceModel.defaults()).isEmpty());
    }

    private static IntrinsicAbilityEvaluator.AbilityValue evaluate(final WeightedDistribution<Integer> counts) {
        return new IntrinsicAbilityEvaluator(model(counts), IntrinsicEvaluationSettings.defaults()).evaluateDefinition(
                FModel.getMagicDb().getCommonCards().getCard("Path of Bravery"), CardStateName.Original)
                .stream().filter(value -> value.path().endsWith("trigger:0")).findFirst().orElseThrow();
    }

    @Test
    public void declaredAttackOutcomeUsesWeightedNonlinearLifeValues() {
        final var one = evaluate(WeightedDistribution.of(new WeightedValue<>(1, 1)));
        final var three = evaluate(WeightedDistribution.of(new WeightedValue<>(3, 1)));
        final var mixed = evaluate(WeightedDistribution.of(new WeightedValue<>(0, .5),
                new WeightedValue<>(1, .25), new WeightedValue<>(3, .25)));
        Assert.assertEquals(mixed.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(mixed.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(mixed.contribution().complete());
        Assert.assertTrue(one.contribution().value() > 0);
        Assert.assertTrue(three.contribution().value() > one.contribution().value());
        Assert.assertEquals(mixed.contribution().value(), (one.contribution().value() + three.contribution().value()) / 2, 1e-9);
        Assert.assertEquals(mixed.expectedOccurrences(), one.expectedOccurrences(), 1e-9);
        Assert.assertEquals(evaluate(WeightedDistribution.of(new WeightedValue<>(0, 1))).contribution().value(), 0.0);
    }
}
