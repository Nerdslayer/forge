package forge.ai.effect;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.AITest;
import forge.ai.effect.IntrinsicDrawOutcomeBackend.State;
import forge.ai.effect.IntrinsicDrawOutcomeBackend.TargetRef;
import forge.ai.effect.IntrinsicReferenceModel.PermanentKind;
import forge.ai.effect.IntrinsicReferenceModel.PermanentProfile;
import forge.card.CardStateName;
import forge.model.FModel;

public class IntrinsicCounterMultiplicationTest extends AITest {
    private static AbilityOutcomeDescription multiply(final String recipient, final AbilityOutcomeDescription next) {
        return new AbilityOutcomeDescription("multiply", "MultiplyCounter", Map.of("CounterType", "P1P1", "Defined", recipient),
                List.of(), next, "");
    }

    private static State state() {
        return new State(3, 3).withSourcePermanent(new PermanentProfile(true, PermanentKind.CREATURE, true, 2, 2, Set.of()));
    }

    private static OutcomePlan<State> evaluate(final AbilityOutcomeDescription node, final State state) {
        final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults(), state.sourcePermanent());
        return new OutcomePlanner<State>().evaluate(new OutcomeDescriptionCompiler<>(backend).compile(node), state);
    }

    @Test
    public void additionsAndRepeatedMultiplicationUseProjectedInventoryNotResampledCounts() {
        final State initial = IntrinsicDrawOutcomeBackend.initializeP1p1(state(), TargetRef.SOURCE, 1);
        final var tail = multiply("Self", null);
        final var add = new AbilityOutcomeDescription("add", "PutCounter",
                Map.of("Defined", "Self", "CounterType", "P1P1", "CounterNum", "1"), List.of(),
                new AbilityOutcomeDescription("life", "GainLife", Map.of("LifeAmount", "1"), List.of(), tail, ""), "");
        final var plan = evaluate(add, initial);
        Assert.assertTrue(plan.complete());
        Assert.assertEquals(plan.state().p1p1(TargetRef.SOURCE), 4);
        Assert.assertEquals(plan.state().sourcePermanent().power(), 6);
        Assert.assertEquals(plan.state().sourcePermanent().toughness(), 6);
        final var twice = evaluate(multiply("Self", tail), initial);
        Assert.assertTrue(twice.complete());
        Assert.assertEquals(twice.state().p1p1(TargetRef.SOURCE), 4);
        Assert.assertEquals(twice.state().sourcePermanent().power(), 6);
        Assert.assertEquals(evaluate(tail, state()).value(), 0.0);
    }

    @Test
    public void groupMultiplicationValuesEachRepresentativeAndHostWithCorrectPerspective() {
        State initial = state().withCreatures(true, new IntrinsicReferenceModel.CreatureProfile(true, 2, 2, Set.of(), false, false))
                .withCreatureCount(true, 2);
        initial = IntrinsicDrawOutcomeBackend.initializeP1p1(initial, TargetRef.CONTROLLER_CREATURE, 1);
        initial = IntrinsicDrawOutcomeBackend.initializeP1p1(initial, TargetRef.SOURCE, 2);
        final var plan = evaluate(multiply("Valid Creature.YouCtrl", null), initial);
        Assert.assertTrue(plan.complete());
        Assert.assertEquals(plan.state().controllerCreature().power(), 4);
        Assert.assertEquals(plan.state().sourcePermanent().power(), 6);
        Assert.assertEquals(plan.state().p1p1(TargetRef.CONTROLLER_CREATURE), 2);
        Assert.assertEquals(plan.state().p1p1(TargetRef.SOURCE), 4);
        final var other = evaluate(multiply("Valid Creature.Other+YouCtrl", null), initial);
        Assert.assertTrue(other.complete());
        Assert.assertEquals(other.state().sourcePermanent().power(), 4);
        Assert.assertTrue(plan.value() > other.value());
        State opposing = state().withCreatures(false, initial.controllerCreature()).withCreatureCount(false, 2);
        opposing = opposing.withP1p1(TargetRef.OPPONENT_CREATURE, 1);
        Assert.assertEquals(evaluate(multiply("Valid Creature.OppCtrl", null), opposing).value(), -other.value());
    }

    @Test
    public void unknownInventoriesAndInventoryChangingChainsRemainIncomplete() {
        final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults());
        for (final var params : List.of(Map.of("Defined", "Self", "CounterType", "LOYALTY"),
                Map.of("Defined", "Self", "CounterType", "P1P1", "Multiplier", "0"),
                Map.of("Defined", "Valid Creature.Elf+YouCtrl", "CounterType", "P1P1"))) {
            Assert.assertFalse(backend.acceptsNode(new AbilityOutcomeDescription("unknown", "MultiplyCounter", params, List.of(), null, "")));
        }
        final var remove = new AbilityOutcomeDescription("remove", "RemoveCounter", Map.of("Defined", "Self",
                "CounterType", "P1P1", "CounterNum", "1"), List.of(), multiply("Self", null), "");
        final var initial = IntrinsicDrawOutcomeBackend.initializeP1p1(state(), TargetRef.SOURCE, 2);
        final var removedThenDoubled = evaluate(remove, initial);
        Assert.assertTrue(removedThenDoubled.complete());
        Assert.assertEquals(removedThenDoubled.state().p1p1(TargetRef.SOURCE), 2);
        Assert.assertEquals(removedThenDoubled.state().sourcePermanent().power(), 4);
        final var minus = new AbilityOutcomeDescription("minus", "PutCounter", Map.of("Defined", "Self",
                "CounterType", "M1M1", "CounterNum", "1"), List.of(), multiply("Self", null), "");
        Assert.assertFalse(evaluate(minus, state()).complete());
        final var conditioned = IntrinsicReferenceModel.defaults().withQuantityBindings(Map.of(
                IntrinsicDrawOutcomeBackend.SOURCE_P1P1, 2));
        Assert.assertEquals(conditioned.referenceIntegers(IntrinsicDrawOutcomeBackend.SOURCE_P1P1,
                conditioned.quantities().distribution(IntrinsicReferenceQuantities.Quantity.P1P1_COUNTERS)).entries()
                .get(0).value().intValue(), 2);
        final var dimensions = backend.referenceDimensions(multiply("Valid Creature.YouCtrl", null));
        Assert.assertTrue(dimensions.containsAll(Set.of(IntrinsicDrawOutcomeBackend.SOURCE_P1P1,
                IntrinsicDrawOutcomeBackend.CONTROLLER_P1P1, IntrinsicDrawOutcomeBackend.CONTROLLER_CREATURE_COUNT)));
    }

    @Test
    public void benchmarkCounterEngineReachesIntrinsicOutcomeEvaluation() {
        final var result = new IntrinsicAbilityEvaluator(IntrinsicReferenceModel.defaults(), IntrinsicEvaluationSettings.defaults())
                .evaluateDefinition(FModel.getMagicDb().getCommonCards().getCard("Bristly Bill, Spine Sower"), CardStateName.Original)
                .stream().filter(value -> value.path().endsWith("ability:0")).findFirst().orElseThrow();
        Assert.assertEquals(result.triggerStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertEquals(result.outcomeStatus(), IntrinsicAbilityEvaluator.SupportStatus.SUPPORTED);
        Assert.assertTrue(result.contribution().complete());
        Assert.assertTrue(result.contribution().value() > 0);
    }

    @Test
    public void removalClampsToInventoryAndAllRemovalCanKillACounterBasedBody() {
        final var initial = IntrinsicDrawOutcomeBackend.initializeP1p1(state(), TargetRef.SOURCE, 2);
        final var excessive = new AbilityOutcomeDescription("remove", "RemoveCounter", Map.of("Defined", "Self",
                "CounterType", "P1P1", "CounterNum", "9"), List.of(), multiply("Self", null), "");
        final var plan = evaluate(excessive, initial);
        Assert.assertTrue(plan.complete());
        Assert.assertEquals(plan.state().sourcePermanent().power(), 2);
        Assert.assertEquals(plan.state().p1p1(TargetRef.SOURCE), 0);
        Assert.assertTrue(plan.value() < 0);
        final var zero = evaluate(excessive, state().withP1p1(TargetRef.SOURCE, 0));
        Assert.assertTrue(zero.complete());
        Assert.assertEquals(zero.value(), 0.0);
        final var all = new AbilityOutcomeDescription("all", "RemoveCounter", Map.of("Defined", "Self",
                "CounterType", "P1P1", "CounterNum", "All"), List.of(), multiply("Self", null), "");
        final var body = new PermanentProfile(true, PermanentKind.CREATURE, true, 0, 0, Set.of());
        final var counterBody = IntrinsicDrawOutcomeBackend.initializeP1p1(state().withSourcePermanent(body), TargetRef.SOURCE, 4);
        final var dead = evaluate(all, counterBody);
        Assert.assertTrue(dead.complete());
        Assert.assertFalse(dead.state().sourcePermanent().present());
        Assert.assertEquals(dead.state().p1p1(TargetRef.SOURCE), 0);
        Assert.assertTrue(dead.value() < 0);
    }

    @Test
    public void groupRemovalUsesSeparateHostAndRepresentativeInventory() {
        State initial = state().withCreatures(true, new IntrinsicReferenceModel.CreatureProfile(true, 2, 2, Set.of(), false, false))
                .withCreatureCount(true, 2);
        initial = IntrinsicDrawOutcomeBackend.initializeP1p1(initial, TargetRef.CONTROLLER_CREATURE, 1);
        initial = IntrinsicDrawOutcomeBackend.initializeP1p1(initial, TargetRef.SOURCE, 2);
        final var remove = new AbilityOutcomeDescription("group", "RemoveCounterAll", Map.of("ValidCards", "Creature.YouCtrl",
                "CounterType", "P1P1", "CounterNum", "3"), List.of(), multiply("Valid Creature.YouCtrl", null), "");
        final var plan = evaluate(remove, initial);
        Assert.assertTrue(plan.complete());
        Assert.assertEquals(plan.state().controllerCreature().power(), 2);
        Assert.assertEquals(plan.state().sourcePermanent().power(), 2);
        Assert.assertEquals(plan.state().p1p1(TargetRef.CONTROLLER_CREATURE), 0);
        Assert.assertEquals(plan.state().p1p1(TargetRef.SOURCE), 0);
        final var backend = new IntrinsicDrawOutcomeBackend(IntrinsicEvaluationSettings.defaults());
        Assert.assertFalse(backend.acceptsNode(new AbilityOutcomeDescription("wrongAll", "RemoveCounterAll",
                Map.of("ValidCards", "Creature.YouCtrl", "CounterType", "P1P1", "CounterNum", "All"), List.of(), null, "")));
    }
}
