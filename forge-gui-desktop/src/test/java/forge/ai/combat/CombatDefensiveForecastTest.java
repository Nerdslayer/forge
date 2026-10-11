package forge.ai.combat;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.effect.ValuationCompleteness;

/** Public-board defense over a fixed horizon, independent of card-specific scripts. */
public class CombatDefensiveForecastTest {
    @Test
    public void survivalClockMakesPreservationProfitableWithOrdinaryBodyValues() {
        final var board = board(16, false, false);
        final var values = values(board, 74, 78);
        final var budget = new CombatSearchBudget(50000);
        final var evaluator = new CombatDefensiveForecastEvaluator(board, values, readiness(), budget, 150);
        final var saved = evaluator.evaluate(project(board, Map.of()));
        final var traded = evaluator.evaluate(project(board, Map.of(10, List.of(20))));
        Assert.assertTrue(saved.usable(), saved.reasons().toString());
        Assert.assertTrue(traded.usable(), traded.reasons().toString());
        final var saveClock = saved.survivalOpportunity().orElseThrow().horizon();
        final var tradeClock = traded.survivalOpportunity().orElseThrow().horizon();
        Assert.assertEquals(saveClock.attacksSurvived(), 2);
        Assert.assertEquals(tradeClock.attacksSurvived(), 1);
        Assert.assertTrue(saveClock.lethalWithinHorizon());
        Assert.assertEquals(saveClock.steps().subList(0, 2).stream().map(CombatSurvivalHorizonEvaluator.Step::lifeAfter).toList(), List.of(12, 1));
        Assert.assertTrue(saveClock.steps().get(2).lifeAfter() <= 0, "Any chosen lethal attack ends the clock");
        Assert.assertEquals(tradeClock.steps().stream().map(CombatSurvivalHorizonEvaluator.Step::lifeAfter).toList(), List.of(7, -2));
        Assert.assertEquals(CombatSafetyEvaluator.survivalDelta(traded, saved), 150);
        Assert.assertEquals(saved.nonterminalUtility(), traded.nonterminalUtility(), "Tail life/casualties must not leak into ordinary value");
        final var search = CombatBlockSearch.searchWithDefensiveForecast(board, values, budget, readiness(), Map.of(), evaluator::evaluate);
        Assert.assertTrue(search.outcomeDomainComplete(), search.reasons().toString());
        Assert.assertTrue(search.best().orElseThrow().assignment().blockersByAttacker().isEmpty());
        Assert.assertEquals(CombatSafetyEvaluator.incrementalPressure(traded, saved), 105);
    }

    @Test
    public void opportunityIsNearLethalBoundedSignedAndRequiresCompleteResults() {
        final var early = opportunity(0, true, true);
        final var later = opportunity(2, true, true);
        final var censored = opportunity(3, false, true);
        Assert.assertEquals(later.delta(early), 150);
        Assert.assertEquals(early.delta(later), -150);
        Assert.assertEquals(later.delta(later), 0);
        Assert.assertEquals(censored.delta(censored), 0);
        Assert.assertEquals(censored.delta(early), 150);
        Assert.expectThrows(IllegalArgumentException.class, () -> censored.delta(opportunity(1, false, false)));
    }

    @Test
    public void groundedOrTramplingThreatDoesNotInventAnExtraTurnForAChump() {
        for (final boolean trample : List.of(false, true)) {
            final var board = board(16, false, trample);
            final var ready = trample ? readiness() : new PublicCombatReadiness(2, Set.of(10, 11, 20),
                    Map.of(10, Set.of(20), 11, Set.of(), 20, Set.of(11)), List.of());
            final var evaluator = new CombatDefensiveForecastEvaluator(board, values(board, 74, 78), ready,
                    new CombatSearchBudget(50000), 150);
            final var saved = evaluator.evaluate(project(board, Map.of()));
            final var traded = evaluator.evaluate(project(board, Map.of(10, List.of(20))));
            Assert.assertTrue(saved.usable(), saved.reasons().toString());
            Assert.assertTrue(traded.usable(), traded.reasons().toString());
            Assert.assertEquals(CombatSafetyEvaluator.survivalDelta(traded, saved), 0);
        }
    }

