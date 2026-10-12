package forge.ai.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

import org.testng.Assert;
import org.testng.annotations.Test;
import org.mockito.Mockito;

import forge.ai.effect.ValuationCompleteness;

/** General cache parity, ordered allocations, equivalence boundaries and cooperative limits. */
public class CombatSearchReuseTest {
    @Test
    public void disabledOrInsufficientReusePreservesIdentityRepresentatives() {
        final var board = board(1, false, false);
        final var ledger = values(board);
        final var ready = new PublicCombatReadiness(2, Set.of(20, 21), Map.of(20, Set.of(10, 11, 12)), List.of());
        final var disabled = new PublicCombatReadiness(ready.nextActivePlayerId(), ready.canAttackNextTurn(),
                ready.blockPairsNextTurn(), ready.unsupportedReasons(), ready.ignoredEffects(), false);
        final var untouched = Mockito.mock(PreparedCombatValuation.class);
        Assert.assertEquals(CombatAttackEquivalence.representatives(board, untouched, ready, List.of()), Map.of());
        Assert.assertEquals(CombatAttackEquivalence.representatives(board, untouched, ready, List.of(10)), Map.of(10, 10));
        Assert.assertEquals(CombatAttackEquivalence.representatives(board, untouched, disabled, List.of(10, 11)), Map.of(10, 10, 11, 11));
        Mockito.verifyNoInteractions(untouched);
        for (final var candidates : List.of(List.<Integer>of(), List.of(10), List.of(10, 10), List.of(10, 11, 12))) {
            final Map<Integer, Integer> expected = new LinkedHashMap<>();
            candidates.forEach(id -> expected.put(id, id));
            Assert.assertEquals(CombatAttackEquivalence.representatives(board, ledger, disabled, candidates), expected);
            if (expected.size() < 2) {
                Assert.assertEquals(CombatAttackEquivalence.representatives(board, ledger, ready, candidates), expected);
            }
        }
        Assert.assertEquals(CombatAttackEquivalence.representatives(board, ledger, ready, List.of(10, 11, 12),
                Map.of(10, 2, 11, 2), Map.of(12, Set.of(2)), 2, true), Map.of(10, 10, 11, 11, 12, 12));
    }

    @Test
    public void exactDuplicatesReuseCompleteWorkWithIdenticalOrderedCombatResults() {
        for (final boolean legacy : List.of(false, true)) {
            for (final boolean strike : List.of(false, true)) {
                final var board = board(2, legacy, strike);
                final var ledger = values(board);
                final var fresh = CombatBlockSearch.search(board, ledger, budget(100000), 16, false);
                final var cached = CombatBlockSearch.search(board, ledger, budget(100000), 16, true);
                Assert.assertTrue(cached.searchExhaustive() && cached.outcomeDomainComplete());
                Assert.assertEquals(cached.best(), fresh.best());
                Assert.assertEquals(cached.unblocked(), fresh.unblocked());
                Assert.assertTrue(cached.cacheHits() > 0);
                Assert.assertTrue(cached.evaluations() < fresh.evaluations());
                Assert.assertTrue(cached.nodes() < fresh.nodes(), "Gang allocation work should execute only on misses");
                final var best = cached.best().orElseThrow();
                Assert.assertEquals(CombatOutcomePredictor.predict(board, best.assignment(), best.damagePlan().orElse(null)), best.projection());
            }
        }
    }

    @Test
    public void baselinePressureAndFixedGroupsRemainIdenticalToFreshEvaluation() {
        final var board = board(2, false, false);
        final var readiness = new PublicCombatReadiness(2, Set.of(20, 21), Map.of(20, Set.of(10, 11, 12), 21, Set.of(10, 11, 12)), List.of());
        for (final var fixed : List.of(Map.<Integer, List<Integer>>of(), Map.of(10, List.of(20)))) {
            final var fresh = CombatBlockSearch.searchWithPressure(board, values(board), budget(100000), readiness, fixed, false);
            final var cached = CombatBlockSearch.searchWithPressure(board, values(board), budget(100000), readiness, fixed, true);
            Assert.assertTrue(cached.searchExhaustive() && cached.outcomeDomainComplete(), cached.reasons().toString());
            Assert.assertEquals(cached.best(), fresh.best());
            Assert.assertEquals(cached.unblocked(), fresh.unblocked());
            Assert.assertEquals(cached.unblocked().orElseThrow().pressureValue(), 0);
        }
    }

