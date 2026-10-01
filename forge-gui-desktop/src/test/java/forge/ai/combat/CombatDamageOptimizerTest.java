package forge.ai.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.effect.ValuationCompleteness;

public class CombatDamageOptimizerTest {
    @Test
    public void allocationUsesFullEngineValueForEitherObservingSide() {
        for (final int observer : List.of(1, 2)) {
            final PublicCombatSnapshot snapshot = snapshot(observer, Map.of(
                    10, creature(10, 1, 2, 5, false), 20, creature(20, 2, 1, 1, false),
                    21, creature(21, 2, 1, 2, false)), false);
            final PreparedCombatValuation values = values(snapshot, Map.of(20, 500, 21, 100));
            final CombatAssignment assignment = new CombatAssignment(snapshot.attackersToDefenders(), Map.of(10, List.of(20, 21)));
            final CombatDamageOptimizer.Result result = CombatDamageOptimizer.optimize(snapshot, assignment, values, new CombatSearchBudget(1000));
            Assert.assertTrue(result.exhaustive() && result.outcomeSupported());
            final CombatDamageOptimizer.Candidate best = result.best().orElseThrow();
            Assert.assertEquals(best.projection().lostCreatures(), Set.of(20), "The assigning attacker prefers killing the engine");
            Assert.assertEquals(best.score().permanentLoss().total(), observer == 1 ? 500 : -500);
            Assert.assertEquals(CombatOutcomePredictor.predict(snapshot, assignment, best.damagePlan()), best.projection());
        }
    }

    @Test
    public void firstStrikeAllocationConsidersWhoWillSurviveToDealRegularDamage() {
        final PublicCombatSnapshot snapshot = snapshot(1, Map.of(
                10, creature(10, 1, 3, 3, true), 20, creature(20, 2, 2, 2, false),
                21, creature(21, 2, 3, 3, false)), false);
        final CombatAssignment assignment = new CombatAssignment(snapshot.attackersToDefenders(), Map.of(10, List.of(20, 21)));
        final CombatDamageOptimizer.Result result = CombatDamageOptimizer.optimize(snapshot, assignment, values(snapshot, Map.of()),
                new CombatSearchBudget(5000));
        Assert.assertTrue(result.exhaustive());
        final CombatDamageOptimizer.Candidate best = result.best().orElseThrow();
        Assert.assertEquals(best.damagePlan().firstStrike().get(10).creatureDamage(), Map.of(21, 3));
        Assert.assertEquals(best.projection().lostCreatures(), Set.of(20, 21));
        Assert.assertTrue(best.projection().survivors().containsKey(10));
        Assert.assertEquals(best.projection().survivors().get(10).markedDamage(), 2);
        Assert.assertEquals(CombatOutcomePredictor.predict(snapshot, assignment, best.damagePlan()), best.projection());
    }

    @Test
    public void fixedLegacyOrderIsRespectedAndNotConfusedWithChoosingAnOrder() {
        final Map<Integer, PublicCombatSnapshot.Creature> creatures = Map.of(
                10, creature(10, 1, 2, 5, false), 20, creature(20, 2, 1, 2, false), 21, creature(21, 2, 1, 1, false));
        final PublicCombatSnapshot legacy = snapshot(1, creatures, true);
        final CombatAssignment assignment = new CombatAssignment(legacy.attackersToDefenders(), Map.of(10, List.of(20, 21)));
        final PreparedCombatValuation values = values(legacy, Map.of(20, 100, 21, 500));
        Assert.assertEquals(CombatDamageOptimizer.optimize(legacy, assignment, values, new CombatSearchBudget(1000))
                .best().orElseThrow().projection().lostCreatures(), Set.of(20));
        final CombatDamageOptimizer.Result selectableOrders = CombatDamageOptimizer.optimizeBlockGroups(legacy, assignment, values,
                new CombatSearchBudget(1000));
        Assert.assertTrue(selectableOrders.exhaustive());
        final CombatDamageOptimizer.Candidate ordered = selectableOrders.best().orElseThrow();
        Assert.assertEquals(ordered.projection().lostCreatures(), Set.of(21));
        Assert.assertEquals(ordered.assignment().blockersByAttacker().get(10), List.of(21, 20));
        Assert.assertEquals(CombatOutcomePredictor.predict(legacy, ordered.assignment(), ordered.damagePlan()), ordered.projection());
        final PublicCombatSnapshot modern = snapshot(1, creatures, false);
        Assert.assertEquals(CombatDamageOptimizer.optimize(modern, assignment, values, new CombatSearchBudget(1000))
                .best().orElseThrow().projection().lostCreatures(), Set.of(21));
    }

