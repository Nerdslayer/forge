package forge.ai.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.effect.ValuationCompleteness;

public class CombatAttackSearchTest {
    @Test
    public void opponentMayForgoAnImmediateTradeToOpenALethalCounterattack() {
        final var all = snapshot(2, 20, Map.of(10, creature(10, 1, 2, 2), 11, creature(11, 1, 0, 3),
                20, creature(20, 2, 2, 2), 21, creature(21, 2, 2, 2)));
        final var attack = CombatAttackCandidates.select(all, List.of(10));
        final Map<Integer, PreparedCombatValuation.PermanentValue> cards = new LinkedHashMap<>();
        all.creatures().forEach((id, card) -> cards.put(id, new PreparedCombatValuation.PermanentValue(id, card.controllerId(),
                card.controllerId() == 1 ? -100 : 100, 0, 0, List.of())));
        final var values = new PreparedCombatValuation(cards, List.of(), ValuationCompleteness.PARTIAL, List.of());
        final var ready = new PublicCombatReadiness(2, Set.of(20, 21), Map.of(20, Set.of(10, 11), 21, Set.of(10, 11)), List.of());
        final var immediate = CombatBlockSearch.search(attack, values, new CombatSearchBudget(10000));
        Assert.assertFalse(immediate.best().orElseThrow().assignment().blockersByAttacker().isEmpty());
        final CombatSearchBudget budget = new CombatSearchBudget(10000);
        final var response = CombatBlockSearch.searchWithReplySafety(attack, values, budget,
                state -> CombatSafetyEvaluator.nextAttack(attack, state, ready, values, budget));
        Assert.assertTrue(response.outcomeDomainComplete() && response.searchExhaustive(), response.reasons().toString());
        Assert.assertTrue(response.best().orElseThrow().assignment().blockersByAttacker().isEmpty());
        Assert.assertTrue(response.best().orElseThrow().followUp().orElseThrow().lethalOpportunity());
        final var selection = CombatAttackSearch.search(attack, values, ready, new CombatSearchBudget(20000));
        Assert.assertTrue(selection.outcomeDomainComplete(), selection.reasons().toString());
        Assert.assertTrue(selection.best().orElseThrow().attackers().isEmpty());
    }

    @Test
    public void overloadedTwoTwoAttackGetsDamagePastOneThreeWithoutLosingCreatures() {
        final var snapshot = snapshot(20, 20, Map.of(10, creature(10, 1, 2, 2), 11, creature(11, 1, 2, 2),
                20, creature(20, 2, 1, 3)));
        final var result = search(snapshot, new CombatSearchBudget(10000));
        Assert.assertTrue(result.outcomeDomainComplete() && result.searchExhaustive(), result.reasons().toString());
        Assert.assertEquals(result.best().orElseThrow().attackers(), List.of(10, 11));
        Assert.assertEquals(result.best().orElseThrow().combat().projection().playerLifeAfter().get(2).intValue(), 18);
        Assert.assertTrue(result.best().orElseThrow().combat().projection().lostCreatures().isEmpty());
    }

    @Test
    public void actualLethalOutranksLosingValuableAttackersAndCannotHaveACounterattack() {
        final var snapshot = snapshot(2, 2, Map.of(10, creature(10, 1, 2, 2), 11, creature(11, 1, 2, 2),
                20, creature(20, 2, 4, 4)));
        final var result = search(snapshot, new CombatSearchBudget(10000));
        Assert.assertTrue(result.outcomeDomainComplete(), result.reasons().toString());
        final var best = result.best().orElseThrow();
        Assert.assertEquals(best.attackers(), List.of(10, 11));
        Assert.assertEquals(best.combat().projection().terminal(), CombatProjection.Terminal.WIN);
        Assert.assertTrue(best.reply().isEmpty());
    }

    @Test
    public void approximateLethalRemainsSelectableButIsNotCertified() {
        final var exact = snapshot(20, 2, Map.of(10, creature(10, 1, 2, 2)));
        final var approximate = new PublicCombatSnapshot(exact.observingPlayerId(), exact.attackingPlayerId(), exact.defendingPlayerId(),
                exact.creatures(), exact.players(), exact.attackersToDefenders(), exact.legalBlockers(), exact.unavailableReasons(),
                exact.unsupportedReasons(), exact.legacyDamageOrder(), exact.resources(), exact.triggers(), exact.observedAttackers(),
                exact.observedBlockers(), exact.preventionRules(), exact.staticWorlds(), List.of("Unprojected public activation: 10"));
        final var result = search(approximate, new CombatSearchBudget(10000));
        Assert.assertTrue(result.outcomeDomainComplete(), result.reasons().toString());
        final var best = result.best().orElseThrow();
        Assert.assertEquals(best.attackers(), List.of(10));
        Assert.assertEquals(best.combat().projection().terminal(), CombatProjection.Terminal.WIN);
        Assert.assertFalse(best.certifiedWin());
        Assert.assertFalse(best.combat().projection().reasons().isEmpty());
    }

