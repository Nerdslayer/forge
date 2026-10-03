package forge.ai.combat;

import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.PlayerResourceValueEvaluator;
import forge.ai.effect.ValuationCompleteness;

public class CombatChumpValueEvaluatorTest {
    @Test
    public void nonlethalChumpsGetHalfSavedLifeCreditFromEitherObserversPerspective() {
        for (final int observer : List.of(1, 2)) {
            final var board = board(observer, 20, creature(10, 1, 8, 8, false), creature(20, 2, 1, 1, false));
            final var assignment = new CombatAssignment(board.attackersToDefenders(), Map.of(10, List.of(20)));
            final var projection = CombatOutcomePredictor.predict(board, assignment);
            final var adjustment = new CombatChumpValueEvaluator(board, new CombatSearchBudget(100))
                    .evaluate(assignment, projection, Map.of());
            final int half = (int) Math.round(PlayerResourceValueEvaluator.evaluateLifeChange(12, 20) * 0.5);
            Assert.assertTrue(adjustment.complete());
            Assert.assertEquals(adjustment.adjustment(), observer == 2 ? -half : half);
            final var selected = CombatBlockSearch.search(board, values(board), new CombatSearchBudget(1000));
            Assert.assertTrue(selected.outcomeDomainComplete());
            Assert.assertTrue(selected.best().orElseThrow().assignment().blockersByAttacker().isEmpty(),
                    "100-point blocker is worth more than half of the saved life, but less than its full value");
        }
    }

    @Test
    public void lethalProtectionTradesSafeBlocksAndFixedBlocksKeepFullCredit() {
        for (final int kind : List.of(0, 1, 2, 3)) {
            final var board = board(2, kind == 0 ? 8 : 20, creature(10, 1, 8, 8, false),
                    creature(20, 2, kind == 1 ? 8 : 1, kind == 1 ? 8 : kind == 2 ? 9 : 1, false));
            final var assignment = new CombatAssignment(board.attackersToDefenders(), Map.of(10, List.of(20)));
            final var projection = CombatOutcomePredictor.predict(board, assignment);
            final var adjustment = new CombatChumpValueEvaluator(board, new CombatSearchBudget(100))
                    .evaluate(assignment, projection, kind == 3 ? assignment.blockersByAttacker() : Map.of());
            Assert.assertTrue(adjustment.complete());
            Assert.assertEquals(adjustment.adjustment(), 0);
            if (kind == 0) {
                Assert.assertFalse(CombatBlockSearch.search(board, values(board), new CombatSearchBudget(1000))
                        .best().orElseThrow().assignment().blockersByAttacker().isEmpty());
            }
        }
    }

    @Test
    public void partialTramplePreventionIsDiscountedWithoutDiscountingLifelinkGain() {
        final var attacker = new PublicCombatSnapshot.Creature(10, 1, 8, 8, 0,
                false, false, false, false, false, true, false, false, false);
        final var board = board(2, 20, attacker, creature(20, 2, 1, 1, true));
        final var assignment = new CombatAssignment(board.attackersToDefenders(), Map.of(10, List.of(20)));
        final var projection = CombatOutcomePredictor.predict(board, assignment);
        Assert.assertEquals(projection.playerLifeAfter().get(2).intValue(), 14, "Seven trample damage and one lifelink life");
        final var adjustment = new CombatChumpValueEvaluator(board, new CombatSearchBudget(100))
                .evaluate(assignment, projection, Map.of());
        Assert.assertEquals(adjustment.adjustment(), -(int) Math.round(PlayerResourceValueEvaluator.evaluateLifeChange(13, 14) * 0.5));
        Assert.assertTrue(adjustment.complete());
    }

    @Test
    public void counterfactualCancellationIsIncompleteRatherThanFullLifeCredit() {
        final var board = board(2, 20, creature(10, 1, 8, 8, false), creature(20, 2, 1, 1, false));
        final var assignment = new CombatAssignment(board.attackersToDefenders(), Map.of(10, List.of(20)));
        final var projection = CombatOutcomePredictor.predict(board, assignment);
        Assert.assertFalse(new CombatChumpValueEvaluator(board, new CombatSearchBudget(0))
                .evaluate(assignment, projection, Map.of()).complete());
    }