    @Test
    public void aSpareBlockerOnlyRemovesCreditWhenItActuallyEqualizesTheClock() {
        final var board = board(16, true, false);
        final var evaluator = new CombatDefensiveForecastEvaluator(board, values(board, 74, 78), readiness(),
                new CombatSearchBudget(50000), 150);
        final var saved = evaluator.evaluate(project(board, Map.of()));
        final var traded = evaluator.evaluate(project(board, Map.of(10, List.of(20))));
        Assert.assertTrue(saved.usable(), saved.reasons().toString());
        Assert.assertTrue(traded.usable(), traded.reasons().toString());
        Assert.assertEquals(saved.survivalOpportunity().orElseThrow().horizon().attacksSurvived(), 2);
        Assert.assertEquals(traded.survivalOpportunity().orElseThrow().horizon().attacksSurvived(), 2);
        Assert.assertEquals(CombatSafetyEvaluator.survivalDelta(traded, saved), 0);
    }

    @Test
    public void horizonRecoveryAndZeroValueRetainThePriorPolicyAndValidationReserve() {
        final var board = board(16, false, false);
        final var values = values(board, 74, 78);
        final var oldBudget = new CombatSearchBudget(50000);
        final var zeroBudget = new CombatSearchBudget(50000);
        final var old = CombatDefensiveForecastEvaluator.search(board, values, readiness(), Map.of(), oldBudget, 12000);
        final var zero = CombatDefensiveForecastEvaluator.search(board, values, readiness(), Map.of(), zeroBudget, 12000, 0);
        Assert.assertEquals(zero, old);
        Assert.assertEquals(zeroBudget.used(), oldBudget.used(), "Zero value must skip all horizon work");
        final var total = new CombatSearchBudget(50000);
        final var planning = total.planningAllowance();
        final var recovered = CombatDefensiveForecastEvaluator.search(board, values, readiness(), Map.of(), planning, 2, 150);
        Assert.assertTrue(recovered.reasons().stream().anyMatch(reason -> reason.startsWith("Survival horizon discarded")));
        Assert.assertTrue(recovered.search().outcomeDomainComplete(), recovered.search().reasons().toString());
        Assert.assertTrue(recovered.search().best().orElseThrow().followUp().orElseThrow().survivalOpportunity().isEmpty());
        Assert.assertEquals(total.remaining() - planning.remaining(), 10000);
    }

    private static CombatSurvivalHorizonEvaluator.Opportunity opportunity(final int survived, final boolean lethal, final boolean complete) {
        return new CombatSurvivalHorizonEvaluator.Opportunity(new CombatSurvivalHorizonEvaluator.Result(complete, survived, lethal,
                List.of(), List.of()), 150);
    }

    @Test
    public void cancelledTailIsIncompleteRatherThanAnUnderstoodZeroBonus() {
        final var board = board(16, false, false);
        final var evaluator = new CombatSurvivalHorizonEvaluator(readiness(), new CombatSearchBudget(1));
        final var horizon = evaluator.evaluate(new CombatSafetyEvaluator.Continuation(board, project(board, Map.of()), values(board, 74, 78)));
        Assert.assertFalse(horizon.complete());
        Assert.assertFalse(horizon.reasons().isEmpty());
        final var incomplete = new CombatSafetyEvaluator.FollowUp(true, true, 0, false, List.of(), List.of(), true, false,
                java.util.Optional.empty(), java.util.Optional.of(new CombatSurvivalHorizonEvaluator.Opportunity(horizon, 150)));
        Assert.assertFalse(incomplete.usable());
    }

    @Test
    public void horizonCanDegradeToThePriorDefensiveForecastBeforeOffensivePressure() {
        final var board = board(16, false, false);
        final var values = values(board, 74, 78);
        final var recovered = CombatDefensiveForecastEvaluator.search(board, values, readiness(), Map.of(),
                new CombatSearchBudget(50000), 200, 150);
        Assert.assertTrue(recovered.reasons().stream().anyMatch(reason -> reason.startsWith("Survival horizon discarded")),
                recovered.reasons().toString());
        Assert.assertTrue(recovered.reasons().stream().anyMatch(reason -> reason.contains("applied; survivalHorizon=false")),
                recovered.reasons().toString());
        Assert.assertTrue(recovered.search().outcomeDomainComplete());
        Assert.assertTrue(recovered.search().best().orElseThrow().followUp().orElseThrow().survivalOpportunity().isEmpty());
    }

