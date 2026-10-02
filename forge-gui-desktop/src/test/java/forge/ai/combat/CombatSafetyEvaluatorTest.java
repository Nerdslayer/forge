package forge.ai.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.effect.ValuationCompleteness;

public class CombatSafetyEvaluatorTest {
    @Test
    public void opposingAllOutReplyUsesOnlyPublicSurvivorsAndMinimizesObserverUtility() {
        final PublicCombatSnapshot original = fiveCreatures(false, 20);
        final PublicCombatSnapshot ownAttack = new PublicCombatSnapshot(1, 1, 2, original.creatures(), original.players(),
                original.attackersToDefenders(), original.legalBlockers(), List.of(), List.of(), false);
        final Map<Integer, PreparedCombatValuation.PermanentValue> cards = new LinkedHashMap<>();
        ownAttack.creatures().forEach((id, card) -> cards.put(id, new PreparedCombatValuation.PermanentValue(id, card.controllerId(),
                card.controllerId() == 1 ? -100 : 100, 0, 0, List.of())));
        final var ledger = new PreparedCombatValuation(cards, List.of(), ValuationCompleteness.PARTIAL, List.of());
        final var current = CombatOutcomePredictor.predict(ownAttack, new CombatAssignment(ownAttack.attackersToDefenders(), Map.of()));
        final var reply = CombatSafetyEvaluator.nextAttack(ownAttack, current, readiness(ownAttack), ledger, new CombatSearchBudget(20000));
        Assert.assertTrue(reply.supported() && reply.searchExhaustive());
        Assert.assertTrue(reply.lethalOpportunity(), "Five public 2/2s can kill the attacking observer on the reply");
        Assert.assertEquals(reply.attackers().size(), 5);
        Assert.assertTrue(reply.nonterminalUtility() < 0);
        Assert.assertTrue(reply.nonterminalUtility() > -10000, "Forecast risk is not an actual game loss");
    }

    @Test
    public void pressureRankingPreservesLethalAttackersButNeverAcceptsCurrentLethalDamage() {
        final PublicCombatSnapshot snapshot = fiveCreatures(false, 20);
        final var search = CombatBlockSearch.searchWithPressure(snapshot, values(snapshot, List.of()),
                new CombatSearchBudget(200000), readiness(snapshot));
        Assert.assertTrue(search.outcomeDomainComplete(), search.reasons().toString());
        Assert.assertTrue(search.searchExhaustive(), "All gangs are enumerated on a five-blocker small domain");
        Assert.assertTrue(search.best().orElseThrow().assignment().blockersByAttacker().isEmpty());
        Assert.assertTrue(search.best().orElseThrow().followUp().orElseThrow().lethalOpportunity());
        final PublicCombatSnapshot lethal = fiveCreatures(false, 2);
        final var mustBlock = CombatBlockSearch.searchWithPressure(lethal, values(lethal, List.of()),
                new CombatSearchBudget(200000), readiness(lethal));
        Assert.assertTrue(mustBlock.outcomeDomainComplete());
        Assert.assertFalse(mustBlock.best().orElseThrow().assignment().blockersByAttacker().isEmpty());
        Assert.assertEquals(mustBlock.best().orElseThrow().projection().terminal(), CombatProjection.Terminal.NONE);
    }

    @Test
    public void pressureDiscountsOrdinaryValueButPreservesLethalAndIncompleteForecastFallback() {
        final var baseline = new CombatSafetyEvaluator.FollowUp(true, true, 0, false, List.of(), List.of());
        final var better = new CombatSafetyEvaluator.FollowUp(true, true, 10000, true, List.of(), List.of());
        Assert.assertEquals(CombatSafetyEvaluator.incrementalPressure(baseline, better), 5500);
        Assert.assertEquals(CombatSafetyEvaluator.incrementalPressure(better, baseline), -5500);
        final var ordinary = new CombatSafetyEvaluator.FollowUp(true, true, 100, false, List.of(), List.of());
        Assert.assertEquals(CombatSafetyEvaluator.incrementalPressure(baseline, ordinary), 50);
        Assert.assertEquals(CombatSafetyEvaluator.incrementalPressure(ordinary, baseline), -50);
        final var lethalOnly = new CombatSafetyEvaluator.FollowUp(true, true, 0, true, List.of(), List.of());
        Assert.assertEquals(CombatSafetyEvaluator.incrementalPressure(baseline, lethalOnly), 500);
        Assert.assertEquals(CombatSafetyEvaluator.incrementalPressure(lethalOnly, baseline), -500);
        Assert.assertEquals(CombatSafetyEvaluator.discountedFollowUpValue(10), 5);
        Assert.assertEquals(CombatSafetyEvaluator.discountedFollowUpValue(-10), -5);
        final PublicCombatSnapshot snapshot = fiveCreatures(false, 20);
        final var result = CombatBlockSearch.searchWithPressure(snapshot, values(snapshot, List.of()),
                new CombatSearchBudget(3), readiness(snapshot));
        Assert.assertFalse(result.outcomeDomainComplete());
        Assert.assertFalse(result.reasons().isEmpty());
    }

