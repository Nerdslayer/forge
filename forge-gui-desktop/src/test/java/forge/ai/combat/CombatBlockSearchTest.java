package forge.ai.combat;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.effect.ValuationCompleteness;

/** Frozen-state search fixtures, independent of controller callbacks and hidden game state. */
public class CombatBlockSearchTest {
    @Test
    public void completeSmallGroupDomainCanFindASixCreatureKillInsteadOfOnlyChumps() {
        final Map<Integer, PublicCombatSnapshot.Creature> creatures = new LinkedHashMap<>();
        creatures.put(10, creature(10, 1, 5, 6));
        for (int id = 20; id < 26; id++) { creatures.put(id, creature(id, 2, 1, 1)); }
        final var snapshot = snapshot(2, 1, creatures, Map.of(10, Set.of(20, 21, 22, 23, 24, 25)));
        final Map<Integer, PreparedCombatValuation.PermanentValue> cards = new LinkedHashMap<>(values(snapshot).permanents());
        cards.put(10, new PreparedCombatValuation.PermanentValue(10, 1, 1000, 0, 0, List.of()));
        final var ledger = new PreparedCombatValuation(cards, List.of(), ValuationCompleteness.PARTIAL, List.of());
        final var result = CombatBlockSearch.search(snapshot, ledger, new CombatSearchBudget(200000));
        Assert.assertTrue(result.outcomeDomainComplete() && result.searchExhaustive(), result.reasons().toString());
        final var best = result.best().orElseThrow();
        Assert.assertEquals(best.assignment().blockersByAttacker().get(10).size(), 6);
        Assert.assertTrue(best.projection().lostCreatures().contains(10));
        Assert.assertEquals(best.projection().playerLifeAfter().get(2).intValue(), 1);
        Assert.assertEquals(best.score().permanentLoss().total(), 500);
    }

    @Test
    public void approximateSearchAndLocalSwapsPreserveFixedBlockerOwnership() {
        final Map<Integer, PublicCombatSnapshot.Creature> creatures = new LinkedHashMap<>();
        final Map<Integer, Set<Integer>> blocks = new LinkedHashMap<>();
        for (int id = 10; id < 18; id++) {
            creatures.put(id, creature(id, 1, 2, 2));
            blocks.put(id, Set.of(20, 21));
        }
        creatures.put(20, creature(20, 2, 0, 3));
        creatures.put(21, creature(21, 2, 0, 3));
        final var snapshot = snapshot(2, 20, creatures, blocks);
        // No attackers next turn isolates assignment search without nested reply work.
        final var readiness = new PublicCombatReadiness(2, Set.of(), Map.of(), List.of());
        final var fixed = Map.of(17, List.of(20));
        final var result = CombatBlockSearch.searchWithPressure(snapshot, values(snapshot), new CombatSearchBudget(100), readiness, fixed);
        Assert.assertFalse(result.searchExhaustive());
        Assert.assertTrue(result.outcomeDomainComplete(), result.reasons().toString());
        Assert.assertTrue(result.reasons().stream().anyMatch(reason -> reason.contains("beam")));
        Assert.assertEquals(result.unblocked().orElseThrow().assignment().blockersByAttacker(), fixed);
        final var chosen = result.best().orElseThrow().assignment().blockersByAttacker();
        Assert.assertEquals(chosen.get(17), List.of(20));
        Assert.assertEquals(chosen.values().stream().flatMap(List::stream).filter(id -> id == 20).count(), 1L);
        Assert.assertTrue(chosen.values().stream().flatMap(List::stream).anyMatch(id -> id == 21));
    }

    @Test
    public void bodyAdvantageAddsOnlyABoundedExchangePreferenceAndCannotEraseAnEngine() {
        final PublicCombatSnapshot snapshot = snapshot(2, 20, Map.of(
                10, creature(10, 1, 2, 2), 20, creature(20, 2, 2, 2),
                21, creature(21, 2, 2, 2), 22, creature(22, 2, 2, 2)), Map.of(10, Set.of(20)));
        final var chosen = CombatBlockSearch.search(snapshot, values(snapshot), new CombatSearchBudget(100)).best().orElseThrow();
        Assert.assertEquals(chosen.assignment().blockersByAttacker(), Map.of(10, List.of(20)));
        Assert.assertEquals(chosen.exchangePreference(), 2);
        final Map<Integer, PreparedCombatValuation.PermanentValue> cards = new LinkedHashMap<>(values(snapshot).permanents());
        final var original = cards.get(20);
        cards.put(20, new PreparedCombatValuation.PermanentValue(20, 2, original.bodyLossValue(), 0, -100, List.of()));
        final var engine = new PreparedCombatValuation(cards, List.of(), ValuationCompleteness.PARTIAL, List.of());
        Assert.assertTrue(CombatBlockSearch.search(snapshot, engine, new CombatSearchBudget(100))
                .best().orElseThrow().assignment().blockersByAttacker().isEmpty());
    }