    @Test
    public void cachingDoesNotEraseLowBudgetOrTimeExhaustion() {
        final var board = board(2, true, true);
        final var limited = CombatBlockSearch.search(board, values(board), budget(2));
        Assert.assertFalse(limited.searchExhaustive());
        Assert.assertTrue(limited.nodes() <= 2);
        final AtomicLong clock = new AtomicLong();
        final var expired = new CombatSearchBudget(1000, 100, clock::get);
        clock.set(100000000);
        final var result = CombatBlockSearch.search(board, values(board), expired);
        Assert.assertTrue(result.best().isEmpty());
        Assert.assertEquals(result.nodes(), 0);
        Assert.assertFalse(result.searchExhaustive());
    }

    @Test
    public void routeRepresentativesExcludeCommittedAndDifferentDestinationsOrResilienceRoles() {
        final var board = board(1, false, false);
        final var ledger = values(board);
        final var ready = new PublicCombatReadiness(2, Set.of(10, 11, 12, 20, 21),
                Map.of(20, Set.of(10, 11, 12), 21, Set.of(10, 11, 12)), List.of());
        final var legal = Map.of(10, Set.of(2, ~100), 11, Set.of(2, ~100), 12, Set.of(2));
        final var representatives = CombatAttackEquivalence.representatives(board, ledger, ready, List.of(10, 11, 12),
                Map.of(), legal, ~100, false);
        Assert.assertEquals(representatives.get(10), representatives.get(11));
        Assert.assertNotEquals(representatives.get(10), representatives.get(12));
        final var committed = CombatAttackEquivalence.representatives(board, ledger, ready, List.of(11),
                Map.of(10, ~100), legal, ~100, false);
        Assert.assertEquals(committed.get(11).intValue(), 11);
        final var tied = CombatAttackEquivalence.representatives(board, ledger, ready, List.of(10, 11),
                Map.of(), legal, ~100, true);
        Assert.assertNotEquals(tied.get(10), tied.get(11), "Different destination roles in a resilience power tie cannot be swapped");
        final Map<Integer, PublicCombatSnapshot.Creature> mixedCards = new LinkedHashMap<>(board.creatures());
        mixedCards.put(12, new PublicCombatSnapshot.Creature(12, 1, 4, 4, 0, false, false, false,
                false, false, true, false, false, false));
        final var mixed = new PublicCombatSnapshot(1, 1, 2, mixedCards, board.players(), board.attackersToDefenders(),
                board.legalBlockers(), List.of(), List.of(), false);
        final var sharedLegal = Map.of(10, Set.of(2), 11, Set.of(2), 12, Set.of(2));
        final var mixedRepresentatives = CombatAttackEquivalence.representatives(mixed, values(mixed), ready, List.of(10, 11),
                Map.of(), sharedLegal, 2, true);
        Assert.assertNotEquals(mixedRepresentatives.get(10), mixedRepresentatives.get(11),
                "Equal-valued reply casualties may interact with ID ties even without equal-power resilience ties");
        final var observed = new PublicCombatSnapshot(board.observingPlayerId(), board.attackingPlayerId(), board.defendingPlayerId(),
                board.creatures(), board.players(), board.attackersToDefenders(), board.legalBlockers(), List.of(), List.of(), false,
                Map.of(), List.of(), Set.of(10), Set.of(), List.of(), PreparedCombatStaticWorlds.empty(), List.of());
        Assert.assertNotEquals(CombatAttackEquivalence.representatives(observed, ledger, ready, List.of(10, 11)).get(10),
                CombatAttackEquivalence.representatives(observed, ledger, ready, List.of(10, 11)).get(11));
    }

    private static CombatSearchBudget budget(final int nodes) {
        return new CombatSearchBudget(nodes, 30000, () -> 0L);
    }