    @Test
    public void preservedUniqueBlockerCanOutweighAnImmediateTrade() {
        final var board = board(16, false, false);
        final var values = values(board, 200, 50);
        final var budget = new CombatSearchBudget(200000);
        final var evaluator = new CombatDefensiveForecastEvaluator(board, values, readiness(), budget);
        final var search = CombatBlockSearch.searchWithDefensiveForecast(board, values, budget, readiness(), Map.of(), evaluator::evaluate);
        Assert.assertTrue(search.outcomeDomainComplete(), search.reasons().toString());
        Assert.assertTrue(search.best().orElseThrow().assignment().blockersByAttacker().isEmpty());
        final var future = search.best().orElseThrow().followUp().orElseThrow();
        Assert.assertTrue(future.usable(), future.reasons().toString());
        Assert.assertFalse(future.defensiveLethalRisk());
        Assert.assertTrue(future.attackers().isEmpty(), "The saved nonvigilant blocker cannot also be credited for attacking");
        Assert.assertTrue(future.defensiveDetails().orElseThrow().opponentAttackers().contains(11),
                "The currently undeclared/summoning-sick large threat joins its controller's next attack");
        Assert.assertEquals(future.defensiveDetails().orElseThrow().futureBlocks().get(11), List.of(20));
        Assert.assertTrue(budget.used() < 12000, "A small public board fits the optional live allowance");
    }

    @Test
    public void immediateLethalRequiresBlockingEvenWhenTheBlockerIsUsefulLater() {
        final var board = board(2, false, false);
        final var values = values(board, 200, 50);
        final var budget = new CombatSearchBudget(200000);
        final var evaluator = new CombatDefensiveForecastEvaluator(board, values, readiness(), budget, 150);
        final var result = CombatBlockSearch.searchWithDefensiveForecast(board, values, budget, readiness(), Map.of(), evaluator::evaluate);
        Assert.assertTrue(result.outcomeDomainComplete(), result.reasons().toString());
        Assert.assertEquals(result.best().orElseThrow().assignment().blockersByAttacker().get(10), List.of(20));
        Assert.assertEquals(result.best().orElseThrow().projection().terminal(), CombatProjection.Terminal.NONE);
    }

    @Test
    public void spareDefendersAndTrampleReduceThePreservedBlockersIncrementalBenefit() {
        final var lone = board(16, false, false);
        final var spare = board(9, true, false);
        final var trample = board(16, false, true);
        final var spareEvaluator = new CombatDefensiveForecastEvaluator(spare, values(spare, 200, 50), readiness(),
                new CombatSearchBudget(200000));
        final var traded = spareEvaluator.evaluate(project(spare, Map.of(10, List.of(20))));
        Assert.assertTrue(traded.usable(), traded.reasons().toString());
        Assert.assertFalse(traded.defensiveLethalRisk());
        Assert.assertEquals(traded.defensiveDetails().orElseThrow().futureBlocks().get(11), List.of(21),
                "Trading one flyer leaves the spare available to chump; no sole-blocker assumption");
        Assert.assertTrue(benefit(trample) < benefit(lone), "A one-toughness chump cannot stop nine trample damage");
    }

    @Test
    public void futureLethalProtectionIsSeparateFromTheOrdinaryDiscount() {
        final var baseline = new CombatSafetyEvaluator.FollowUp(true, true, 0, false, List.of(), List.of(), true,
                true, java.util.Optional.empty());
        final var safe = new CombatSafetyEvaluator.FollowUp(true, true, 100, false, List.of(), List.of(), true,
                false, java.util.Optional.empty());
        Assert.assertEquals(CombatSafetyEvaluator.incrementalPressure(baseline, safe), 570);
        Assert.assertEquals(CombatSafetyEvaluator.incrementalPressure(safe, baseline), -570);
    }