    @Test
    public void holdBackDefensiveAnchorWhenTappingItOpensLethalReply() {
        final var snapshot = snapshot(4, 20, Map.of(10, creature(10, 1, 5, 5),
                20, creature(20, 2, 2, 2), 21, creature(21, 2, 2, 2)));
        final var result = search(snapshot, new CombatSearchBudget(10000));
        Assert.assertTrue(result.outcomeDomainComplete(), result.reasons().toString());
        Assert.assertTrue(result.best().orElseThrow().attackers().isEmpty());
        Assert.assertFalse(result.best().orElseThrow().reply().orElseThrow().lethalOpportunity());
    }

    @Test
    public void insufficientReplyBudgetIsExplicitNotAnOptimisticSafeAttack() {
        final var snapshot = snapshot(4, 20, Map.of(10, creature(10, 1, 5, 5),
                20, creature(20, 2, 2, 2), 21, creature(21, 2, 2, 2)));
        final var result = search(snapshot, new CombatSearchBudget(2));
        Assert.assertFalse(result.outcomeDomainComplete());
        Assert.assertFalse(result.searchExhaustive());
        Assert.assertFalse(result.reasons().isEmpty());
    }

    @Test
    public void interchangeableSingletonsAreReusedButDistinctLossOrBlockingRolesAreNot() {
        final var before = snapshot(20, 20, Map.of(10, creature(10, 1, 2, 2), 11, creature(11, 1, 2, 2),
                12, creature(12, 1, 2, 2), 20, creature(20, 2, 1, 3)));
        final Map<Integer, PreparedCombatValuation.PermanentValue> cards = new LinkedHashMap<>();
        before.creatures().forEach((id, card) -> cards.put(id, new PreparedCombatValuation.PermanentValue(id, card.controllerId(),
                card.controllerId() == 1 ? -100 : 100, 0, id == 12 ? -20 : 0, List.of())));
        final var ledger = new PreparedCombatValuation(cards, List.of(), ValuationCompleteness.PARTIAL, List.of());
        final var ready = new PublicCombatReadiness(2, Set.of(20), Map.of(20, Set.of(10, 11, 12)), List.of());
        final var representatives = CombatAttackEquivalence.representatives(before, ledger, ready, List.of(10, 11, 12));
        Assert.assertEquals(representatives.get(10), representatives.get(11));
        Assert.assertNotEquals(representatives.get(10), representatives.get(12));
        final var distinctBlocks = new PublicCombatReadiness(2, Set.of(20), Map.of(20, Set.of(10, 12)), List.of());
        final var distinct = CombatAttackEquivalence.representatives(before, ledger, distinctBlocks, List.of(10, 11));
        Assert.assertNotEquals(distinct.get(10), distinct.get(11));
        final var result = CombatAttackSearch.search(before, ledger, ready, new CombatSearchBudget(100000));
        Assert.assertTrue(result.candidateSearchComplete(), result.reasons().toString());
        Assert.assertFalse(result.searchExhaustive());
        Assert.assertTrue(result.declarationsEvaluated() < 8, "Do not enumerate all three-attacker subsets");
        Assert.assertEquals(result.best().orElseThrow().attackers(), List.of(10, 11, 12));
    }

    @Test
    public void individuallyLethalAttackersForceChumpsAndFreeOtherwiseUnprofitableAttackers() {
        final var board = lethalOverloadBoard(false);
        assertSmallAttackersLoseAlone(board);
        final var result = search(board, new CombatSearchBudget(2000000), true);
        Assert.assertTrue(result.candidateSearchComplete(), result.reasons().toString());
        final var best = result.best().orElseThrow();
        Assert.assertEquals(best.attackers(), List.of(10, 11, 12, 13, 14));
        Assert.assertEquals(best.combat().projection().playerLifeAfter().get(2).intValue(), 3);
        Assert.assertEquals(best.combat().projection().lostCreatures(), Set.of(20, 21, 22));
    }