    @Test
    public void allocationAndRepeatedProjectionShareOneAllowance() {
        final PublicCombatSnapshot snapshot = snapshot(1, Map.of(
                10, creature(10, 1, 30, 5, false), 20, creature(20, 2, 1, 2, false), 21, creature(21, 2, 1, 1, false)), false);
        final CombatAssignment assignment = new CombatAssignment(snapshot.attackersToDefenders(), Map.of(10, List.of(20, 21)));
        final CombatSearchBudget budget = new CombatSearchBudget(8);
        Assert.assertTrue(budget.tryConsume());
        final CombatDamageOptimizer.Result result = CombatDamageOptimizer.optimize(snapshot, assignment, values(snapshot, Map.of()), budget);
        Assert.assertFalse(result.exhaustive());
        Assert.assertTrue(result.outcomeSupported(), "Not reaching every choice is not an unsupported mechanic");
        Assert.assertEquals(result.nodes(), 7);
        Assert.assertEquals(budget.used(), 8);
        Assert.assertFalse(result.reasons().isEmpty());
    }

    @Test
    public void repeatsKeepPlansAndScoresStableWithoutChangingFrozenInputs() {
        final PublicCombatSnapshot snapshot = snapshot(1, Map.of(
                10, creature(10, 1, 3, 4, true), 20, creature(20, 2, 1, 2, false), 21, creature(21, 2, 2, 2, false)), false);
        final CombatAssignment assignment = new CombatAssignment(snapshot.attackersToDefenders(), Map.of(10, List.of(20, 21)));
        final PreparedCombatValuation values = values(snapshot, Map.of());
        final CombatDamageOptimizer.Result result = CombatDamageOptimizer.optimize(snapshot, assignment, values, new CombatSearchBudget(5000));
        Assert.assertEquals(CombatDamageOptimizer.optimize(snapshot, assignment, values, new CombatSearchBudget(5000)), result);
        Assert.assertEquals(snapshot.creatures().get(10).markedDamage(), 0);
        Assert.expectThrows(UnsupportedOperationException.class, () -> result.best().orElseThrow().damagePlan().regular().clear());
    }

    private static PublicCombatSnapshot.Creature creature(final int id, final int controller, final int power,
            final int toughness, final boolean doubleStrike) {
        return new PublicCombatSnapshot.Creature(id, controller, power, toughness, 0,
                false, false, doubleStrike, false, false, false, false, false, false);
    }

    private static PublicCombatSnapshot snapshot(final int observer, final Map<Integer, PublicCombatSnapshot.Creature> creatures,
            final boolean legacyOrder) {
        return new PublicCombatSnapshot(observer, 1, 2, creatures, Map.of(
                1, new PublicCombatSnapshot.LifeState(20, true, true, false, false),
                2, new PublicCombatSnapshot.LifeState(20, true, true, false, false)),
                Map.of(10, 2), Map.of(10, Set.of(20, 21)), List.of(), List.of(), legacyOrder);
    }

    private static PreparedCombatValuation values(final PublicCombatSnapshot snapshot, final Map<Integer, Integer> overrides) {
        final Map<Integer, PreparedCombatValuation.PermanentValue> values = new LinkedHashMap<>();
        snapshot.creatures().forEach((id, card) -> values.put(id, new PreparedCombatValuation.PermanentValue(id, card.controllerId(),
                (card.controllerId() == snapshot.observingPlayerId() ? -1 : 1) * overrides.getOrDefault(id, 100), 0, 0, List.of())));
        return new PreparedCombatValuation(values, List.of(), ValuationCompleteness.PARTIAL, List.of());
    }
}
