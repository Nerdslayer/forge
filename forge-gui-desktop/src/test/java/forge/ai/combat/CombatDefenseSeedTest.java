package forge.ai.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;
import org.testng.annotations.DataProvider;

import forge.ai.effect.ValuationCompleteness;

public class CombatDefenseSeedTest {
    @DataProvider(name = "strongBlockers")
    public Object[][] strongBlockers() {
        return new Object[][] {{19, 100}, {29, 100}, {19, 700}, {29, 700}};
    }

    @Test(dataProvider = "strongBlockers")
    public void safeKillIsFoundRegardlessOfCheapDecoysOrPreparedAbilityValue(final int strongId, final int strongValue) {
        final var board = snapshot(12, Map.of(10, creature(10, 1, 2, 3), 11, creature(11, 1, 1, 1),
                20, creature(20, 2, 2, 2), 21, creature(21, 2, 2, 1), strongId, creature(strongId, 2, 4, 4)),
                Map.of(10, Set.of(20, 21, strongId), 11, Set.of()));
        final var original = values(board);
        final var ledger = new LinkedHashMap<>(original.permanents());
        ledger.put(strongId, new PreparedCombatValuation.PermanentValue(strongId, 2, -strongValue, 0, 0, List.of()));
        final var prepared = new PreparedCombatValuation(ledger, List.of(), ValuationCompleteness.PARTIAL, List.of());
        final var budget = new CombatSearchBudget(5000, 500, () -> 0L);
        final var seeds = prepare(board, prepared, Map.of(), budget.childAllowance(1250));
        final var floor = seeds.floor().best().orElseThrow();
        Assert.assertEquals(floor.assignment().blockersByAttacker(), Map.of(10, List.of(strongId)));
        Assert.assertEquals(floor.projection().lostPermanents(), Set.of(10));
        Assert.assertEquals(floor.projection().playerLifeAfter().get(2).intValue(), 11);
        Assert.assertTrue(seeds.assignments().get(0).blockersByAttacker().isEmpty());
        Assert.assertTrue(seeds.assignments().size() <= 4);
        final var optional = budget.nodeAllowance(50);
        final var partial = CombatBlockSearch.searchWithSeeds(board, prepared, optional,
                new PublicCombatReadiness(2, Set.of(), Map.of(), List.of()), Map.of(), seeds.assignments(), state -> {
                    while (optional.tryConsume()) { /* Interrupt optional forecasts after retaining the safe block. */ }
                    return new CombatSafetyEvaluator.FollowUp(false, false, 0, false, List.of(), List.of());
                });
        Assert.assertEquals(CombatBlockSearch.preserveFloor(board, partial, seeds).best(), seeds.floor().best());
        Assert.assertTrue(budget.remaining() > 3000);
    }

    @Test
    public void harmlessBlockPreventsDamageButBadTradesAtComfortableLifeDoNot() {
        final var safe = snapshot(20, Map.of(10, creature(10, 1, 2, 3), 20, creature(20, 2, 0, 4)), Map.of(10, Set.of(20)));
        final var defense = prepare(safe, values(safe), Map.of(), new CombatSearchBudget(4000)).floor().best().orElseThrow();
        Assert.assertEquals(defense.assignment().blockersByAttacker(), Map.of(10, List.of(20)));
        Assert.assertTrue(defense.projection().lostPermanents().isEmpty());
        final var bad = snapshot(40, Map.of(10, creature(10, 1, 2, 3), 20, creature(20, 2, 1, 1)), Map.of(10, Set.of(20)));
        Assert.assertTrue(prepare(bad, values(bad), Map.of(), new CombatSearchBudget(4000))
                .floor().best().orElseThrow().assignment().blockersByAttacker().isEmpty());
    }