    @Test
    public void aWinningCounterattackStopsBeforeTheOpponentTurnWithoutATerminalPointRefund() {
        final var base = board(16, true, false);
        final var players = new LinkedHashMap<>(base.players());
        players.put(1, new PublicCombatSnapshot.LifeState(1, true, true, false, false));
        final var board = new PublicCombatSnapshot(2, 1, 2, base.creatures(), players, base.attackersToDefenders(),
                base.legalBlockers(), List.of(), List.of(), false);
        final var evaluator = new CombatDefensiveForecastEvaluator(board, values(board, 200, 50), readiness(), new CombatSearchBudget(200000), 150);
        final var forecast = evaluator.evaluate(project(board, Map.of()));
        Assert.assertTrue(forecast.usable(), forecast.reasons().toString());
        Assert.assertTrue(forecast.lethalOpportunity());
        Assert.assertFalse(forecast.defensiveLethalRisk());
        Assert.assertEquals(forecast.attackers(), List.of(20, 21));
        Assert.assertTrue(forecast.defensiveDetails().orElseThrow().opponentAttackers().isEmpty());
        Assert.assertEquals(forecast.defensiveDetails().orElseThrow().defensiveUtility(), 0);
        Assert.assertTrue(forecast.nonterminalUtility() < 10000);
    }

    @Test
    public void aGroundOnlyBlockerReceivesNoCreditForStoppingAFlyingThreat() {
        final var board = board(16, false, false);
        final var groundReady = new PublicCombatReadiness(2, Set.of(10, 11, 20),
                Map.of(10, Set.of(20), 11, Set.of(), 20, Set.of(11)), List.of());
        final var evaluator = new CombatDefensiveForecastEvaluator(board, values(board, 200, 50), groundReady,
                new CombatSearchBudget(200000));
        final var forecast = evaluator.evaluate(project(board, Map.of()));
        Assert.assertTrue(forecast.usable(), forecast.reasons().toString());
        Assert.assertTrue(forecast.defensiveDetails().orElseThrow().opponentAttackers().contains(11));
        Assert.assertTrue(forecast.defensiveDetails().orElseThrow().futureBlocks().getOrDefault(11, List.of()).isEmpty());
    }

    @Test
    public void vigilanceAllowsAnInterveningAttackWithoutLosingTheFutureBlock() {
        for (final boolean vigilance : List.of(false, true)) {
            final var base = board(16, false, false);
            final var cards = new LinkedHashMap<>(base.creatures());
            cards.put(20, new PublicCombatSnapshot.Creature(20, 2, 1, 1, 0,
                    false, false, false, false, false, false, false, vigilance, false));
            final var board = new PublicCombatSnapshot(2, 1, 2, cards, base.players(), base.attackersToDefenders(),
                    base.legalBlockers(), List.of(), List.of(), false);
            // The large threat can attack but cannot block; this makes the intervening attack safe.
            final var ready = new PublicCombatReadiness(2, Set.of(10, 11, 20),
                    Map.of(10, Set.of(20), 11, Set.of(20), 20, Set.of()), List.of());
            final var evaluator = new CombatDefensiveForecastEvaluator(board, values(board, 200, 50), ready,
                    new CombatSearchBudget(200000));
            final var forecast = evaluator.evaluate(project(board, Map.of()));
            Assert.assertTrue(forecast.usable(), forecast.reasons().toString());
            Assert.assertEquals(forecast.attackers().contains(20), vigilance);
            Assert.assertEquals(forecast.defensiveDetails().orElseThrow().futureBlocks().get(11), List.of(20));
        }
    }

    @Test
    public void incompleteOptionalForecastNeverLooksLikeZeroRisk() {
        final var board = board(16, false, false);
        final var values = values(board, 200, 50);
        final var budget = new CombatSearchBudget(2);
        final var evaluator = new CombatDefensiveForecastEvaluator(board, values, readiness(), budget);
        final var result = CombatBlockSearch.searchWithDefensiveForecast(board, values, budget, readiness(), Map.of(), evaluator::evaluate);
        Assert.assertFalse(result.outcomeDomainComplete());
        Assert.assertFalse(result.reasons().isEmpty());
    }

