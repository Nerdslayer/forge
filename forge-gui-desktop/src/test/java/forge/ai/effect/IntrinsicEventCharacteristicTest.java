package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.card.CardStateName;
import forge.model.FModel;

public class IntrinsicEventCharacteristicTest extends AITest {
    private static IntrinsicReferenceModel.PermanentProfile host() {
        return new IntrinsicReferenceModel.PermanentProfile(true, IntrinsicReferenceModel.PermanentKind.CREATURE,
                true, 2, 3, Set.of());
    }

    @Test
    public void sourceRelativeBoundsNormalizeWithoutErasingUnknownFilters() {
        final var parameters = Map.of("Mode", "ChangesZone", "Origin", "Battlefield", "Destination", "Graveyard",
                "ValidCard", "Creature.YouCtrl+powerGTX+Other");
        final var ability = new CardAbilityTraversal.AbilityDescription("event", CardAbilityTraversal.Origin.TRIGGER,
                CardAbilityTraversal.Provenance.PRINTED, parameters, null);
        final var model = IntrinsicReferenceModel.defaults();
        final var normalized = IntrinsicEventCharacteristicFilter.normalize(ability, Map.of("X", "Count$CardPower"), model, host());
        Assert.assertEquals(normalized.parameters().get("ValidCard"), "Creature.YouCtrl+powerGT2+Other");
        Assert.assertEquals(ability.parameters(), parameters);
        Assert.assertSame(IntrinsicEventCharacteristicFilter.normalize(ability, Map.of("X", "UnknownQuantity"), model, host()), ability);
        final var event = IntrinsicCreatureDeathTriggerAdapter.describe(normalized.parameters(), model).orElseThrow();
        final double present = model.creatureProfiles().entries().stream().filter(entry -> entry.value().present())
                .mapToDouble(WeightedValue::weight).sum();
        final double eligible = model.creatureProfiles().entries().stream().filter(entry -> entry.value().present()
                && entry.value().power() > 2).mapToDouble(WeightedValue::weight).sum();
        Assert.assertEquals(event.occurrenceMultiplier(), eligible / present, 1e-9);
        final var bindings = IntrinsicWatchedCreatureBinding.cases(normalized, model, host()).orElseThrow();
        Assert.assertTrue(bindings.stream().allMatch(entry -> entry.value().creature().power() > 2));
        Assert.assertTrue(bindings.stream().allMatch(entry -> entry.value().creature().controlledByAi()));
        Assert.assertEquals(bindings.stream().mapToDouble(WeightedValue::weight).sum(), 1.0, 1e-9);
    }

