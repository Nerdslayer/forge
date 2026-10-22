package forge.ai.combat;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.testng.Assert;
import org.testng.annotations.Test;

import forge.ai.effect.ValuationCompleteness;

public class CombatAttackRefinerTest {
    @Test
    public void removalUsesTheOriginalBaselineAndOnlyAcceptsFullyEvaluatedCandidates() {
        final var board = board();
        final var values = values(board);
        final var budget = new CombatSearchBudget(100000, 500, () -> 0L);
        final var reasons = new LinkedHashSet<String>();
        final var evaluation = new CombatAttackSearch.Evaluation(board, values,
                new PublicCombatReadiness(2, Set.of(), Map.of(), List.of()), budget, reasons);
        evaluation.evaluate(CombatAttackCandidates.select(board, List.of()));
        final var original = evaluation.evaluate(board);
        final var allowance = budget.childAllowance(4000);
        final var optional = evaluation.withBudget(allowance);
        final var refined = CombatAttackRefiner.refine(original, Map.of(),
                assignment -> optional.evaluate(CombatAttackCandidates.assign(board, assignment)),
                CombatAttackSearch::compare, allowance, new LinkedHashSet<>());
        Assert.assertEquals(refined.attackers(), List.of(11, 12));
        Assert.assertTrue(CombatAttackSearch.compare(refined, original) >= 0);
        Assert.assertEquals(refined.replyAdjustment(), original.replyAdjustment());
        Assert.assertSame(evaluation.evaluate(board), original, "Optional work must not replace or invalidate core cached results");
        final var fixedAllowance = budget.childAllowance(4000);
        final var fixedEvaluation = evaluation.withBudget(fixedAllowance);
        final var fixed = CombatAttackRefiner.refine(original, Map.of(10, 2),
                assignment -> fixedEvaluation.evaluate(CombatAttackCandidates.assign(board, assignment)),
                CombatAttackSearch::compare, fixedAllowance, new LinkedHashSet<>());
        Assert.assertEquals(fixed.attackers(), original.attackers());
    }

    @Test
    public void failedOptionalTrialDoesNotConsumeTheValidationReserveOrPoisonRequiredCoverage() {
        final var board = board();
        final var budget = new CombatSearchBudget(100000, 500, () -> 0L);
        final var reasons = new LinkedHashSet<String>();
        final var required = new CombatAttackSearch.Evaluation(board, values(board),
                new PublicCombatReadiness(2, Set.of(), Map.of(), List.of()), budget.planningAllowance(), reasons);
        required.evaluate(CombatAttackCandidates.select(board, List.of()));
        final var original = required.evaluate(board);
        final int reasonCount = reasons.size();
        final var allowance = budget.planningAllowance().childAllowance(1);
        final var optional = required.withBudget(allowance);
        final var result = CombatAttackRefiner.refine(original, Map.of(),
                assignment -> optional.evaluate(CombatAttackCandidates.assign(board, assignment)),
                CombatAttackSearch::compare, allowance, new LinkedHashSet<>());
        Assert.assertSame(result, original);
        Assert.assertEquals(reasons.size(), reasonCount);
        Assert.assertSame(required.evaluate(board), original);
        Assert.assertTrue(budget.remaining() >= 20000);
    }

    private static PublicCombatSnapshot board() {
        final var cards = Map.of(10, creature(10, 1, 0, 2), 11, creature(11, 1, 2, 4),
                12, creature(12, 1, 2, 2), 20, creature(20, 2, 0, 5));
        return new PublicCombatSnapshot(1, 1, 2, cards, Map.of(
                1, new PublicCombatSnapshot.LifeState(20, true, true, false, false),
                2, new PublicCombatSnapshot.LifeState(20, true, true, false, false)),
                Map.of(10, 2, 11, 2, 12, 2), Map.of(10, Set.of(20), 11, Set.of(20), 12, Set.of(20)), List.of(), List.of(), false);
    }

    private static PublicCombatSnapshot.Creature creature(final int id, final int controller, final int power, final int toughness) {
        return new PublicCombatSnapshot.Creature(id, controller, power, toughness, 0,
                false, false, false, false, false, false, false, false, false);
    }

    private static PreparedCombatValuation values(final PublicCombatSnapshot board) {
        final Map<Integer, PreparedCombatValuation.PermanentValue> result = new java.util.LinkedHashMap<>();
        board.creatures().forEach((id, card) -> result.put(id, new PreparedCombatValuation.PermanentValue(id, card.controllerId(),
                card.controllerId() == 1 ? -100 : 100, 0, 0, List.of())));
        return new PreparedCombatValuation(result, List.of(), ValuationCompleteness.PARTIAL, List.of());
    }
}
