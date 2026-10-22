package forge.ai.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.effect.ValuationCompleteness;

public class CombatDefenseSeedTest {
    @Test
    public void wholeBoardFloorSurvivesInterruptedForecastAndDoesNotMixScoringModes() {
        final Map<Integer, PublicCombatSnapshot.Creature> cards = new LinkedHashMap<>();
        final Map<Integer, Set<Integer>> pairs = new LinkedHashMap<>();
        final Set<Integer> blockers = Set.of(20, 21, 22, 23, 24, 25);
        for (int id = 10; id < 16; id++) {
            cards.put(id, creature(id, 1, 2, 2));
            pairs.put(id, blockers);
        }
        blockers.forEach(id -> cards.put(id, creature(id, 2, 3, 3)));
        final var board = snapshot(1, cards, pairs);
        final var values = values(board);
        final var budget = new CombatSearchBudget(5000, 500, () -> 0L);
        final var seeds = prepare(board, values, Map.of(), budget.childAllowance(1250));
        Assert.assertEquals(seeds.floor().best().orElseThrow().assignment().blockersByAttacker().size(), 6);
        Assert.assertEquals(seeds.floor().best().orElseThrow().projection().terminal(), CombatProjection.Terminal.NONE);
        Assert.assertFalse(seeds.floor().searchExhaustive());
        final var optional = budget.childAllowance(50);
        final var partial = CombatBlockSearch.searchWithSeeds(board, values, optional,
                new PublicCombatReadiness(2, Set.of(), Map.of(), List.of()), Map.of(), seeds.assignments(), state -> {
                    while (optional.tryConsume()) { /* Deliberately interrupt only optional work. */ }
                    return new CombatSafetyEvaluator.FollowUp(false, false, 0, false, List.of(), List.of());
                });
        final var recovered = CombatBlockSearch.preserveFloor(board, partial, seeds);
        Assert.assertEquals(recovered.best(), seeds.floor().best());
        Assert.assertTrue(recovered.outcomeDomainComplete());
        Assert.assertTrue(recovered.selectedAllocationComplete());
        Assert.assertEquals(recovered.best().orElseThrow().pressureValue(), 0);
        Assert.assertTrue(recovered.best().orElseThrow().followUp().isEmpty());
        Assert.assertTrue(budget.remaining() > 3000);
    }

    @Test
    public void scarceLegalBlockerIsSavedAndHopelessDefenseStillSpreadsBlocks() {
        final var cards = Map.of(10, creature(10, 1, 8, 8), 11, creature(11, 1, 7, 7),
                20, creature(20, 2, 1, 1), 21, creature(21, 2, 1, 1));
        final var board = snapshot(1, cards, Map.of(10, Set.of(20, 21), 11, Set.of(20)));
        final var seeds = prepare(board, values(board), Map.of(), new CombatSearchBudget(4000));
        Assert.assertEquals(seeds.floor().best().orElseThrow().assignment().blockersByAttacker(),
                Map.of(10, List.of(21), 11, List.of(20)));
        final var extra = new LinkedHashMap<>(cards);
        extra.put(12, creature(12, 1, 2, 2));
        final var hopeless = snapshot(1, extra, Map.of(10, Set.of(20, 21), 11, Set.of(20), 12, Set.of(20, 21)));
        final var defense = prepare(hopeless, values(hopeless), Map.of(), new CombatSearchBudget(4000)).floor().best().orElseThrow();
        Assert.assertEquals(defense.assignment().blockersByAttacker().size(), 2);
        Assert.assertEquals(defense.projection().terminal(), CombatProjection.Terminal.LOSS);
        Assert.assertEquals(defense.projection().playerLifeAfter().get(2).intValue(), -1);
    }

    @Test
    public void fixedMenaceGroupIsCompletedWithoutDuplicatesAndInvalidPairsAreRejected() {
        final var menace = new PublicCombatSnapshot.Creature(10, 1, 3, 3, 0, false, false, false,
                false, false, false, false, false, false, 2, 2);
        final var board = snapshot(1, Map.of(10, menace, 11, creature(11, 1, 3, 3),
                20, creature(20, 2, 1, 1), 21, creature(21, 2, 1, 1), 22, creature(22, 2, 1, 1)),
                Map.of(10, Set.of(20, 21), 11, Set.of(21, 22)));
        final var seeds = prepare(board, values(board), Map.of(10, List.of(20)), new CombatSearchBudget(4000));
        Assert.assertTrue(seeds.floor().best().isPresent());
        for (final var assignment : seeds.assignments()) {
            Assert.assertEquals(Set.copyOf(assignment.blockersByAttacker().get(10)), Set.of(20, 21));
            final var assigned = assignment.blockersByAttacker().values().stream().flatMap(List::stream).toList();
            Assert.assertEquals(Set.copyOf(assigned).size(), assigned.size());
        }
        Assert.assertTrue(prepare(board, values(board), Map.of(10, List.of(22)), new CombatSearchBudget(4000)).assignments().isEmpty());
    }