    @Test
    public void mutableSourceComparisonReadsEarlierCounterChanges() {
        final var conditional = new AbilityOutcomeDescription("conditional", "Draw", Map.of("NumCards", "1",
                "ConditionCheckSVar", "X", "ConditionSVarCompare", "LT3"), List.of(), null, "");
        final var counter = new AbilityOutcomeDescription("counter", "PutCounter", Map.of("Defined", "Self",
                "CounterType", "P1P1", "CounterNum", "1"), List.of(), conditional, "");
        final var model = IntrinsicReferenceModel.defaults();
        final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults());
        final var prepared = IntrinsicOutcomeQuantityBinder.bind(counter, Map.of("X", "Count$CardPower"), model, host(), Map.of())
                .get(0).value();
        final var state = new IntrinsicDrawOutcomeBackend.State(0, 0).withSourcePermanent(host());
        final var plan = new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(
                new OutcomeDescriptionCompiler<>(backend).compile(prepared), state);
        Assert.assertTrue(plan.complete());
        Assert.assertEquals(plan.state().sourcePermanent().power(), 3);
        Assert.assertEquals(plan.state().controllerHand(), 0);
        final var before = IntrinsicOutcomeQuantityBinder.bind(conditional, Map.of("X", "Count$CardPower"), model, host(), Map.of())
                .get(0).value();
        Assert.assertEquals(new OutcomePlanner<IntrinsicDrawOutcomeBackend.State>().evaluate(
                new OutcomeDescriptionCompiler<>(backend).compile(before), state).state().controllerHand(), 1);
    }

    @Test
    public void relativeEntryAndDeathCounterTriggersReachDefinitionEvaluation() {
        final var values = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Pelt Collector"), CardStateName.Original).stream()
                .filter(entry -> entry.path().contains("/trigger:")).toList();
        Assert.assertEquals(values.size(), 2);
        for (final var value : values) {
            Assert.assertEquals(value.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
            Assert.assertEquals(value.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
            Assert.assertTrue(value.contribution().complete());
            Assert.assertTrue(value.contribution().value() > 0);
        }
    }

    @Test
    public void unrestrictedCreatureEventsShareWeightedControllerAcrossAllContinuations() {
        final var second = new AbilityOutcomeDescription("second", "Draw", Map.of("Defined", "TriggeredCardController",
                "NumCards", "1"), List.of(), null, "");
        final var first = new AbilityOutcomeDescription("first", "Draw", second.parameters(), List.of(), second, "");
        final var model = IntrinsicReferenceModel.defaults();
        for (final String destination : List.of("Battlefield", "Graveyard")) {
            final var ability = new CardAbilityTraversal.AbilityDescription("event", CardAbilityTraversal.Origin.TRIGGER,
                    CardAbilityTraversal.Provenance.PRINTED, Map.of("Mode", "ChangesZone", "Origin",
                            "Graveyard".equals(destination) ? "Battlefield" : "Any", "Destination", destination,
                            "ValidCard", "Creature.Other"), first);
            Assert.assertTrue(IntrinsicWatchedCreatureBinding.needed(ability, Map.of()));
            final var cases = IntrinsicWatchedCreatureBinding.cases(ability, model, host()).orElseThrow();
            Assert.assertEquals(cases.stream().mapToDouble(WeightedValue::weight).sum(), 1.0, 1e-9);
            Assert.assertEquals(cases.stream().filter(entry -> entry.value().creature().controlledByAi())
                    .mapToDouble(WeightedValue::weight).sum(), .5, 1e-9);
            for (final var entry : cases) {
                final var bound = entry.value().bindOutcome(first);
                Assert.assertEquals(bound.parameters().get("Defined"), bound.next().parameters().get("Defined"));
                Assert.assertEquals(bound.parameters().get("Defined"), entry.value().creature().controlledByAi() ? "You" : "Opponent");
            }
            final var opposingOnly = model.withQuantities(model.quantities().with(
                    IntrinsicReferenceQuantities.Quantity.WATCHED_CREATURE_IS_OPPONENT,
                    new WeightedDistribution<>(List.of(new WeightedValue<>(1, 1.0)))));
            Assert.assertTrue(IntrinsicWatchedCreatureBinding.cases(ability, opposingOnly, host()).orElseThrow().stream()
                    .noneMatch(entry -> entry.value().creature().controlledByAi()));
        }
    }

    @Test
    public void fixedControllerScopesOverrideGenericReferencePrior() {
        final var model = IntrinsicReferenceModel.defaults();
        for (final String owner : List.of("YouCtrl", "OppCtrl")) {
            final var ability = new CardAbilityTraversal.AbilityDescription("event", CardAbilityTraversal.Origin.TRIGGER,
                    CardAbilityTraversal.Provenance.PRINTED, Map.of("Mode", "ChangesZone", "Origin", "Battlefield",
                            "Destination", "Graveyard", "ValidCard", "Creature.Other+" + owner), null);
            Assert.assertTrue(IntrinsicWatchedCreatureBinding.cases(ability, model, host()).orElseThrow().stream()
                    .allMatch(entry -> entry.value().creature().controlledByAi() == "YouCtrl".equals(owner)));
        }
        final var invalid = new WeightedDistribution<>(List.of(new WeightedValue<>(2, 1.0)));
        Assert.expectThrows(IllegalArgumentException.class, () -> model.quantities().with(
                IntrinsicReferenceQuantities.Quantity.WATCHED_CREATURE_IS_OPPONENT, invalid));
    }

    @Test
    public void unrestrictedDeathPowerFeedsExistingCounterOutcomeWithoutDoublingOccurrences() {
        final var model = IntrinsicReferenceModel.defaults();
        final var card = FModel.getMagicDb().getCommonCards().getCard("Kresh the Bloodbraided");
        final var value = new IntrinsicAbilityEvaluator(model, IntrinsicEvaluationSettings.defaults()).evaluateDefinition(card,
                CardStateName.Original).stream().filter(entry -> entry.path().endsWith("trigger:0")).findFirst().orElseThrow();
        Assert.assertEquals(value.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(value.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(value.contribution().complete());
        Assert.assertTrue(value.contribution().value() > 0);
        final var oneSide = model.withQuantities(model.quantities().with(
                IntrinsicReferenceQuantities.Quantity.WATCHED_CREATURE_IS_OPPONENT,
                new WeightedDistribution<>(List.of(new WeightedValue<>(1, 1.0)))));
        final var opposing = new IntrinsicAbilityEvaluator(oneSide, IntrinsicEvaluationSettings.defaults()).evaluateDefinition(card,
                CardStateName.Original).stream().filter(entry -> entry.path().equals(value.path())).findFirst().orElseThrow();
        Assert.assertEquals(value.expectedOccurrences(), opposing.expectedOccurrences(), 1e-9);
        Assert.assertEquals(value.contribution().value(), opposing.contribution().value(), 1e-9);
    }
}