    @Test
    public void spareBlockerKeepsOtherwiseUnprofitableAttackersHomeDespiteForcedChumps() {
        final var board = lethalOverloadBoard(true);
        assertSmallAttackersLoseAlone(board);
        final var result = search(board, new CombatSearchBudget(2000000), true);
        Assert.assertTrue(result.candidateSearchComplete(), result.reasons().toString());
        final var best = result.best().orElseThrow();
        Assert.assertEquals(best.attackers(), List.of(10, 11, 12));
        Assert.assertEquals(best.combat().projection().playerLifeAfter().get(2).intValue(), 5);
        Assert.assertEquals(best.combat().projection().lostCreatures().size(), 3);
        Assert.assertTrue(best.combat().projection().lostCreatures().stream().allMatch(id -> id >= 20));
    }

    private static PublicCombatSnapshot lethalOverloadBoard(final boolean spareBlocker) {
        // Blockers have defender: isolate forced chumps without unrelated counterattack choices.
        final Map<Integer, PublicCombatSnapshot.Creature> cards = new LinkedHashMap<>();
        for (int id = 10; id <= 12; id++) { cards.put(id, creature(id, 1, 5, 5)); }
        for (int id = 13; id <= 14; id++) { cards.put(id, creature(id, 1, 1, 1)); }
        for (int id = 20; id <= (spareBlocker ? 23 : 22); id++) { cards.put(id, creature(id, 2, 2, 3)); }
        return snapshot(40, 5, cards);
    }

    private static void assertSmallAttackersLoseAlone(final PublicCombatSnapshot board) {
        for (final int id : List.of(13, 14)) {
            final var alone = search(CombatAttackCandidates.select(board, List.of(id)), new CombatSearchBudget(2000000), true);
            Assert.assertTrue(alone.candidateSearchComplete(), alone.reasons().toString());
            Assert.assertTrue(alone.best().orElseThrow().attackers().isEmpty());
        }
    }

    private static CombatAttackSearch.Result search(final PublicCombatSnapshot snapshot, final CombatSearchBudget budget) {
        return search(snapshot, budget, false);
    }

    private static CombatAttackSearch.Result search(final PublicCombatSnapshot snapshot, final CombatSearchBudget budget,
            final boolean defenderOnlyBlockers) {
        final Map<Integer, PreparedCombatValuation.PermanentValue> values = new LinkedHashMap<>();
        final Map<Integer, Set<Integer>> replies = new LinkedHashMap<>();
        final Set<Integer> own = snapshot.creatures().keySet().stream().filter(id -> snapshot.creatures().get(id).controllerId() == 1)
                .collect(java.util.stream.Collectors.toSet());
        final Set<Integer> enemies = snapshot.creatures().keySet().stream().filter(id -> snapshot.creatures().get(id).controllerId() == 2)
                .collect(java.util.stream.Collectors.toSet());
        snapshot.creatures().forEach((id, card) -> {
            values.put(id, new PreparedCombatValuation.PermanentValue(id, card.controllerId(), card.controllerId() == 1 ? -100 : 100,
                    0, 0, List.of()));
            if (card.controllerId() == 2) { replies.put(id, own); }
        });
        return CombatAttackSearch.search(snapshot, new PreparedCombatValuation(values, List.of(), ValuationCompleteness.PARTIAL, List.of()),
                new PublicCombatReadiness(2, defenderOnlyBlockers ? Set.of() : enemies, replies, List.of()), budget);
    }

    private static PublicCombatSnapshot snapshot(final int ownLife, final int opposingLife,
            final Map<Integer, PublicCombatSnapshot.Creature> creatures) {
        final Map<Integer, Integer> attacks = new LinkedHashMap<>();
        final Map<Integer, Set<Integer>> blocks = new LinkedHashMap<>();
        final Set<Integer> opponents = creatures.keySet().stream().filter(id -> creatures.get(id).controllerId() == 2)
                .collect(java.util.stream.Collectors.toSet());
        creatures.forEach((id, card) -> { if (card.controllerId() == 1) { attacks.put(id, 2); blocks.put(id, opponents); } });
        return new PublicCombatSnapshot(1, 1, 2, creatures, Map.of(
                1, new PublicCombatSnapshot.LifeState(ownLife, true, true, false, false),
                2, new PublicCombatSnapshot.LifeState(opposingLife, true, true, false, false)), attacks, blocks,
                List.of(), List.of(), false);
    }

    private static PublicCombatSnapshot.Creature creature(final int id, final int controller, final int power, final int toughness) {
        return new PublicCombatSnapshot.Creature(id, controller, power, toughness, 0, false, false, false,
                false, false, false, false, false, false);
    }
}