    @Test
    public void trampleGroupsAreVerifiedAndAnInterruptedAllocationIsNotASurvivalCertificate() {
        final var trample = new PublicCombatSnapshot.Creature(10, 1, 8, 8, 0, false, false, false,
                false, false, true, false, false, false);
        final var board = snapshot(4, Map.of(10, trample, 20, creature(20, 2, 1, 1), 21, creature(21, 2, 1, 4)),
                Map.of(10, Set.of(20, 21)));
        final var seeds = prepare(board, values(board), Map.of(), new CombatSearchBudget(4000));
        Assert.assertEquals(seeds.floor().best().orElseThrow().projection().terminal(), CombatProjection.Terminal.NONE);
        Assert.assertEquals(seeds.floor().best().orElseThrow().projection().playerLifeAfter().get(2).intValue(), 1);
        final var partial = CombatBlockSearch.verifySeeds(board, values(board), new CombatSearchBudget(2), Map.of(),
                List.of(new CombatAssignment(board.attackersToDefenders(), Map.of()),
                        new CombatAssignment(board.attackersToDefenders(), Map.of(10, List.of(20, 21)))));
        Assert.assertEquals(partial.assignments().size(), 1);
        Assert.assertEquals(partial.floor().best().orElseThrow().projection().terminal(), CombatProjection.Terminal.LOSS);
    }

    @Test
    public void scarceChumpStopsPreventableDamageInsteadOfBeingWastedOnABiggerTrampler() {
        final var trample = new PublicCombatSnapshot.Creature(10, 1, 8, 8, 0, false, false, false,
                false, false, true, false, false, false);
        final var board = snapshot(9, Map.of(10, trample, 11, creature(11, 1, 7, 7), 20, creature(20, 2, 1, 1)),
                Map.of(10, Set.of(20), 11, Set.of(20)));
        final var seeds = prepare(board, values(board), Map.of(), new CombatSearchBudget(4000));
        Assert.assertEquals(seeds.floor().best().orElseThrow().assignment().blockersByAttacker(), Map.of(11, List.of(20)));
        Assert.assertEquals(seeds.floor().best().orElseThrow().projection().playerLifeAfter().get(2).intValue(), 1);
        final var baseline = seeds.floor().unblocked().orElseThrow();
        final var inflated = new CombatBlockSearch.Candidate(baseline.assignment(), baseline.projection(), baseline.score(),
                baseline.damagePlan(), 1000000, 0, java.util.Optional.empty(), 0);
        final var advanced = new CombatBlockSearch.Result(java.util.Optional.of(inflated), seeds.floor().unblocked(),
                false, true, 1, List.of(), 1, 0, true);
        Assert.assertEquals(CombatBlockSearch.preserveFloor(board, advanced, seeds).best(), seeds.floor().best(),
                "Future utility cannot override a verified immediate survival line");
    }

    private static CombatBlockSearch.Seeds prepare(final PublicCombatSnapshot snapshot, final PreparedCombatValuation values,
            final Map<Integer, List<Integer>> fixed, final CombatSearchBudget budget) {
        return CombatBlockSearch.verifySeeds(snapshot, values, budget, fixed,
                CombatDefenseSeedBuilder.build(snapshot, values, fixed, budget));
    }

    private static PublicCombatSnapshot.Creature creature(final int id, final int controller, final int power, final int toughness) {
        return new PublicCombatSnapshot.Creature(id, controller, power, toughness, 0,
                false, false, false, false, false, false, false, false, false);
    }

    private static PublicCombatSnapshot snapshot(final int life, final Map<Integer, PublicCombatSnapshot.Creature> cards,
            final Map<Integer, Set<Integer>> pairs) {
        final Map<Integer, Integer> attackers = new LinkedHashMap<>();
        pairs.keySet().forEach(id -> attackers.put(id, 2));
        return new PublicCombatSnapshot(2, 1, 2, cards, Map.of(
                1, new PublicCombatSnapshot.LifeState(20, true, true, false, false),
                2, new PublicCombatSnapshot.LifeState(life, true, true, false, false)), attackers, pairs, List.of(), List.of(), false);
    }

    private static PreparedCombatValuation values(final PublicCombatSnapshot board) {
        final Map<Integer, PreparedCombatValuation.PermanentValue> result = new LinkedHashMap<>();
        board.creatures().forEach((id, card) -> result.put(id, new PreparedCombatValuation.PermanentValue(id, card.controllerId(),
                card.controllerId() == 2 ? -100 : 100, 0, 0, List.of())));
        return new PreparedCombatValuation(result, List.of(), ValuationCompleteness.PARTIAL, List.of());
    }
}