    @Test
    public void representativeSingletonsHaveTheSameFullRankingsButKeepActualIdentities() {
        final var board = board(1, false, false);
        final var ledger = values(board);
        final var ready = new PublicCombatReadiness(2, Set.of(10, 11, 12, 20, 21),
                Map.of(10, Set.of(20, 21), 11, Set.of(20, 21), 12, Set.of(20, 21),
                        20, Set.of(10, 11, 12), 21, Set.of(10, 11, 12)), List.of());
        final var representative = CombatAttackEquivalence.representatives(board, ledger, ready, List.of(10, 11, 12));
        Assert.assertEquals(Set.copyOf(representative.values()), Set.of(10));
        CombatAttackSearch.Candidate first = null;
        for (final int id : List.of(10, 11, 12)) {
            final var search = CombatAttackSearch.search(CombatAttackCandidates.select(board, List.of(id)), ledger, ready, budget(100000));
            Assert.assertTrue(search.candidateSearchComplete(), search.reasons().toString());
            final var best = search.best().orElseThrow();
            Assert.assertEquals(best.attackers(), List.of(id));
            if (first == null) { first = best; }
            else { Assert.assertEquals(CombatAttackSearch.compare(first, best), 0); }
        }
    }

    @Test
    public void ordinaryFutureReuseMatchesIdentitySpecificSearchAndSavesWork() {
        final var board = board(1, false, false);
        final var hold = CombatAttackCandidates.select(board, List.of());
        final var state = CombatOutcomePredictor.predict(hold, new CombatAssignment(Map.of(), Map.of()));
        final var ready = new PublicCombatReadiness(2, Set.of(20, 21), Map.of(20, Set.of(10, 11, 12), 21, Set.of(10, 11, 12)), List.of());
        final var freshReady = new PublicCombatReadiness(ready.nextActivePlayerId(), ready.canAttackNextTurn(),
                ready.blockPairsNextTurn(), ready.unsupportedReasons(), ready.ignoredEffects(), false);
        final var cachedBudget = budget(100000);
        final var freshBudget = budget(100000);
        final var cached = CombatSafetyEvaluator.forecastNextAttack(board, state, ready, values(board), cachedBudget);
        final var fresh = CombatSafetyEvaluator.forecastNextAttack(board, state, freshReady, values(board), freshBudget);
        Assert.assertTrue(cached.evaluation().usable() && fresh.evaluation().usable());
        Assert.assertEquals(cached.evaluation().nonterminalUtility(), fresh.evaluation().nonterminalUtility());
        Assert.assertEquals(cached.evaluation().lethalOpportunity(), fresh.evaluation().lethalOpportunity());
        Assert.assertEquals(cached.evaluation().attackers(), fresh.evaluation().attackers());
        Assert.assertEquals(cached.continuation().orElseThrow().projection(), fresh.continuation().orElseThrow().projection());
        Assert.assertTrue(cachedBudget.used() < freshBudget.used());
    }

    private static PreparedCombatValuation values(final PublicCombatSnapshot snapshot) {
        final Map<Integer, PreparedCombatValuation.PermanentValue> values = new LinkedHashMap<>();
        snapshot.creatures().forEach((id, card) -> values.put(id, new PreparedCombatValuation.PermanentValue(id, card.controllerId(),
                card.controllerId() == snapshot.observingPlayerId() ? -100 : 100, 0, 0, List.of())));
        return new PreparedCombatValuation(values, List.of(), ValuationCompleteness.PARTIAL, List.of());
    }

    private static PublicCombatSnapshot board(final int observer, final boolean legacy, final boolean strike) {
        final Map<Integer, PublicCombatSnapshot.Creature> cards = new LinkedHashMap<>();
        for (int id = 10; id <= 12; id++) {
            cards.put(id, new PublicCombatSnapshot.Creature(id, 1, 3, 3, 0, false, strike, false,
                    false, false, true, false, false, false));
        }
        cards.put(20, new PublicCombatSnapshot.Creature(20, 2, 1, 2, 0, false, false, false,
                false, false, false, false, false, false));
        cards.put(21, new PublicCombatSnapshot.Creature(21, 2, 1, 2, 0, false, false, false,
                false, false, false, false, false, false));
        return new PublicCombatSnapshot(observer, 1, 2, cards, Map.of(
                1, new PublicCombatSnapshot.LifeState(20, true, true, false, false),
                2, new PublicCombatSnapshot.LifeState(20, true, true, false, false)),
                Map.of(10, 2, 11, 2, 12, 2), Map.of(10, Set.of(20, 21), 11, Set.of(20, 21), 12, Set.of(20, 21)),
                List.of(), List.of(), legacy);
    }
}