    @Test
    public void overloadedAttacksUseOneBlockerOnceAndPreventRealDamage() {
        final PublicCombatSnapshot snapshot = snapshot(2, 20, Map.of(
                10, creature(10, 1, 2, 2), 11, creature(11, 1, 2, 2), 20, creature(20, 2, 1, 3)),
                Map.of(10, Set.of(20), 11, Set.of(20)));
        final CombatBlockSearch.Result result = CombatBlockSearch.search(snapshot, values(snapshot), new CombatSearchBudget(1000));
        Assert.assertTrue(result.searchExhaustive());
        Assert.assertTrue(result.outcomeDomainComplete());
        final CombatBlockSearch.Candidate chosen = result.best().orElseThrow();
        Assert.assertEquals(chosen.assignment().blockersByAttacker().size(), 1);
        Assert.assertTrue(chosen.projection().lostCreatures().isEmpty());
        Assert.assertEquals(chosen.projection().playerLifeAfter().get(2).intValue(), 18);
        Assert.assertEquals(result.unblocked().orElseThrow().projection().playerLifeAfter().get(2).intValue(), 16);
    }

    @Test
    public void jointAssignmentDoesNotGreedilySpendTheOnlyReachBlocker() {
        final PublicCombatSnapshot snapshot = snapshot(2, 20, Map.of(
                10, creature(10, 1, 3, 3), 11, creature(11, 1, 3, 3),
                20, creature(20, 2, 0, 4), 21, creature(21, 2, 0, 4)),
                Map.of(10, Set.of(20, 21), 11, Set.of(20)));
        final CombatBlockSearch.Result result = CombatBlockSearch.search(snapshot, values(snapshot), new CombatSearchBudget(100));
        Assert.assertEquals(result.best().orElseThrow().assignment().blockersByAttacker(),
                Map.of(10, List.of(21), 11, List.of(20)));
        Assert.assertEquals(result.best().orElseThrow().projection().playerLifeAfter().get(2).intValue(), 20);
        Assert.assertTrue(result.outcomeDomainComplete());
        Assert.assertTrue(result.searchExhaustive());
    }

    @Test
    public void gangBlocksAreScoredJointlyAndRetainTheirDamagePlan() {
        final PublicCombatSnapshot snapshot = snapshot(2, 20, Map.of(
                10, creature(10, 1, 4, 4), 20, creature(20, 2, 3, 3), 21, creature(21, 2, 2, 2)),
                Map.of(10, Set.of(20, 21)));
        final CombatBlockSearch.Result result = CombatBlockSearch.search(snapshot, values(snapshot), new CombatSearchBudget(1000));
        Assert.assertTrue(result.searchExhaustive() && result.outcomeDomainComplete());
        final CombatBlockSearch.Candidate best = result.best().orElseThrow();
        Assert.assertEquals(best.assignment().blockersByAttacker(), Map.of(10, List.of(20, 21)));
        Assert.assertTrue(best.projection().lostCreatures().contains(10));
        Assert.assertTrue(best.damagePlan().isPresent());
        Assert.assertEquals(CombatOutcomePredictor.predict(snapshot, best.assignment(), best.damagePlan().orElseThrow()), best.projection());
    }

    @Test
    public void enginePreservationYieldsToPreventingActualLethal() {
        final Map<Integer, PublicCombatSnapshot.Creature> creatures = Map.of(
                10, creature(10, 1, 2, 2), 20, creature(20, 2, 1, 1));
        final PublicCombatSnapshot healthy = snapshot(2, 20, creatures, Map.of(10, Set.of(20)));
        final PreparedCombatValuation ledger = new PreparedCombatValuation(Map.of(
                10, new PreparedCombatValuation.PermanentValue(10, 1, 100, 0, 0, List.of()),
                20, new PreparedCombatValuation.PermanentValue(20, 2, -100, 0, -30_000, List.of())),
                List.of(), ValuationCompleteness.PARTIAL, List.of());
        Assert.assertTrue(CombatBlockSearch.search(healthy, ledger, new CombatSearchBudget(100))
                .best().orElseThrow().assignment().blockersByAttacker().isEmpty());
        final PublicCombatSnapshot lethal = snapshot(2, 2, creatures, Map.of(10, Set.of(20)));
        final CombatBlockSearch.Candidate saved = CombatBlockSearch.search(lethal, ledger, new CombatSearchBudget(100))
                .best().orElseThrow();
        Assert.assertEquals(saved.assignment().blockersByAttacker(), Map.of(10, List.of(20)));
        Assert.assertEquals(saved.projection().terminal(), CombatProjection.Terminal.NONE);
        Assert.assertTrue(saved.score().total() < -10_000, "Terminal safety must outrank any point subtotal");
    }