    @Test
    public void opponentMayTakeAnUnprofitableImmediateTradeToEnableALethalReply() {
        final Map<Integer, PublicCombatSnapshot.Creature> cards = Map.of(
                10, new PublicCombatSnapshot.Creature(10, 1, 2, 2, 0,
                        false, false, false, false, false, false, false, true, false),
                20, creature(20, 2, 2, 2, false), 21, creature(21, 2, 9, 9, false));
        final var board = new PublicCombatSnapshot(1, 1, 2, cards, Map.of(
                1, new PublicCombatSnapshot.LifeState(9, true, true, false, false),
                2, new PublicCombatSnapshot.LifeState(20, true, true, false, false)),
                Map.of(10, 2), Map.of(10, Set.of(20)), List.of(), List.of(), false);
        final var values = new PreparedCombatValuation(Map.of(
                10, new PreparedCombatValuation.PermanentValue(10, 1, -80, 0, 0, List.of()),
                20, new PreparedCombatValuation.PermanentValue(20, 2, 200, 0, 0, List.of()),
                21, new PreparedCombatValuation.PermanentValue(21, 2, 300, 0, 0, List.of())),
                List.of(), ValuationCompleteness.PARTIAL, List.of());
        final var immediate = CombatBlockSearch.search(board, values, new CombatSearchBudget(10000));
        Assert.assertTrue(immediate.best().orElseThrow().assignment().blockersByAttacker().isEmpty());
        final var budget = new CombatSearchBudget(200000);
        final var ready = new PublicCombatReadiness(2, Set.of(20, 21), Map.of(20, Set.of(10), 21, Set.of(10)), List.of());
        final var combined = CombatBlockSearch.searchWithUndiscountedReply(board, values, budget,
                projection -> CombatSafetyEvaluator.forecastNextAttack(board, projection, ready, values, budget, null, true).evaluation());
        Assert.assertTrue(combined.outcomeDomainComplete(), combined.reasons().toString());
        Assert.assertEquals(combined.best().orElseThrow().assignment().blockersByAttacker().get(10), List.of(20));
        Assert.assertTrue(combined.best().orElseThrow().followUp().orElseThrow().lethalOpportunity());
    }

    @Test
    public void optionalExhaustionRecoversTheSameOldPolicyWithoutConsumingValidationReserve() {
        final var board = board(16, false, false);
        final var values = values(board, 200, 50);
        final var old = CombatBlockSearch.searchWithPressure(board, values, new CombatSearchBudget(20000), readiness());
        final var total = new CombatSearchBudget(20000);
        final var planning = total.planningAllowance();
        final var recovered = CombatDefensiveForecastEvaluator.search(board, values, readiness(), Map.of(), planning, 2);
        Assert.assertTrue(recovered.reasons().stream().anyMatch(reason -> reason.startsWith("Optional defensive forecast discarded")));
        Assert.assertTrue(recovered.search().outcomeDomainComplete(), recovered.search().reasons().toString());
        Assert.assertEquals(recovered.search().best().orElseThrow().assignment(), old.best().orElseThrow().assignment());
        Assert.assertEquals(recovered.search().best().orElseThrow().pressureValue(), old.best().orElseThrow().pressureValue());
        Assert.assertEquals(recovered.search().best().orElseThrow().followUp(), old.best().orElseThrow().followUp());
        Assert.assertEquals(total.remaining() - planning.remaining(), 4000);
    }