    @Test
    public void takingSafeDamagePreservesFiveCreatureLethalButTradingDoesNot() {
        final PublicCombatSnapshot snapshot = fiveCreatures(false, 20);
        final PreparedCombatValuation values = values(snapshot, List.of());
        final PublicCombatReadiness readiness = readiness(snapshot);
        final CombatProjection takeHit = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of()));
        final CombatProjection trade = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of(10, List.of(20))));
        final CombatSafetyEvaluator.FollowUp retained = CombatSafetyEvaluator.nextAttack(snapshot, takeHit, readiness, values,
                new CombatSearchBudget(20000));
        final CombatSafetyEvaluator.FollowUp lost = CombatSafetyEvaluator.nextAttack(snapshot, trade, readiness, values,
                new CombatSearchBudget(20000));
        Assert.assertTrue(retained.supported() && retained.searchExhaustive());
        Assert.assertTrue(retained.lethalOpportunity());
        Assert.assertEquals(retained.attackers().size(), 5);
        Assert.assertTrue(lost.supported() && lost.searchExhaustive());
        Assert.assertFalse(lost.lethalOpportunity());
        Assert.assertEquals(lost.attackers().size(), 4);
        Assert.assertTrue(retained.nonterminalUtility() < 10000, "No actual-game terminal bonus in a future opportunity");
    }

    @Test
    public void vigilanceLeavesTheOriginalAttackerAvailableToBlockTheReply() {
        final PublicCombatSnapshot snapshot = fiveCreatures(true, 20);
        final CombatProjection current = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of()));
        final CombatSafetyEvaluator.FollowUp follow = CombatSafetyEvaluator.nextAttack(snapshot, current, readiness(snapshot),
                values(snapshot, List.of()), new CombatSearchBudget(20000));
        Assert.assertTrue(follow.supported() && follow.searchExhaustive());
        Assert.assertFalse(follow.lethalOpportunity());
        Assert.assertFalse(current.survivors().get(10).tapped());
    }

    @Test
    public void imminentLossAndUncertainTurnOrCleanupCannotClaimFuturePressure() {
        final PublicCombatSnapshot lethal = fiveCreatures(false, 2);
        final CombatProjection dying = CombatOutcomePredictor.predict(lethal,
                new CombatAssignment(lethal.attackersToDefenders(), Map.of()));
        Assert.assertFalse(CombatSafetyEvaluator.nextAttack(lethal, dying, readiness(lethal), values(lethal, List.of()),
                new CombatSearchBudget(20000)).supported());
        final PublicCombatSnapshot snapshot = fiveCreatures(false, 20);
        final CombatProjection current = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of()));
        final PublicCombatReadiness ordinary = readiness(snapshot);
        for (final PublicCombatReadiness uncertain : List.of(
                new PublicCombatReadiness(1, ordinary.canAttackNextTurn(), ordinary.blockPairsNextTurn(), List.of()),
                new PublicCombatReadiness(2, ordinary.canAttackNextTurn(), ordinary.blockPairsNextTurn(), List.of("Unprojected cleanup")))) {
            final var follow = CombatSafetyEvaluator.nextAttack(snapshot, current, uncertain, values(snapshot, List.of()), new CombatSearchBudget(20000));
            Assert.assertFalse(follow.supported());
            Assert.assertFalse(follow.lethalOpportunity());
            Assert.assertFalse(follow.reasons().isEmpty());
        }
    }

    @Test
    public void alreadyLostRelationshipCannotBeCreditedAgainWhenTheOtherEndpointTrades() {
        final Map<Integer, PublicCombatSnapshot.Creature> creatures = Map.of(
                10, creature(10, 1, 2, 2, false), 11, creature(11, 1, 2, 2, false),
                20, creature(20, 2, 1, 1, false), 21, creature(21, 2, 2, 2, false));
        final PublicCombatSnapshot snapshot = new PublicCombatSnapshot(2, 1, 2, creatures, players(20, 20),
                Map.of(10, 2), Map.of(10, Set.of(20)), List.of(), List.of(), false);
        final CombatProjection current = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of(10, List.of(20))));
        Assert.assertEquals(current.lostCreatures(), Set.of(20));
        final PreparedCombatValuation.RelationshipValue edge = new PreparedCombatValuation.RelationshipValue(
                new PreparedCombatValuation.RelationshipKey(20, "producer", 21, "consumer", "test", "batch"), 1234);
        final CombatSafetyEvaluator.FollowUp follow = CombatSafetyEvaluator.nextAttack(snapshot, current,
                new PublicCombatReadiness(2, Set.of(21), Map.of(21, Set.of(10, 11)), List.of()), values(snapshot, List.of(edge)),
                new CombatSearchBudget(1000));
        Assert.assertTrue(follow.supported() && follow.searchExhaustive());
        Assert.assertEquals(follow.nonterminalUtility(), 0);
    }

    @Test
    public void followUpSearchSharesTheBudgetAndNeverRecursesIntoAnotherFollowUp() {
        final PublicCombatSnapshot snapshot = fiveCreatures(false, 20);
        final CombatProjection current = CombatOutcomePredictor.predict(snapshot,
                new CombatAssignment(snapshot.attackersToDefenders(), Map.of()));
        final CombatSearchBudget budget = new CombatSearchBudget(3);
        final var follow = CombatSafetyEvaluator.nextAttack(snapshot, current, readiness(snapshot), values(snapshot, List.of()), budget);
        Assert.assertEquals(budget.used(), 3);
        Assert.assertFalse(follow.searchExhaustive());
        Assert.assertFalse(follow.lethalOpportunity());
        final var complete = CombatSafetyEvaluator.nextAttack(snapshot, current, readiness(snapshot), values(snapshot, List.of()), new CombatSearchBudget(20000));
        Assert.assertTrue(complete.searchExhaustive());
        Assert.assertEquals(current.playerLifeAfter().get(2).intValue(), 18);
    }

    private static PublicCombatSnapshot fiveCreatures(final boolean vigilance, final int ownLife) {
        final Map<Integer, PublicCombatSnapshot.Creature> creatures = new LinkedHashMap<>();
        creatures.put(10, creature(10, 1, 2, 2, vigilance));
        for (int id = 20; id < 25; id++) { creatures.put(id, creature(id, 2, 2, 2, false)); }
        return new PublicCombatSnapshot(2, 1, 2, creatures, players(10, ownLife), Map.of(10, 2),
                Map.of(10, Set.of(20, 21, 22, 23, 24)), List.of(), List.of(), false);
    }

    private static PublicCombatReadiness readiness(final PublicCombatSnapshot snapshot) {
        final Map<Integer, Set<Integer>> blocks = new LinkedHashMap<>();
        snapshot.creatures().keySet().stream().filter(id -> id >= 20).forEach(id -> blocks.put(id, Set.of(10)));
        return new PublicCombatReadiness(2, blocks.keySet(), blocks, List.of());
    }

    private static PublicCombatSnapshot.Creature creature(final int id, final int controller, final int power, final int toughness,
            final boolean vigilance) {
        return new PublicCombatSnapshot.Creature(id, controller, power, toughness, 0,
                false, false, false, false, false, false, false, vigilance, false);
    }

    private static Map<Integer, PublicCombatSnapshot.LifeState> players(final int opponentLife, final int ownLife) {
        return Map.of(1, new PublicCombatSnapshot.LifeState(opponentLife, true, true, false, false),
                2, new PublicCombatSnapshot.LifeState(ownLife, true, true, false, false));
    }

    private static PreparedCombatValuation values(final PublicCombatSnapshot snapshot,
            final List<PreparedCombatValuation.RelationshipValue> relationships) {
        final Map<Integer, PreparedCombatValuation.PermanentValue> cards = new LinkedHashMap<>();
        snapshot.creatures().forEach((id, card) -> cards.put(id, new PreparedCombatValuation.PermanentValue(id, card.controllerId(),
                card.controllerId() == 2 ? -100 : 100, 0, 0, List.of())));
        return new PreparedCombatValuation(cards, relationships, ValuationCompleteness.PARTIAL, List.of());
    }
}