    @Test
    public void predictingOpponentBlocksMinimizesTheObserversUtility() {
        final Map<Integer, PublicCombatSnapshot.Creature> creatures = Map.of(
                10, creature(10, 1, 2, 2), 20, creature(20, 2, 0, 3));
        final PublicCombatSnapshot ownBlocks = snapshot(2, 20, creatures, Map.of(10, Set.of(20)));
        final PublicCombatSnapshot enemyBlocks = snapshot(1, 20, creatures, Map.of(10, Set.of(20)));
        final CombatBlockSearch.Candidate defending = CombatBlockSearch.search(ownBlocks, values(ownBlocks), new CombatSearchBudget(100))
                .best().orElseThrow();
        final CombatBlockSearch.Candidate predicted = CombatBlockSearch.search(enemyBlocks, values(enemyBlocks), new CombatSearchBudget(100))
                .best().orElseThrow();
        Assert.assertEquals(predicted.assignment(), defending.assignment());
        Assert.assertEquals(predicted.score().total(), -defending.score().total());
    }

    @Test
    public void completingTheLastNodeDoesNotFalselyReportIncompleteSearch() {
        final PublicCombatSnapshot snapshot = snapshot(2, 20, Map.of(
                10, creature(10, 1, 2, 2), 20, creature(20, 2, 0, 3)), Map.of(10, Set.of(20)));
        final CombatBlockSearch.Result result = CombatBlockSearch.search(snapshot, values(snapshot), new CombatSearchBudget(3));
        Assert.assertTrue(result.searchExhaustive());
        Assert.assertEquals(result.nodes(), 3);
        Assert.assertEquals(result.best().orElseThrow().assignment().blockersByAttacker(), Map.of(10, List.of(20)));
    }

    @Test
    public void sharedBudgetBoundsApproximateSearchAndExhaustionIsNotUnsupported() {
        final Map<Integer, PublicCombatSnapshot.Creature> creatures = new LinkedHashMap<>();
        final Map<Integer, Set<Integer>> blocks = new LinkedHashMap<>();
        for (int id = 10; id < 18; id++) { creatures.put(id, creature(id, 1, 2, 2)); blocks.put(id, Set.of(20, 21)); }
        creatures.put(20, creature(20, 2, 0, 3));
        creatures.put(21, creature(21, 2, 0, 3));
        final PublicCombatSnapshot snapshot = snapshot(2, 20, creatures, blocks);
        final CombatSearchBudget budget = new CombatSearchBudget(25);
        Assert.assertTrue(budget.tryConsume());
        final CombatBlockSearch.Result result = CombatBlockSearch.search(snapshot, values(snapshot), budget, 2);
        Assert.assertFalse(result.searchExhaustive());
        Assert.assertEquals(result.nodes(), 24);
        Assert.assertEquals(budget.used(), 25);
        Assert.assertTrue(result.best().orElseThrow().projection().available());
        final CombatBlockSearch.Result exhausted = CombatBlockSearch.search(snapshot, values(snapshot), budget);
        Assert.assertTrue(exhausted.best().isEmpty());
        Assert.assertEquals(exhausted.nodes(), 0);
        Assert.assertFalse(exhausted.searchExhaustive());
    }

    @Test
    public void repeatAndReorderedInputsGiveIdenticalTiesAndNodeCounts() {
        final PublicCombatSnapshot first = snapshot(2, 20, Map.of(
                10, creature(10, 1, 2, 2), 11, creature(11, 1, 2, 2), 20, creature(20, 2, 0, 3)),
                Map.of(10, Set.of(20), 11, Set.of(20)));
        final Map<Integer, PublicCombatSnapshot.Creature> reverse = new LinkedHashMap<>();
        first.creatures().keySet().stream().sorted(java.util.Comparator.reverseOrder())
                .forEach(id -> reverse.put(id, first.creatures().get(id)));
        final PublicCombatSnapshot second = snapshot(2, 20, reverse, Map.of(11, Set.of(20), 10, Set.of(20)));
        final CombatBlockSearch.Result a = CombatBlockSearch.search(first, values(first), new CombatSearchBudget(100));
        final CombatBlockSearch.Result b = CombatBlockSearch.search(second, values(second), new CombatSearchBudget(100));
        Assert.assertEquals(a, b);
        Assert.assertEquals(a, CombatBlockSearch.search(first, values(first), new CombatSearchBudget(100)));
    }

