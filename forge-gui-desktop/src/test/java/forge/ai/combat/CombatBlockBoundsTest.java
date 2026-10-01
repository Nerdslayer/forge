package forge.ai.combat;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.effect.ValuationCompleteness;

public class CombatBlockBoundsTest {
    @Test
    public void followUpKeepsMenaceBoundsWhenTheOpposingSideHasOnlyOneReadyBlocker() {
        final var own = new PublicCombatSnapshot.Creature(20, 2, 2, 2, 0, false, false, false,
                false, false, false, false, false, false, 2, Integer.MAX_VALUE);
        final var other = new PublicCombatSnapshot.Creature(21, 2, 2, 2, 0, false, false, false,
                false, false, false, false, false, false, 2, Integer.MAX_VALUE);
        final Map<Integer, PublicCombatSnapshot.Creature> cards = Map.of(
                10, new PublicCombatSnapshot.Creature(10, 1, 0, 1, 0, false, false, false,
                        false, false, false, false, false, false),
                11, new PublicCombatSnapshot.Creature(11, 1, 3, 3, 0, false, false, false,
                        false, false, false, false, false, false), 20, own, 21, other);
        final var snapshot = new PublicCombatSnapshot(2, 1, 2, cards, Map.of(
                1, new PublicCombatSnapshot.LifeState(4, true, true, false, false),
                2, new PublicCombatSnapshot.LifeState(20, true, true, false, false)),
                Map.of(10, 2), Map.of(10, Set.of()), List.of(), List.of(), false);
        final Map<Integer, PreparedCombatValuation.PermanentValue> ledger = new java.util.LinkedHashMap<>();
        cards.forEach((id, card) -> ledger.put(id, new PreparedCombatValuation.PermanentValue(id, card.controllerId(),
                card.controllerId() == 2 ? -100 : 100, 0, 0, List.of())));
        final var current = CombatOutcomePredictor.predict(snapshot, new CombatAssignment(snapshot.attackersToDefenders(), Map.of()));
        final var future = CombatSafetyEvaluator.nextAttack(snapshot, current,
                new PublicCombatReadiness(2, Set.of(20, 21), Map.of(20, Set.of(11), 21, Set.of(11)), List.of()),
                new PreparedCombatValuation(ledger, List.of(), ValuationCompleteness.PARTIAL, List.of()), new CombatSearchBudget(1000));
        Assert.assertTrue(future.supported() && future.searchExhaustive(), future.reasons().toString());
        Assert.assertTrue(future.lethalOpportunity());
        Assert.assertEquals(future.attackers(), List.of(20, 21));
    }

    @Test
    public void maximumOneKeepsSixEligibleSingleBlocksExhaustiveButLargeMinimumIsExplicitlyBounded() {
        final Map<Integer, PublicCombatSnapshot.Creature> blockers = new java.util.LinkedHashMap<>();
        final Map<Integer, PreparedCombatValuation.PermanentValue> ledger = new java.util.LinkedHashMap<>();
        ledger.put(10, new PreparedCombatValuation.PermanentValue(10, 1, 100, 0, 0, List.of()));
        for (int id = 20; id < 29; id++) {
            blockers.put(id, creature(id, 1, 3));
            ledger.put(id, new PreparedCombatValuation.PermanentValue(id, 2, -100, 0, 0, List.of()));
        }
        final var values = new PreparedCombatValuation(ledger, List.of(), ValuationCompleteness.PARTIAL, List.of());
        final var single = new PublicCombatSnapshot.Creature(10, 1, 2, 2, 0, false, false, false,
                false, false, false, false, false, false, 1, 1);
        final var complete = CombatBlockSearch.search(snapshot(single, blockers), values, new CombatSearchBudget(1000));
        Assert.assertTrue(complete.outcomeDomainComplete() && complete.searchExhaustive());
        Assert.assertEquals(complete.best().orElseThrow().assignment().blockersByAttacker().get(10).size(), 1);
        final var large = new PublicCombatSnapshot.Creature(10, 1, 2, 2, 0, false, false, false,
                false, false, false, false, false, false, 9, Integer.MAX_VALUE);
        final var bounded = CombatBlockSearch.search(snapshot(large, blockers), values, new CombatSearchBudget(1000));
        Assert.assertTrue(bounded.outcomeDomainComplete());
        Assert.assertFalse(bounded.searchExhaustive());
        Assert.assertFalse(bounded.reasons().isEmpty());
    }

