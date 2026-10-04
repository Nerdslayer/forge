package forge.ai.effect;

import java.util.List;
import java.util.Map;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.card.CardStateName;
import forge.model.FModel;

public class IntrinsicReceivedDamageBindingTest extends AITest {
    private static CardAbilityTraversal.AbilityDescription description(final String target) {
        final var outcome = new AbilityOutcomeDescription("outcome", "GainLife", Map.of("Defined", "You", "LifeAmount", "X"),
                List.of(), new AbilityOutcomeDescription("next", "GainLife", Map.of("Defined", "You", "LifeAmount", "TwiceX"), List.of(), null, ""), "");
        return new CardAbilityTraversal.AbilityDescription("trigger", CardAbilityTraversal.Origin.TRIGGER,
                CardAbilityTraversal.Provenance.PRINTED, Map.of("Mode", "DamageDoneOnce", "ValidTarget", target), outcome);
    }

    @Test
    public void onePositiveEventAmountIsSharedAndNotCappedByToughness() {
        final var model = IntrinsicReferenceModel.defaults();
        final var variables = Map.of("X", "TriggerCount$DamageAmount", "TwiceX", "TriggerCount$DamageAmount/Twice", "Alias", "X");
        final var cases = IntrinsicReceivedDamageBinding.cases(description("Card.Self"), variables, model).orElseThrow();
        Assert.assertEquals(cases.stream().mapToDouble(WeightedValue::weight).sum(), 1.0, 1e-9);
        Assert.assertTrue(cases.stream().allMatch(entry -> entry.value().amount() > 0));
        Assert.assertTrue(cases.stream().anyMatch(entry -> entry.value().amount() == 8));
        for (final var reference : cases) {
            final var bound = reference.value().bindVariables(variables);
            Assert.assertEquals(bound.get("X"), "Number$" + reference.value().amount());
            Assert.assertEquals(bound.get("TwiceX"), "Number$" + reference.value().amount() + "/Twice");
            Assert.assertEquals(bound.get("Alias"), "X");
        }
        final var event = IntrinsicReceivedDamageBinding.describe(description("Permanent.YouCtrl").parameters()).orElseThrow();
        Assert.assertFalse(event.atMostOncePerTurn());
        Assert.assertEquals(event.turnScope(), IntrinsicEventTrigger.TurnScope.ANY_TURN);
    }

    @Test
    public void unrelatedEventsFiltersAndNestedNewScopesAreNotBound() {
        final var variables = Map.of("X", "TriggerCount$DamageAmount");
        final var model = IntrinsicReferenceModel.defaults();
        for (final String target : List.of("Player", "Creature.OppCtrl", "Creature.YouCtrl+Red")) {
            Assert.assertTrue(IntrinsicReceivedDamageBinding.cases(description(target), variables, model).isEmpty());
        }
        final var extra = new java.util.HashMap<>(description("Card.Self").parameters());
        extra.put("ValidSource", "Creature.OppCtrl");
        Assert.assertTrue(IntrinsicReceivedDamageBinding.describe(extra).isEmpty());
        extra.remove("ValidSource");
        extra.put("DamageSource", "Any");
        Assert.assertTrue(IntrinsicReceivedDamageBinding.describe(extra).isPresent());
        extra.put("DamageSource", "Red");
        Assert.assertTrue(IntrinsicReceivedDamageBinding.describe(extra).isEmpty());
        final var delayed = new AbilityOutcomeDescription("delayed", "DelayedTrigger",
                Map.of("LifeAmount", "TriggerCount$DamageAmount"), List.of(), null, "");
        Assert.assertSame(new IntrinsicReceivedDamageBinding(3).bindOutcome(delayed), delayed);
        Assert.assertTrue(IntrinsicQuantityResolver.resolve("TriggerCount$DamageAmount", Map.of(), model,
                new IntrinsicReferenceModel.PermanentProfile(true, IntrinsicReferenceModel.PermanentKind.CREATURE,
                        true, 1, 1, java.util.Set.of())).isEmpty());
    }

    @Test
    public void configuredAmountsReachTheOutcomeRatherThanBeingResampled() {
        final var model = IntrinsicReferenceModel.defaults();
        final var settings = IntrinsicEvaluationSettings.defaults();
        final var card = FModel.getMagicDb().getCommonCards().getCard("Wall of Hope");
        final double[] values = new double[2];
        for (int index = 0; index < 2; index++) {
            final int amount = index == 0 ? 1 : 8;
            final var configured = model.withQuantities(model.quantities().with(
                    IntrinsicReferenceQuantities.Quantity.RECEIVED_DAMAGE_AMOUNT,
                    WeightedDistribution.of(new WeightedValue<>(amount, 1))));
            values[index] = new IntrinsicAbilityEvaluator(configured, settings).evaluateDefinition(card,
                    CardStateName.Original).stream().filter(value -> value.path().endsWith("trigger:0"))
                    .findFirst().orElseThrow().contribution().value();
        }
        Assert.assertTrue(values[1] > values[0]);
        final var mixed = model.withQuantities(model.quantities().with(
                IntrinsicReferenceQuantities.Quantity.RECEIVED_DAMAGE_AMOUNT,
                WeightedDistribution.of(new WeightedValue<>(1, .7), new WeightedValue<>(8, .3))));
        final double combined = new IntrinsicAbilityEvaluator(mixed, settings).evaluateDefinition(card, CardStateName.Original)
                .stream().filter(value -> value.path().endsWith("trigger:0")).findFirst().orElseThrow().contribution().value();
        Assert.assertEquals(combined, values[0] * .7 + values[1] * .3, 1e-7);
    }

    @Test
    public void receivedDamageDefinitionsReachLifeAndTargetedDamageOutcomes() {
        final var evaluator = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults());
        for (final String name : List.of("Wall of Hope", "Ill-Tempered Loner")) {
            final var entry = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard(name),
                    CardStateName.Original).stream().filter(value -> value.path().endsWith("trigger:0")).findFirst().orElseThrow();
            Assert.assertEquals(entry.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED, name);
            Assert.assertTrue(entry.contribution().value() > 0, name);
        }
        final var avenger = evaluator.evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Ill-Tempered Loner"),
                CardStateName.Backside).stream().filter(value -> value.path().endsWith("trigger:0")).findFirst().orElseThrow();
        Assert.assertEquals(avenger.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(avenger.contribution().value() > 0);
    }
}