    @Test
    public void unknownMechanicsDoNotBecomeZeroValueCandidates() {
        final PublicCombatSnapshot original = snapshot(2, 20, Map.of(10, creature(10, 1, 2, 2)), Map.of(10, Set.of()));
        final PublicCombatSnapshot unknown = new PublicCombatSnapshot(2, 1, 2, original.creatures(), original.players(),
                original.attackersToDefenders(), original.legalBlockers(), List.of(), List.of("Unprojected prevention"), false);
        final CombatBlockSearch.Result result = CombatBlockSearch.search(unknown, values(unknown), new CombatSearchBudget(100));
        Assert.assertTrue(result.best().isEmpty());
        Assert.assertFalse(result.outcomeDomainComplete());
        Assert.assertTrue(result.reasons().contains("Unprojected prevention"));
    }

    @Test
    public void exhaustiveSearchMatchesIndependentSmallBoardEnumeration() {
        for (int power = 1; power <= 5; power++) {
            final PublicCombatSnapshot snapshot = snapshot(2, 5, Map.of(
                    10, creature(10, 1, power, 3), 11, creature(11, 1, 2, 2),
                    20, creature(20, 2, 2, 2), 21, creature(21, 2, 1, 4)),
                    Map.of(10, Set.of(20, 21), 11, Set.of(20, 21)));
            final PreparedCombatValuation ledger = values(snapshot);
            final List<CombatTransitionValueEvaluator.Score> all = new ArrayList<>();
            final List<Integer> chumpAdjustments = new ArrayList<>();
            final var chumps = new CombatChumpValueEvaluator(snapshot, new CombatSearchBudget(1000));
            for (final int first : List.of(-1, 20, 21)) {
                for (final int second : List.of(-1, 20, 21)) {
                    if (first >= 0 && first == second) { continue; }
                    final Map<Integer, List<Integer>> blocks = new LinkedHashMap<>();
                    if (first >= 0) { blocks.put(10, List.of(first)); }
                    if (second >= 0) { blocks.put(11, List.of(second)); }
                    final var assignment = new CombatAssignment(snapshot.attackersToDefenders(), blocks);
                    final CombatProjection projection = CombatOutcomePredictor.predict(snapshot, assignment);
                    all.add(CombatTransitionValueEvaluator.evaluate(snapshot, ledger, projection));
                    final var adjustment = chumps.evaluate(assignment, projection, Map.of());
                    Assert.assertTrue(adjustment.complete());
                    chumpAdjustments.add(adjustment.adjustment());
                }
            }
            final CombatBlockSearch.Result result = CombatBlockSearch.search(snapshot, ledger, new CombatSearchBudget(10000));
            Assert.assertTrue(result.searchExhaustive());
            final CombatTransitionValueEvaluator.Score chosen = result.best().orElseThrow().score();
            for (int index = 0; index < all.size(); index++) {
                final var alternative = all.get(index);
                if (chosen.terminal() == alternative.terminal()) {
                    Assert.assertTrue(result.best().orElseThrow().adjustedTotal()
                            >= CombatOutcomePredictor.add(alternative.total(), chumpAdjustments.get(index)));
                } else {
                    Assert.assertEquals(chosen.terminal(), CombatProjection.Terminal.NONE);
                    Assert.assertEquals(alternative.terminal(), CombatProjection.Terminal.LOSS);
                }
            }
        }
    }

    private static PublicCombatSnapshot.Creature creature(final int id, final int controller, final int power, final int toughness) {
        return new PublicCombatSnapshot.Creature(id, controller, power, toughness, 0,
                false, false, false, false, false, false, false, false, false);
    }

    private static PublicCombatSnapshot snapshot(final int observer, final int defendingLife,
            final Map<Integer, PublicCombatSnapshot.Creature> creatures, final Map<Integer, Set<Integer>> blocks) {
        final Map<Integer, Integer> attacks = new LinkedHashMap<>();
        blocks.keySet().forEach(id -> attacks.put(id, 2));
        return new PublicCombatSnapshot(observer, 1, 2, creatures, Map.of(
                1, new PublicCombatSnapshot.LifeState(20, true, true, false, false),
                2, new PublicCombatSnapshot.LifeState(defendingLife, true, true, false, false)),
                attacks, blocks, List.of(), List.of(), false);
    }

    private static PreparedCombatValuation values(final PublicCombatSnapshot snapshot) {
        final Map<Integer, PreparedCombatValuation.PermanentValue> values = new LinkedHashMap<>();
        snapshot.creatures().forEach((id, card) -> values.put(id, new PreparedCombatValuation.PermanentValue(
                id, card.controllerId(), card.controllerId() == snapshot.observingPlayerId() ? -100 : 100,
                0, 0, List.of())));
        return new PreparedCombatValuation(values, List.of(), ValuationCompleteness.PARTIAL, List.of());
    }
}