    @Test
    public void firstStrikeCasualtiesDoNotRecheckTheOriginalMinimumAtRegularDamage() {
        final var attacker = new PublicCombatSnapshot.Creature(10, 1, 4, 4, 0, false, false, true,
                false, false, false, false, false, false, 2, Integer.MAX_VALUE);
        final var snapshot = snapshot(attacker, Map.of(20, creature(20, 2, 2), 21, creature(21, 2, 2)));
        final var assignment = new CombatAssignment(snapshot.attackersToDefenders(), Map.of(10, List.of(20, 21)));
        final var damage = new CombatDamagePlan(Map.of(10, new CombatDamageAllocation.Allocation(Map.of(20, 2, 21, 2), 0)),
                Map.of(10, new CombatDamageAllocation.Allocation(Map.of(), 0)));
        final var result = CombatOutcomePredictor.predict(snapshot, assignment, damage);
        Assert.assertTrue(result.supported() && result.available(), result.reasons().toString());
        Assert.assertEquals(result.lostCreatures(), Set.of(20, 21));
        Assert.assertEquals(result.playerLifeAfter().get(2).intValue(), 20, "Nontrampler remains blocked after both blockers die");
        Assert.assertEquals(result.survivors().get(10).markedDamage(), 0);
    }

    @Test
    public void contradictoryBoundsMeanNoLegalPositiveGroupNotAnUnsupportedCard() {
        final var attacker = new PublicCombatSnapshot.Creature(10, 1, 2, 2, 0, false, false, false,
                false, false, false, false, false, false, 2, 1);
        final var snapshot = snapshot(attacker, Map.of(20, creature(20, 3, 3), 21, creature(21, 3, 3)));
        final var values = new PreparedCombatValuation(Map.of(
                10, new PreparedCombatValuation.PermanentValue(10, 1, 100, 0, 0, List.of()),
                20, new PreparedCombatValuation.PermanentValue(20, 2, -100, 0, 0, List.of()),
                21, new PreparedCombatValuation.PermanentValue(21, 2, -100, 0, 0, List.of())),
                List.of(), ValuationCompleteness.PARTIAL, List.of());
        final var result = CombatBlockSearch.search(snapshot, values, new CombatSearchBudget(100));
        Assert.assertTrue(result.outcomeDomainComplete() && result.searchExhaustive());
        Assert.assertTrue(result.best().orElseThrow().assignment().blockersByAttacker().isEmpty());
        Assert.assertEquals(result.best().orElseThrow().projection().playerLifeAfter().get(2).intValue(), 18);
    }

    private static PublicCombatSnapshot.Creature creature(final int id, final int power, final int toughness) {
        return new PublicCombatSnapshot.Creature(id, 2, power, toughness, 0, false, false, false,
                false, false, false, false, false, false);
    }

    private static PublicCombatSnapshot snapshot(final PublicCombatSnapshot.Creature attacker,
            final Map<Integer, PublicCombatSnapshot.Creature> blockers) {
        final Map<Integer, PublicCombatSnapshot.Creature> cards = new java.util.LinkedHashMap<>(blockers);
        cards.put(attacker.id(), attacker);
        return new PublicCombatSnapshot(2, 1, 2, cards, Map.of(
                1, new PublicCombatSnapshot.LifeState(20, true, true, false, false),
                2, new PublicCombatSnapshot.LifeState(20, true, true, false, false)),
                Map.of(10, 2), Map.of(10, blockers.keySet()), List.of(), List.of(), false);
    }
}