    @Test
    public void mixedTradesAndChumpsDiscountOnlyTheDamagePreventedByTheChump() {
        final var single = board(2, 20, creature(10, 1, 8, 8, false), creature(20, 2, 1, 1, false));
        final Map<Integer, PublicCombatSnapshot.Creature> cards = new LinkedHashMap<>(single.creatures());
        cards.put(11, creature(11, 1, 2, 2, false));
        cards.put(21, creature(21, 2, 2, 2, false));
        final var board = new PublicCombatSnapshot(2, 1, 2, cards, single.players(), Map.of(10, 2, 11, 2),
                Map.of(10, Set.of(20), 11, Set.of(21)), List.of(), List.of(), false);
        final var assignment = new CombatAssignment(board.attackersToDefenders(), Map.of(10, List.of(20), 11, List.of(21)));
        final var projection = CombatOutcomePredictor.predict(board, assignment);
        Assert.assertEquals(projection.lostCreatures(), Set.of(11, 20, 21));
        final var adjustment = new CombatChumpValueEvaluator(board, new CombatSearchBudget(100))
                .evaluate(assignment, projection, Map.of());
        Assert.assertTrue(adjustment.complete());
        Assert.assertEquals(adjustment.adjustment(), -(int) Math.round(PlayerResourceValueEvaluator.evaluateLifeChange(12, 20) * 0.5));
    }

    @Test
    public void mixedGangTradesRetainTheirDamagePlanWhenRemovingASeparateChump() {
        final var single = board(2, 20, creature(10, 1, 8, 8, false), creature(20, 2, 1, 1, false));
        final Map<Integer, PublicCombatSnapshot.Creature> cards = new LinkedHashMap<>(single.creatures());
        cards.put(11, creature(11, 1, 4, 4, false));
        cards.put(21, creature(21, 2, 2, 2, false));
        cards.put(22, creature(22, 2, 2, 2, false));
        final var board = new PublicCombatSnapshot(2, 1, 2, cards, single.players(), Map.of(10, 2, 11, 2),
                Map.of(10, Set.of(20), 11, Set.of(21, 22)), List.of(), List.of(), false);
        final var assignment = new CombatAssignment(board.attackersToDefenders(), Map.of(10, List.of(20), 11, List.of(21, 22)));
        final var candidate = CombatDamageOptimizer.optimizeBlockGroups(board, assignment, values(board), new CombatSearchBudget(1000))
                .best().orElseThrow();
        Assert.assertEquals(candidate.projection().lostCreatures(), Set.of(11, 20, 21, 22));
        final var adjustment = new CombatChumpValueEvaluator(board, new CombatSearchBudget(100))
                .evaluate(candidate.assignment(), candidate.projection(), Map.of(), candidate.damagePlan());
        Assert.assertTrue(adjustment.complete());
        Assert.assertEquals(adjustment.adjustment(), -(int) Math.round(PlayerResourceValueEvaluator.evaluateLifeChange(12, 20) * 0.5));
    }

    private static PublicCombatSnapshot board(final int observer, final int life,
            final PublicCombatSnapshot.Creature attacker, final PublicCombatSnapshot.Creature blocker) {
        return new PublicCombatSnapshot(observer, 1, 2, Map.of(10, attacker, 20, blocker), Map.of(
                1, new PublicCombatSnapshot.LifeState(20, true, true, false, false),
                2, new PublicCombatSnapshot.LifeState(life, true, true, false, false)),
                Map.of(10, 2), Map.of(10, Set.of(20)), List.of(), List.of(), false);
    }

    private static PublicCombatSnapshot.Creature creature(final int id, final int controller, final int power,
            final int toughness, final boolean lifelink) {
        return new PublicCombatSnapshot.Creature(id, controller, power, toughness, 0,
                false, false, false, false, false, false, lifelink, false, false);
    }

    private static PreparedCombatValuation values(final PublicCombatSnapshot board) {
        final Map<Integer, PreparedCombatValuation.PermanentValue> cards = new LinkedHashMap<>();
        board.creatures().forEach((id, card) -> cards.put(id, new PreparedCombatValuation.PermanentValue(id, card.controllerId(),
                card.controllerId() == board.observingPlayerId() ? -100 : 100, 0, 0, List.of())));
        return new PreparedCombatValuation(cards, List.of(), ValuationCompleteness.PARTIAL, List.of());
    }
}