    @Test
    public void turnTransitionsCarryTapStunDamageAndShieldStateInsteadOfResettingIt() {
        final var initial = board(16, false, false);
        final var cards = new LinkedHashMap<>(initial.creatures());
        cards.put(10, new PublicCombatSnapshot.Creature(10, 1, 2, 3, 1,
                false, false, false, false, false, false, false, false, false));
        cards.put(20, new PublicCombatSnapshot.Creature(20, 2, 1, 1, 0,
                false, false, false, false, false, false, false, false, true, 1, Integer.MAX_VALUE, 2, 1));
        final var board = new PublicCombatSnapshot(2, 1, 2, cards, initial.players(), Map.of(10, 2),
                initial.legalBlockers(), List.of(), List.of(), false);
        final var values = values(board, 200, 50);
        final var current = project(board, Map.of());
        final var first = CombatTurnProjectionBuilder.advance(board, current, readiness(), values).turn().orElseThrow();
        Assert.assertTrue(first.alternatives().creatures().get(20).tapped());
        Assert.assertEquals(first.alternatives().creatures().get(20).stunCounters(), 1);
        Assert.assertEquals(first.alternatives().creatures().get(20).shieldCounters(), 1);
        final var hold = CombatAttackCandidates.select(first.alternatives(), List.of());
        final var second = CombatTurnProjectionBuilder.advance(hold, project(hold, Map.of()), readiness().forActivePlayer(1),
                first.values()).turn().orElseThrow();
        Assert.assertTrue(second.alternatives().creatures().get(20).tapped(), "No second AI untap on the opponent's turn");
        Assert.assertEquals(second.alternatives().creatures().get(20).stunCounters(), 1);
        Assert.assertEquals(second.alternatives().creatures().get(10).markedDamage(), 0);
        Assert.assertFalse(second.alternatives().creatures().get(10).tapped(), "Original attacking opponent untaps only now");
        final var opponentHold = CombatAttackCandidates.select(second.alternatives(), List.of());
        final var third = CombatTurnProjectionBuilder.advance(opponentHold, project(opponentHold, Map.of()), readiness(),
                second.values()).turn().orElseThrow();
        Assert.assertEquals(third.alternatives().creatures().get(20).stunCounters(), 0);
        Assert.assertTrue(third.alternatives().creatures().get(20).tapped(), "The second actual AI untap consumes the last stun instead");
    }

    private static int benefit(final PublicCombatSnapshot board) {
        final var values = values(board, 200, 50);
        final var evaluator = new CombatDefensiveForecastEvaluator(board, values, readiness(), new CombatSearchBudget(200000));
        final var saved = evaluator.evaluate(project(board, Map.of()));
        final var traded = evaluator.evaluate(project(board, Map.of(10, List.of(20))));
        Assert.assertTrue(saved.usable(), saved.reasons().toString());
        Assert.assertTrue(traded.usable(), traded.reasons().toString());
        return CombatSafetyEvaluator.incrementalPressure(traded, saved);
    }

    private static CombatProjection project(final PublicCombatSnapshot board, final Map<Integer, List<Integer>> blocks) {
        return CombatOutcomePredictor.predict(board, new CombatAssignment(board.attackersToDefenders(), blocks));
    }

    private static PublicCombatReadiness readiness() {
        return new PublicCombatReadiness(2, Set.of(10, 11, 20, 21), Map.of(
                10, Set.of(20, 21), 11, Set.of(20, 21), 20, Set.of(11), 21, Set.of(11)), List.of());
    }

    private static PublicCombatSnapshot board(final int life, final boolean spare, final boolean trample) {
        final Map<Integer, PublicCombatSnapshot.Creature> cards = new LinkedHashMap<>();
        cards.put(10, creature(10, 1, 2, 1, false));
        cards.put(11, creature(11, 1, 9, 9, trample));
        cards.put(20, creature(20, 2, 1, 1, false));
        if (spare) { cards.put(21, creature(21, 2, 1, 1, false)); }
        return new PublicCombatSnapshot(2, 1, 2, cards, Map.of(
                1, new PublicCombatSnapshot.LifeState(30, true, true, false, false),
                2, new PublicCombatSnapshot.LifeState(life, true, true, false, false)),
                Map.of(10, 2), Map.of(10, spare ? Set.of(20, 21) : Set.of(20)), List.of(), List.of(), false);
    }

    private static PublicCombatSnapshot.Creature creature(final int id, final int controller, final int power,
            final int toughness, final boolean trample) {
        return new PublicCombatSnapshot.Creature(id, controller, power, toughness, 0,
                false, false, false, false, false, trample, false, false, false);
    }

    private static PreparedCombatValuation values(final PublicCombatSnapshot board, final int blockerValue, final int smallValue) {
        final Map<Integer, PreparedCombatValuation.PermanentValue> cards = new LinkedHashMap<>();
        board.creatures().forEach((id, card) -> cards.put(id, new PreparedCombatValuation.PermanentValue(id, card.controllerId(),
                card.controllerId() == 2 ? -blockerValue : id == 10 ? smallValue : 300, 0, 0, List.of())));
        return new PreparedCombatValuation(cards, List.of(), ValuationCompleteness.PARTIAL, List.of());
    }
}