    @Test
    public void fourMemberFirstStrikeGangIsAtomicAndLegacyCoverageIsHonest() {
        final var cards = new LinkedHashMap<Integer, PublicCombatSnapshot.Creature>();
        cards.put(10, new PublicCombatSnapshot.Creature(10, 1, 4, 4, 0,
                false, true, false, false, false, false, false, false, false));
        for (int id = 20; id < 24; id++) { cards.put(id, creature(id, 2, 2, 2)); }
        final var board = snapshot(20, cards, Map.of(10, Set.of(20, 21, 22, 23)));
        final var ledger = new LinkedHashMap<>(values(board).permanents());
        ledger.put(10, new PreparedCombatValuation.PermanentValue(10, 1, 800, 0, 0, List.of()));
        final var prepared = new PreparedCombatValuation(ledger, List.of(), ValuationCompleteness.PARTIAL, List.of());
        final var seeds = prepare(board, prepared, Map.of(), new CombatSearchBudget(4000, 500, () -> 0L));
        final var best = seeds.floor().best().orElseThrow();
        Assert.assertEquals(best.assignment().blockersByAttacker().get(10).size(), 4);
        Assert.assertTrue(best.projection().lostPermanents().contains(10));
        Assert.assertEquals(best.projection().lostPermanents().size(), 3);
        Assert.assertTrue(best.damagePlan().isPresent());
        final var legacy = new PublicCombatSnapshot(2, 1, 2, cards, board.players(), board.attackersToDefenders(),
                board.legalBlockers(), List.of(), List.of(), true);
        final var legacySeeds = prepare(legacy, prepared, Map.of(), new CombatSearchBudget(4000, 500, () -> 0L));
        Assert.assertTrue(legacySeeds.floor().best().isPresent());
        Assert.assertTrue(legacySeeds.assignments().stream().noneMatch(a -> a.blockersByAttacker().getOrDefault(10, List.of()).size() == 4),
                "Incomplete legacy order alternatives must not be advertised as a verified floor");
    }

    @Test
    public void interruptedTrialAndNodeOnlySlicesPreserveCompletedResultsAndDeadline() {
        final var clock = new java.util.concurrent.atomic.AtomicLong();
        final var budget = new CombatSearchBudget(100, 100, clock::get);
        final var slice = budget.nodeAllowance(20);
        clock.set(60_000_000L);
        Assert.assertTrue(slice.tryConsume(), "Node-only views do not shorten the parent's deadline");
        Assert.assertEquals(budget.used(), 1);
        final var board = snapshot(12, Map.of(10, creature(10, 1, 4, 4), 20, creature(20, 2, 2, 2),
                21, creature(21, 2, 2, 2)), Map.of(10, Set.of(20, 21)));
        final var evaluator = new CombatBlockSearch.ImmediateEvaluator(board, values(board), Map.of(), budget);
        final var baseline = new CombatAssignment(board.attackersToDefenders(), Map.of());
        final var verified = evaluator.evaluate(baseline, budget);
        Assert.assertNotNull(verified);
        Assert.assertNull(evaluator.evaluate(new CombatAssignment(board.attackersToDefenders(), Map.of(10, List.of(20, 21))), budget.nodeAllowance(3)));
        Assert.assertSame(evaluator.evaluate(baseline, budget), verified);
        Assert.assertEquals(evaluator.cacheHits(), 1);
        clock.set(100_000_000L);
        Assert.assertEquals(slice.remaining(), 0);
        Assert.assertEquals(budget.remaining(), 0);
    }

    @Test
    public void largePairDomainIsBoundedAndNeverAdvertisedAsExhaustive() {
        final var cards = new LinkedHashMap<Integer, PublicCombatSnapshot.Creature>();
        final var pairs = new LinkedHashMap<Integer, Set<Integer>>();
        final var blockers = new java.util.LinkedHashSet<Integer>();
        for (int id = 100; id < 120; id++) { cards.put(id, creature(id, 2, 3, 3)); blockers.add(id); }
        for (int id = 10; id < 30; id++) { cards.put(id, creature(id, 1, 2, 2)); pairs.put(id, blockers); }
        final var board = snapshot(20, cards, pairs);
        final var budget = new CombatSearchBudget(5000, 500, () -> 0L);
        final var result = prepare(board, values(board), Map.of(), budget.nodeAllowance(4000));
        Assert.assertTrue(result.floor().best().isPresent());
        Assert.assertFalse(result.floor().searchExhaustive());
        Assert.assertTrue(result.floor().evaluations() <= 256);
        Assert.assertTrue(result.assignments().size() <= 4);
        Assert.assertTrue(budget.remaining() >= 1000);
        for (final var assignment : result.assignments()) {
            final var assigned = assignment.blockersByAttacker().values().stream().flatMap(List::stream).toList();
            Assert.assertEquals(assigned.size(), Set.copyOf(assigned).size());
        }
    }

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
        final var partial = prepare(board, values(board), Map.of(), new CombatSearchBudget(4, 500, () -> 0L));
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
        return CombatDefenseFloorSearch.prepare(snapshot, values, fixed, budget);
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
